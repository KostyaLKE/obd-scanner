package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.data.HistoryStore
import com.kostyalke.obdscanner.data.ReportMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** История отчётов: без перезаписи, без скрытого удаления, повреждённый файл не прячет остальные. */
class HistoryStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = HistoryStore(tmp.root)

    @Test
    fun twoSavesInOneSecondGiveTwoReports() {
        val s = store()
        val a = s.save("Отчёт диагностики · A\nтекст A", 1000L)
        val b = s.save("Отчёт диагностики · B\nтекст B", 1000L)
        assertNotEquals(a.id, b.id)
        assertEquals(2, s.list().size)
        assertTrue(s.list().any { it.text.contains("текст A") })
    }

    @Test
    fun nothingIsDeletedSilently() {
        val s = store()
        repeat(105) { s.save("Отчёт диагностики · $it", it * 1000L) }
        assertEquals(105, s.list().size)
        // Удаление лишнего — только явным вызовом.
        assertEquals(5, s.prune(keep = 100))
        assertEquals(100, s.list().size)
        assertEquals(0, s.prune(keep = 0)) // 0 = хранить всё
    }

    @Test
    fun brokenFileDoesNotHideOthers() {
        val s = store()
        s.save("Отчёт диагностики · хороший", 1000L)
        File(tmp.root, "2020-01-01_00-00-00.txt").mkdir() // «файл», который нельзя прочитать
        val bad = s.save("Отчёт диагностики · с битыми метаданными", 5000L)
        File(tmp.root, bad.id + ".json").writeText("{не json")
        val list = s.list()
        assertEquals(3, list.size)
        assertEquals(1, list.count { it.broken })
        assertTrue(list.any { it.text.contains("хороший") })
        assertTrue(list.any { it.text.contains("битыми метаданными") && !it.broken })
    }

    @Test
    fun metaLinkAndNoteSurviveReopen() {
        val before = store().save("Отчёт диагностики · до", 1000L, ReportMeta(kind = "до сброса", vin = "TMB123", errors = 2, complete = true))
        val after = store().save("Отчёт диагностики · после", 9000L,
            ReportMeta(kind = "после сброса", linkedTo = before.id, errors = 0, complete = false, demo = true))
        store().setNote(after, "заменил датчик")
        val list = store().list() // новый экземпляр = «после перезапуска»
        val a = list.first { it.id == after.id }
        assertEquals(before.id, a.meta.linkedTo)
        assertEquals("заменил датчик", a.meta.note)
        assertTrue(a.meta.demo)
        assertFalse(a.meta.complete!!)
        assertEquals(2, list.first { it.id == before.id }.meta.errors)
    }

    @Test
    fun oldReportsWithoutMetaAreStillListed() {
        File(tmp.root, "2026-10-09_10-00-00.txt").writeText("Отчёт диагностики · 09.10.2026 10:00\nстарый формат")
        val r = store().list().single()
        assertEquals("09.10.2026 10:00", r.title)
        assertFalse(r.broken)
    }

    @Test
    fun noTemporaryFilesLeftOrListed() {
        val s = store()
        s.save("Отчёт диагностики · x", 1000L)
        File(tmp.root, "2026-01-01_00-00-00.txt.tmp").writeText("недописанный")
        assertEquals(1, s.list().size)
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") && it.name.startsWith("1970") })
    }

    @Test
    fun deleteRemovesReportAndMeta() {
        val s = store()
        val r = s.save("Отчёт диагностики · x", 1000L, ReportMeta(kind = "блоки"))
        s.delete(r)
        assertTrue(s.list().isEmpty())
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }
}
