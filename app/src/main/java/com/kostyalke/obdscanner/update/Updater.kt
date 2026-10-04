package com.kostyalke.obdscanner.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Описание сборки из version.json, который CI кладёт в релиз рядом с APK. */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val notes: String,
) {
    companion object {
        fun parse(json: String): ReleaseInfo {
            val o = JSONObject(json)
            return ReleaseInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName", ""),
                sha256 = o.getString("sha256").lowercase(),
                size = o.optLong("size", -1),
                notes = o.optString("notes", ""),
            )
        }
    }
}

/**
 * Самообновление из GitHub Releases.
 * Тихая установка без участия человека обычному приложению Android не разрешает:
 * первый раз система спросит разрешение и подтверждение, дальше (Android 12+,
 * когда приложение уже ставило себя само) обновления обычно проходят без вопросов.
 */
object Updater {
    private const val BASE = "https://github.com/KostyaLKE/obd-scanner/releases/latest/download/"

    /** Результат установки от системы (через [InstallResultReceiver]). */
    val installResult = MutableStateFlow<String?>(null)

    fun currentVersionCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val c = open(BASE + "version.json?t=" + System.currentTimeMillis())
        try {
            if (c.responseCode != 200) throw IOException("GitHub ответил ${c.responseCode}")
            ReleaseInfo.parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Скачивает APK в кэш и сверяет SHA-256 — битый или подменённый файл не ставим. */
    suspend fun download(ctx: Context, rel: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "obd-scanner-${rel.versionCode}.apk")
        val c = open(BASE + "obd-scanner.apk")
        try {
            if (c.responseCode != 200) throw IOException("GitHub ответил ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (hash != rel.sha256) {
                file.delete()
                throw IOException("Файл скачался с ошибкой (контрольная сумма не совпала). Попробуйте ещё раз.")
            }
            file
        } finally {
            c.disconnect()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Передаёт APK системному установщику. Приложение закроется и обновится. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("obd-scanner.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** Получает ответ системы: нужно подтверждение человека, успех или ошибка. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installResult.value = null
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.installResult.value = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Не удалось обновить поверх: подпись отличается. Удалите приложение и установите заново по ссылке."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места на телефоне"
                    else -> "Не удалось установить" + (msg?.let { ": $it" } ?: "")
                }
            }
        }
    }
}
