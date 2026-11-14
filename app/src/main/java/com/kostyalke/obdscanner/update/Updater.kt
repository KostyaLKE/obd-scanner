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
import java.io.InputStream
import kotlinx.coroutines.sync.Mutex
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
    /** Неизменяемый адрес APK именно этой сборки (новые релизы); null — старый формат метаданных. */
    val apkUrl: String? = null,
) {
    /**
     * Откуда качать. Адрес конкретной сборки не меняется, поэтому новый релиз во время
     * загрузки не подменит файл. Принимаем только адреса релизов нашего репозитория.
     */
    val downloadUrl: String
        get() = apkUrl?.takeIf { it.startsWith(Updater.RELEASES + "download/") } ?: (Updater.RELEASES + "latest/download/obd-scanner.apk")

    companion object {
        fun parse(json: String): ReleaseInfo {
            val o = JSONObject(json)
            return ReleaseInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName", ""),
                sha256 = o.getString("sha256").lowercase(),
                size = o.optLong("size", -1),
                notes = o.optString("notes", ""),
                apkUrl = o.optString("apkUrl", "").ifEmpty { null },
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
    const val RELEASES = "https://github.com/KostyaLKE/obd-scanner/releases/"
    private const val BASE = RELEASES + "latest/download/"

    /** Результат установки от системы (через [InstallResultReceiver]). */
    val installResult = MutableStateFlow<String?>(null)

    /**
     * Идёт ли диагностика (подключение, чтение, сброс, VAG, сохранение отчёта).
     * Задаёт экран; пока true — установка не начинается ни из приложения, ни из фоновой проверки.
     */
    @Volatile var isBusy: () -> Boolean = { false }

    /** Одна загрузка или установка за раз. */
    private val lock = Mutex()

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

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Уже скачанный файл с верной контрольной суммой — повторно не качаем. */
    fun isVerified(file: File, sha256: String): Boolean =
        file.isFile && runCatching { sha256(file) == sha256.lowercase() }.getOrDefault(false)

    /**
     * Пишет поток во временный файл «.part», сверяет SHA-256 и только потом даёт файлу
     * итоговое имя. При обрыве или несовпадении ничего не остаётся — можно повторить.
     */
    fun copyVerified(input: InputStream, target: File, sha256: String, total: Long, onProgress: (Float) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            input.use { inp ->
                part.outputStream().use { out ->
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
            if (hash != sha256.lowercase()) {
                throw IOException("Файл скачался с ошибкой (контрольная сумма не совпала). Попробуем ещё раз позже.")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить скачанный файл")
        } finally {
            part.delete()
        }
    }

    /** Скачивает APK конкретной сборки в кэш и сверяет SHA-256 — битый или подменённый файл не ставим. */
    suspend fun download(ctx: Context, rel: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) throw IOException("Обновление уже загружается")
        try {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            val file = File(dir, "obd-scanner-${rel.versionCode}.apk")
            // Старые и недокачанные файлы убираем, готовый файл этой версии оставляем.
            dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
            if (isVerified(file, rel.sha256)) return@withContext file
            val c = open(rel.downloadUrl)
            try {
                if (c.responseCode != 200) throw IOException("GitHub ответил ${c.responseCode}")
                copyVerified(c.inputStream, file, rel.sha256, c.contentLengthLong.takeIf { it > 0 } ?: rel.size, onProgress)
                file
            } finally {
                c.disconnect()
            }
        } finally {
            lock.unlock()
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /**
     * Передаёт APK системному установщику (не в главном потоке). Обновление ставится поверх:
     * история и настройки сохраняются. Неуспешная сессия установщика освобождается.
     */
    suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        if (isBusy()) throw IOException("Идёт диагностика — обновление установится после неё")
        if (!lock.tryLock()) throw IOException("Обновление уже устанавливается")
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            try {
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
            } catch (e: Exception) {
                runCatching { installer.abandonSession(id) }
                throw e
            }
        } finally {
            lock.unlock()
        }
    }

    /** Понятное объяснение отказа установщика. Удалять приложение не советуем: пропадёт история. */
    fun installError(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена. Обновление останется скачанным — можно установить позже."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Телефон заблокировал установку (защита или политика устройства)."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Файл обновления повреждён — будет скачан заново."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Обновление не подходит к установленной версии (подпись или номер сборки). " +
                "Не удаляйте приложение — пропадёт история отчётов. Отправьте журнал разработчику."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места на телефоне для обновления."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Эта сборка несовместима с телефоном (версия Android или процессор)."
        else -> "Не удалось установить обновление" + (message?.let { ": $it" } ?: "")
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
                // Сессию установщика освобождаем, иначе она висит в системе.
                val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) {
                    File(context.cacheDir, "update").listFiles()?.forEach { it.delete() }
                }
                Updater.installResult.value =
                    Updater.installError(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}
