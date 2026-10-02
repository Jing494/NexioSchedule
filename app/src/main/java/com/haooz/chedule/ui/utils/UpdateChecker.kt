package com.haooz.chedule.ui.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 更新 APK 的唯一清理入口。
 *
 * 策略与 [UpdateInstaller] 下载路径对齐，按 **tag 有效性** 保留，而不是「按修改时间只留最新」：
 * - 目标 tag（update_settings.latest_tag）且包体完整 → 保留
 * - 其它 tag 的 update-*.apk → 删除
 * - 不完整/半成品（含 .part）→ 删除，即使它 mtime 最新
 * - 没有 latest_tag 时：至多保留一个完整 APK，同样先丢掉半成品
 */
internal object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val PREF_UPDATE = "update_settings"
    private const val KEY_LATEST_TAG = "latest_tag"
    private const val APK_PREFIX = "update-"
    private const val APK_SUFFIX = ".apk"
    private const val PART_SUFFIX = ".part"
    private const val MIN_COMPLETE_APK_BYTES = 512L * 1024L

    /**
     * 本 fork **普通变体**的签名证书 SHA-256（公开信息）。
     *
     * 为什么需要它：同一个 Release 里会放两个变体的 APK ——
     *   · 普通版：本 fork keystore 签的（能覆盖安装你现在手机上那版）；
     *   · wear 版：与**手表 rpk 同一把** keystore 签的，文件名带 `-wear`
     *     （小米穿戴 interconnect 要求手表 rpk 与 APK 同包名且同签名）。
     * 两个变体签名不同 → Android 不允许互相覆盖安装 → 更新必须按变体分流。
     * 分流依据就是**自己的签名**：等于下面的常量 = 普通变体；不等于 = wear/自签变体。
     *
     * ★ 必须与 .github/workflows 里的 EXPECT_CERT 一致（tools/ci_check.py 会钉住两边）
     */
    const val FORK_CERT_SHA256 = "a7fdc7b704db284774a0124fb13495b5e6ee4a05f6430547ad8ec21aba5ac938"

    /** 本变体的附件名后缀：普通变体空串，wear 变体 "-wear" */
    fun variantSuffix(context: Context): String = if (isWearVariant(context)) "-wear" else ""

    /**
     * 当前安装是不是 wear 变体 —— **只看自己的签名**，不看文件名/版本号（那些都能被改）。
     *
     * ★ 取不到自己签名时按**普通变体**处理（fail-safe，宁可给普通包）：
     *   万一判成 wear，普通用户会永远收不到更新；判成普通最坏只是"白下一趟" ——
     *   因为安装前还有一道签名预检会把不匹配的包拦下（见 UpdateInstaller.signatureMatchesOwn）。
     */
    fun isWearVariant(context: Context): Boolean {
        val own = ownSignerSha256(context)
        return own.isNotBlank() && own != FORK_CERT_SHA256
    }

    /** 本机当前安装包的签名证书 SHA-256（小写十六进制）；取不到返回空串（调用方按"不匹配"处理） */
    fun ownSignerSha256(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    data class GiteeRelease(
        val tagName: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String,
        val createdAt: String
    )

    /**
     * 解析版本号为数字序列。
     * 格式: [v]MAJOR.MINOR.PATCH[-DATE] 或 [v]MAJOR.MINOR.PATCH.BETA[-DATE]
     * 例: 1.5.0-0905 → [1,5,0]；1.5.0.2-0905 → [1,5,0,2]
     * 日期后缀不参与比较。
     */
    fun parseVersion(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return cleaned.split('.').map { it.toIntOrNull() ?: 0 }
    }

    /** 是否 beta 版（第 4 段版本号存在） */
    fun isBetaVersion(raw: String): Boolean = parseVersion(raw).size >= 4

    /** 比较版本：remote 是否比 local 更新。忽略日期后缀。 */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = parseVersion(remote)
        val l = parseVersion(local)
        val max = maxOf(r.size, l.size)
        for (i in 0 until max) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    /**
     * 取该 release 里**本变体**的 .apk 附件下载地址；没有则返回空串。
     *
     * 普通变体不认带 `-wear` 的附件，wear 变体只认带 `-wear` 的 —— 两个变体签名不同，
     * 拿错包要么装不上、要么逼用户卸载重装，所以在"挑包"这一层就分流。
     */
    private fun apkUrlOf(release: com.google.gson.JsonObject, wear: Boolean): String {
        val assets = release.getAsJsonArray("assets") ?: return ""
        for (i in 0 until assets.size()) {
            val a = assets[i].asJsonObject
            val assetName = a.get("name")?.asString ?: ""
            if (!assetName.endsWith(".apk")) continue
            val isWearAsset = assetName.substringBeforeLast(".apk").endsWith("-wear")
            if (isWearAsset != wear) continue
            return a.get("browser_download_url")?.asString ?: ""
        }
        return ""
    }

    // 需在 IO 线程调用。
    // stable: 正式通道，只跳过显式预发布（prerelease）
    // beta: 可检测正式版 + beta 版
    // 两个通道都额外要求：release 必须带 .apk 附件（见循环里的说明）
    fun checkForUpdate(context: Context, source: String = "gitee", channel: String = "stable"): Pair<Boolean, GiteeRelease?> {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            // 本变体（普通 / wear）：只认与**自己签名**匹配的那一类附件
            val wearVariant = isWearVariant(context)
            // 更新源 = **本 fork 自己的两个仓库**（不再指向上游）：
            // 上游包与本 fork 签名不同，下载过去会装不上/要求卸载重装。
            // 国内优先 Gitee（默认 source=gitee），GitHub 作为备选。
            val baseUrl = if (source == "github") {
                "https://api.github.com/repos/Jing494/NexioSchedule/releases"
            } else {
                "https://gitee.com/api/v5/repos/jing494/nexio-schedule_fork/releases"
            }
            val url = "$baseUrl?page=1&per_page=10&direction=desc&t=${System.currentTimeMillis()}"
            val request = okhttp3.Request.Builder().url(url).apply {
                if (source == "github") {
                    header("Accept", "application/vnd.github.v3+json")
                }
            }.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}")
                return Pair(false, null)
            }

            val responseBody = response.body?.string() ?: return Pair(false, null)
            val arr = com.google.gson.JsonParser.parseString(responseBody).asJsonArray
            var best: com.google.gson.JsonObject? = null
            var bestVer = ""
            var bestApkUrl = ""
            for (i in 0 until arr.size()) {
                val release = arr[i].asJsonObject
                val tag = release.get("tag_name")?.asString ?: continue
                val ver = tag.removePrefix("v")

                if (channel == "stable") {
                    val isPre = release.get("prerelease")?.asBoolean ?: false
                    // 正式通道：只跳过**显式预发布**。
                    //
                    // 原来还额外跳过"第4段版本号"（isBetaVersion），那是为了避开**上游**的 beta 包；
                    // 但更新源已改成本 fork 自己的仓库，而本 fork 正是用第 4 段递增
                    //（1.5.6.1 / 1.5.6.2 …）来让 App 识别出"有新版本"的（MAJOR.MINOR.PATCH 与上游相同，
                    // 不动它才能保持版本号与上游对齐）。所以这里不能再按第 4 段过滤。
                    if (isPre) continue
                }
                // beta 通道：正式 + beta 均可；stable 通道已在上方过滤

                // ★ 只认「带**本变体**安装包」的版本。
                //   Gitee 建 release 时会**自动挂上源码 zip**，而 APK 是 CI 过一会儿才
                //   attach 上去的（实测 gh5 差了 8 分钟）。这段窗口里"最新 release"没有 .apk，
                //   一旦选中它，下载地址就是空的 —— 点「开始下载」只会弹「未找到下载链接」。
                //   所以没有 .apk 的版本直接跳过，继续找「最新的、且带包的」。
                val apk = apkUrlOf(release, wearVariant)
                if (apk.isBlank()) continue

                if (best == null || isNewerVersion(ver, bestVer)) {
                    best = release
                    bestVer = ver
                    bestApkUrl = apk
                }
            }
            val json = best
            if (json == null) return Pair(false, null)
            val tagName = json.get("tag_name")?.asString ?: ""
            val name = json.get("name")?.asString ?: ""
            val body = json.get("body")?.asString ?: ""
            val htmlUrl = json.get("html_url")?.asString ?: ""
            val createdAt = json.get("created_at")?.asString ?: ""
            val apkUrl = bestApkUrl

            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }

            val tagVersion = tagName.removePrefix("v")
            val appVersion = currentVersion.removePrefix("v")
            val hasUpdate = isNewerVersion(tagVersion, appVersion)

            Log.d(TAG, "检查完成: channel=$channel, hasUpdate=$hasUpdate, remote=$tagVersion, local=$appVersion")
            Pair(hasUpdate, GiteeRelease(tagName, name, body, htmlUrl, apkUrl, createdAt))
        } catch (e: Exception) {
            Log.e(TAG, "检查更新失败", e)
            Pair(false, null)
        }
    }

    /** 当前待安装目标 tag；无则 null */
    fun currentKeepTag(context: Context): String? {
        return context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getString(KEY_LATEST_TAG, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 包体是否像完整 APK：ZIP 魔数 + 最小体积，避免把半成品当可安装包 */
    fun isLikelyCompleteApk(file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() < MIN_COMPLETE_APK_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 4L) return false
                val header = ByteArray(4)
                raf.readFully(header)
                // ZIP local file header: PK\x03\x04
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 从下载文件名取 tag：`update-<tag>.apk` 与 `update-<tag>-wear.apk` 都认。
     * 变体后缀必须在这里剥掉，否则清理逻辑会把 wear 包当成"tag 对不上"删掉。
     */
    fun apkTagOrNull(fileName: String): String? {
        if (!fileName.startsWith(APK_PREFIX) || !fileName.endsWith(APK_SUFFIX)) return null
        val tag = fileName.removePrefix(APK_PREFIX).removeSuffix(APK_SUFFIX)
        return tag.removeSuffix("-wear")
    }

    /**
     * 按 tag 清理 filesDir 下的更新包。
     * [keepTag] 为目标版本；null/空则不按 tag 保，只保证「不留下半成品、至多一个完整包」。
     */
    fun cleanOldApks(context: Context, keepTag: String?) {
        try {
            val keep = keepTag?.takeIf { it.isNotBlank() }
            val filesDir = context.filesDir
            val candidates = filesDir.listFiles()?.filter { file ->
                file.isFile && file.name.startsWith(APK_PREFIX) &&
                    (file.name.endsWith(APK_SUFFIX) || file.name.endsWith(PART_SUFFIX))
            } ?: return

            var keptComplete = false
            for (file in candidates) {
                val name = file.name
                val isPart = name.endsWith(PART_SUFFIX)
                val tag = if (isPart) null else apkTagOrNull(name)

                if (isPart) {
                    if (file.delete()) Log.d(TAG, "清理下载中间态: $name")
                    continue
                }

                val complete = isLikelyCompleteApk(file)
                val shouldKeep = when {
                    keep != null && tag == keep && complete -> true
                    keep != null -> false
                    complete && !keptComplete -> true
                    else -> false
                }
                if (shouldKeep) {
                    keptComplete = true
                    continue
                }
                if (file.delete()) {
                    Log.d(TAG, "已清理APK: $name complete=$complete keepTag=$keep")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧APK失败", e)
        }
    }

    /**
     * 启动 / 通用清理入口：与检查更新后的 cleanOldApks 同一策略。
     * 不再「按 mtime 只留最新」，避免半成品挤掉完好旧包。
     */
    fun cleanupTransientApks(context: Context) {
        cleanOldApks(context, currentKeepTag(context))
    }
}
