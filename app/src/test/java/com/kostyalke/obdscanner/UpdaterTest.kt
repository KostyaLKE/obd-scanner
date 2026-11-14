package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.update.ReleaseInfo
import com.kostyalke.obdscanner.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Обновление: неизменяемый адрес версии, проверка SHA-256, чистка недокачанного, понятные ошибки. */
class UpdaterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun immutableApkUrlIsUsedWhenPresent() {
        val r = ReleaseInfo.parse("""{"versionCode": 17, "sha256": "AB",
            "apkUrl": "https://github.com/KostyaLKE/obd-scanner/releases/download/build-17/obd-scanner.apk"}""")
        assertEquals("https://github.com/KostyaLKE/obd-scanner/releases/download/build-17/obd-scanner.apk", r.downloadUrl)
    }

    @Test
    fun oldMetadataAndForeignUrlFallBackToLatest() {
        val latest = "https://github.com/KostyaLKE/obd-scanner/releases/latest/download/obd-scanner.apk"
        assertEquals(latest, ReleaseInfo.parse("""{"versionCode": 16, "sha256": "AB"}""").downloadUrl)
        // Чужой адрес из метаданных не используем.
        assertEquals(latest, ReleaseInfo.parse("""{"versionCode": 17, "sha256": "AB", "apkUrl": "https://evil.example/x.apk"}""").downloadUrl)
    }

    @Test
    fun verifiedCopyKeepsFileOnlyWhenHashMatches() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val target = File(tmp.root, "obd-scanner-17.apk")
        Updater.copyVerified(data.inputStream(), target, sha(data), data.size.toLong()) {}
        assertTrue(target.exists())
        assertEquals(data.size.toLong(), target.length())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun mismatchedHashLeavesNothingBehind() {
        val data = ByteArray(1000) { 1 }
        val target = File(tmp.root, "obd-scanner-17.apk")
        try {
            Updater.copyVerified(data.inputStream(), target, sha(ByteArray(1) { 2 }), -1) {}
            fail("файл с чужой контрольной суммой принят")
        } catch (e: IOException) { /* ожидаемо */ }
        assertFalse(target.exists())
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test
    fun brokenDownloadLeavesNothingBehindAndCanBeRetried() {
        val data = ByteArray(1000) { 3 }
        val target = File(tmp.root, "obd-scanner-17.apk")
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100) 3 else throw IOException("сеть пропала")
        }
        try {
            Updater.copyVerified(failing, target, sha(data), 1000) {}
            fail("обрыв загрузки не замечен")
        } catch (e: IOException) { /* ожидаемо */ }
        assertTrue(tmp.root.listFiles()!!.isEmpty())
        Updater.copyVerified(data.inputStream(), target, sha(data), 1000) {} // повтор
        assertTrue(target.exists())
    }

    @Test
    fun alreadyDownloadedVerifiedFileIsRecognised() {
        val data = ByteArray(500) { 7 }
        val target = File(tmp.root, "obd-scanner-17.apk").apply { writeBytes(data) }
        assertTrue(Updater.isVerified(target, sha(data)))
        assertFalse(Updater.isVerified(target, sha(ByteArray(2))))
        assertFalse(Updater.isVerified(File(tmp.root, "нет.apk"), sha(data)))
    }

    @Test
    fun installErrorsAreDistinctAndNeverAdviseUninstall() {
        val texts = listOf(2, 3, 4, 5, 6, 7, 1).map { Updater.installError(it, null) }
        assertEquals("разные ошибки слиты в одну", texts.size, texts.toSet().size)
        assertTrue(texts.none { it.contains("Удалите приложение") })
        assertTrue(Updater.installError(5, null).contains("истори")) // конфликт: предупреждаем о данных
    }
}
