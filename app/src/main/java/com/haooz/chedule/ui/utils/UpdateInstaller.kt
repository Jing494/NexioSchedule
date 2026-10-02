package com.haooz.chedule.ui.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.haooz.chedule.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 应用更新：下载与安装的公共入口，弹窗与设置页共用 */
internal object UpdateInstaller {

    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 本地文件名带变体后缀（普通 ""/wear "-wear"）：两个变体的缓存不会互相顶掉，
    // 也让"日志里看到的包名"能直接反映出变体。
    fun apkFile(context: Context, tag: String): File =
        File(context.filesDir, "update-$tag${UpdateChecker.variantSuffix(context)}.apk")

    private fun partFile(context: Context, tag: String): File =
        File(context.filesDir, "update-$tag${UpdateChecker.variantSuffix(context)}.apk.part")

    fun hasValidApk(context: Context, tag: String): Boolean {
        return UpdateChecker.isLikelyCompleteApk(apkFile(context, tag))
    }

    /**
     * 下载 APK：先写 .part，完整后再原子 rename，避免半成品被当成可安装包。
     * @param onProgress 0f..1f，在主线程回调
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        tag: String,
        onProgress: suspend (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (apkUrl.isBlank()) throw IllegalArgumentException("未找到下载链接")
        val finalFile = apkFile(context, tag)
        val part = partFile(context, tag)
        if (part.exists()) part.delete()
        try {
            val connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.connect()
            val fileSize = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (fileSize > 0) {
                            val p = (totalRead.toFloat() / fileSize).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(p) }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (fileSize > 0 && part.length() != fileSize) {
                part.delete()
                throw java.io.IOException("APK 下载不完整: ${part.length()}/$fileSize")
            }
            if (!UpdateChecker.isLikelyCompleteApk(part)) {
                part.delete()
                throw java.io.IOException("APK 包体校验失败")
            }
            if (finalFile.exists()) finalFile.delete()
            if (!part.renameTo(finalFile)) {
                part.delete()
                throw java.io.IOException("APK 落盘失败")
            }
            finalFile
        } catch (e: Exception) {
            runCatching { part.delete() }
            throw e
        }
    }

    /**
     * 安装 APK：优先 Shizuku 静默安装，失败回退系统安装器。
     * @param onInstallingChanged 主线程回调安装中状态
     * @param onFinished 主线程回调结束（静默成功/失败回退系统安装器/系统安装器已拉起）
     */
    fun installApk(
        context: Context,
        file: File,
        onInstallingChanged: (Boolean) -> Unit,
        onFinished: (() -> Unit)? = null,
    ) {
        // ★ 交给安装器之前的最后一道闸：包的签名必须等于**本机当前安装**的签名。
        //   两个变体（普通 / wear）签的不是同一把钥匙，拿错包只会失败或逼用户卸载重装；
        //   顺带挡住"下载被替换/半成品"这类事。校验不过就删掉，绝不交给安装器。
        if (!signatureMatchesOwn(context, file)) {
            file.delete()
            Toast.makeText(context, "安装包与当前版本签名不一致（可能是另一个变体），已删除", Toast.LENGTH_LONG).show()
            onFinished?.invoke()
            return
        }
        if (ShizukuManager.isShizukuRunning() && ShizukuManager.checkSelfPermission()) {
            onInstallingChanged(true)
            installScope.launch {
                val (ok, message) = withContext(Dispatchers.IO) {
                    ShizukuManager.silentInstallApk(file.absolutePath)
                }
                onInstallingChanged(false)
                if (ok) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    onFinished?.invoke()
                } else {
                    Toast.makeText(context, "静默安装失败，已改用系统安装器", Toast.LENGTH_SHORT).show()
                    onInstallingChanged(true)
                    launchSystemInstaller(context, file)
                    onFinished?.invoke()
                }
            }
        } else {
            onInstallingChanged(true)
            launchSystemInstaller(context, file)
            onFinished?.invoke()
        }
    }

    /** 该 APK 的签名证书 SHA-256 是否等于本机自己的；读不出来就当不匹配（宁可让人重下） */
    private fun signatureMatchesOwn(context: Context, file: File): Boolean {
        val own = UpdateChecker.ownSignerSha256(context)
        if (own.isBlank()) return false
        return try {
            val info = context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return false
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return false
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(signer.toByteArray()).joinToString("") { "%02x".format(it) } == own
        } catch (_: Exception) {
            false
        }
    }

    private fun launchSystemInstaller(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
