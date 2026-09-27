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

    /** 取该 release 里第一个 .apk 附件的下载地址；没有 .apk 附件则返回空串 */
    private fun apkUrlOf(release: com.google.gson.JsonObject): String {
        val assets = release.getAsJsonArray("assets") ?: return ""
        for (i in 0 until assets.size()) {
            val a = assets[i].asJsonObject
            val assetName = a.get("name")?.asString ?: ""
            if (assetName.endsWith(".apk")) return a.get("browser_download_url")?.asString ?: ""
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

                // ★ 只认「带安装包」的版本。
                //   Gitee 建 release 时会**自动挂上源码 zip**，而 APK 是 CI 过一会儿才
                //   attach 上去的（实测 gh5 差了 8 分钟）。这段窗口里"最新 release"没有 .apk，
                //   一旦选中它，下载地址就是空的 —— 点「开始下载」只会弹「未找到下载链接」。
                //   所以没有 .apk 的版本直接跳过，继续找「最新的、且带包的」。
                val apk = apkUrlOf(release)
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

    fun apkTagOrNull(fileName: String): String? {
        if (!fileName.startsWith(APK_PREFIX) || !fileName.endsWith(APK_SUFFIX)) return null
        return fileName.removePrefix(APK_PREFIX).removeSuffix(APK_SUFFIX)
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
