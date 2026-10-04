package com.haooz.chedule.ui.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * 更新 APK 的校验与清理入口。
 *
 * 清理策略与 [UpdateInstaller] 下载路径对齐，按 **tag 有效性** 保留，而不是「按修改时间只留最新」：
 * - 目标 tag（update_settings.latest_tag）且包体完整 → 保留
 * - 其它 tag 的 update-*.apk → 删除
 * - 不完整/半成品（含 .part）→ 删除，即使它 mtime 最新
 * - 没有 latest_tag 时：至多保留一个完整 APK，同样先丢掉半成品
 *
 * 「完整」的判据见 [verifyApk]：必须真的能被 PackageManager 解析，而不是只看 ZIP 魔数。
 */
internal object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val PREF_UPDATE = "update_settings"
    private const val KEY_LATEST_TAG = "latest_tag"
    private const val KEY_SHA_PREFIX = "apk_sha256_"
    private const val KEY_SIZE_PREFIX = "apk_size_"
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
        val createdAt: String,
        /** Release 资产下发的 SHA-256（GitHub 为 digest="sha256:<hex>"）；拿不到时为 null */
        val apkSha256: String? = null,
        /** Release 资产声明的字节数 */
        val apkSize: Long? = null
    )

    /** 校验结论；[reason] 用于日志与用户提示，仅在失败时有值 */
    data class ApkCheck(val ok: Boolean, val reason: String? = null)

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
            // ★ 上游这轮新增：同时记下该附件的 sha256（GitHub 下发 digest）与体积，供安装前完整性校验用。
            //   必须取**本变体**的那一个附件（与 apkUrlOf 同一套 -wear 过滤）——
            //   否则 wear 用户会拿普通包的摘要去校验 wear 包（或反之），永远校验失败。
            var apkSha256: String? = null
            var apkSize: Long? = null
            json.getAsJsonArray("assets")?.let { assets ->
                for (i in 0 until assets.size()) {
                    val a = assets[i].asJsonObject
                    val assetName = a.get("name")?.asString ?: ""
                    if (!assetName.endsWith(".apk")) continue
                    val isWearAsset = assetName.substringBeforeLast(".apk").endsWith("-wear")
                    if (isWearAsset != wearVariant) continue
                    // GitHub 资产下发 digest（"sha256:<hex>"），部分平台直接给 sha256；都没有就退化为解析级校验
                    val digest = a.get("digest")?.takeIf { !it.isJsonNull }?.asString
                        ?: a.get("sha256")?.takeIf { !it.isJsonNull }?.asString
                    apkSha256 = digest
                        ?.replace("sha256:", "", ignoreCase = true)
                        ?.trim()
                        ?.takeIf { it.length == 64 }
                    apkSize = a.get("size")?.takeIf { !it.isJsonNull }?.asLong
                    break
                }
            }

            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }

            val tagVersion = tagName.removePrefix("v")
            val appVersion = currentVersion.removePrefix("v")
            val hasUpdate = isNewerVersion(tagVersion, appVersion)

            Log.d(TAG, "检查完成: channel=$channel, hasUpdate=$hasUpdate, remote=$tagVersion, local=$appVersion")
            Pair(hasUpdate, GiteeRelease(tagName, name, body, htmlUrl, apkUrl, createdAt, apkSha256, apkSize))
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

    /** 记录该 tag 安装包的期望校验值，供后续复核使用 */
    fun rememberApkDigest(context: Context, tag: String, sha256: String?, sizeBytes: Long?) {
        val shaKey = KEY_SHA_PREFIX + tag
        val sizeKey = KEY_SIZE_PREFIX + tag
        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE).edit {
            if (sha256.isNullOrBlank()) remove(shaKey) else putString(shaKey, sha256)
            if (sizeBytes == null || sizeBytes <= 0L) remove(sizeKey) else putLong(sizeKey, sizeBytes)
        }
    }

    fun rememberedApkSha256(context: Context, tag: String): String? =
        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getString(KEY_SHA_PREFIX + tag, null)
            ?.takeIf { it.isNotBlank() }

    fun rememberedApkSize(context: Context, tag: String): Long? =
        context.getSharedPreferences(PREF_UPDATE, Context.MODE_PRIVATE)
            .getLong(KEY_SIZE_PREFIX + tag, -1L)
            ?.takeIf { it > 0L }

    /** 流式计算文件 SHA-256（大文件避免一次性读入内存） */
    fun sha256(file: File): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e(TAG, "SHA-256 计算失败: ${file.name}", e)
        null
    }

    /**
     * 包体是否像完整 APK：ZIP 魔数 + 最小体积。
     *
     * 仅作为**廉价前置筛选**，不能单独作为「可安装」判据——被截断的包只要保留了
     * 文件头就能通过。真正的判定见 [verifyApk]。
     */
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
     * 安装包完整性校验：能不能安全交给系统安装器。
     *
     * 分层校验，任一层失败即判定不可用，并给出可展示的原因：
     * 1. 体积 + ZIP 魔数：挡住明显的半成品/空文件
     * 2. ZIP 中央目录 + AndroidManifest.xml：挡住截断包与「HTML 错误页伪装成 APK」
     * 3. PackageManager 解析归档：与系统安装器同一套解析逻辑，能提前复现「解析失败」
     * 4. 包名一致、versionCode 递增：挡住张冠李戴的包
     * 5. 签名证书与已安装应用一致：挡住签名不符导致的安装失败
     * 6. （可选）服务端下发的 SHA-256：字节级校验
     *
     * 需在 IO 线程调用。
     */
    fun verifyApk(context: Context, file: File, expectedSha256: String? = null): ApkCheck {
        if (!isLikelyCompleteApk(file)) {
            return ApkCheck(false, "安装包不完整或已损坏")
        }

        // 截断包通常保留文件头但缺少中央目录，这里先卡一道
        if (!hasZipCentralDirectory(file)) {
            return ApkCheck(false, "安装包不完整（中央目录损坏）")
        }

        val pm = context.packageManager
        val archive = try {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: Exception) {
            Log.e(TAG, "解析安装包异常: ${file.name}", e)
            return ApkCheck(false, "安装包解析失败：${e.message ?: e.javaClass.simpleName}")
        } ?: return ApkCheck(false, "安装包解析失败：包体损坏")

        if (archive.packageName != context.packageName) {
            return ApkCheck(false, "包名不匹配：${archive.packageName}")
        }

        val installed = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        if (!sameSigner(installed, archive)) {
            return ApkCheck(false, "签名与已安装应用不一致")
        }

        val installedCode = installed?.longVersionCode ?: -1L
        if (installedCode >= 0L && archive.longVersionCode <= installedCode) {
            return ApkCheck(false, "安装包版本(${archive.longVersionCode})不高于当前($installedCode)")
        }

        expectedSha256?.takeIf { it.isNotBlank() }?.let { want ->
            val actual = sha256(file) ?: return ApkCheck(false, "校验值计算失败")
            if (!actual.equals(want.trim(), ignoreCase = true)) {
                return ApkCheck(false, "校验值不匹配，文件可能已损坏")
            }
        }

        return ApkCheck(true)
    }

    /** ZIP 中央目录可读且含 AndroidManifest.xml */
    private fun hasZipCentralDirectory(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null && zip.entries().hasMoreElements()
        }
    } catch (_: Exception) {
        false
    }

    /** 签名一致性：优先比对历史签名链，兼容签名轮换 */
    private fun sameSigner(installed: PackageInfo?, archive: PackageInfo?): Boolean {
        val installedCerts = signingCerts(installed)
        val archiveCerts = signingCerts(archive)
        if (installedCerts == null || archiveCerts == null) return true // 拿不到就放行，交给系统安装器裁决
        return installedCerts.any { archiveCerts.contains(it) }
    }

    private fun signingCerts(info: PackageInfo?): List<String>? {
        val signingInfo = info?.signingInfo ?: return null
        val signatures: Array<Signature>? = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures
            ?.map { sig -> sig.toByteArray().toHexString() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

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

                // 用完整校验（而非仅 ZIP 魔数）判定，损坏包必须删掉，
                // 否则会被下一轮 hasValidApk 反复当成「已下载」继续复用
                val expectedSha = if (tag != null) rememberedApkSha256(context, tag) else null
                val check = verifyApk(context, file, expectedSha)
                val complete = check.ok
                if (!complete) {
                    Log.w(TAG, "清理损坏APK: $name 原因=${check.reason}")
                }
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
