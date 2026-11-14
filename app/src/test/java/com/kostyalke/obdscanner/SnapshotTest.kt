package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.data.BackupStore
import com.kostyalke.obdscanner.vag.ModuleBackup
import com.kostyalke.obdscanner.vag.VagModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Снимок данных блоков: пустое и частичное не выдаётся за полную копию; хватает данных для идентификации. */
class SnapshotTest {

    private val abs = VagModule(0x03, "ABS")
    private val kombi = VagModule(0x17, "Приборная панель")

    private val full = ModuleBackup(abs, mapOf(0x9B to intArrayOf(1), 0x9A to intArrayOf(2)),
        protocol = "KWP2000", tpAddress = 0x03, requested = listOf(0x9B, 0x9A), partNumber = "6R0907379AL")
    private val partial = ModuleBackup(kombi, mapOf(0xF187 to intArrayOf(0x35)),
        protocol = "UDS", canIds = 0x714 to 0x77E, requested = listOf(0xF187, 0x0600))
    private val empty = ModuleBackup(abs, emptyMap(), protocol = "KWP2000", tpAddress = 0x03, requested = listOf(0x9B))

    @Test
    fun emptyIsNotSuccessAndPartialIsMarked() {
        assertTrue(full.complete)
        assertFalse(partial.complete)
        assertEquals(listOf(0x0600), partial.missing)
        assertFalse(empty.hasData)
        assertFalse(empty.complete)
    }

    @Test
    fun jsonKeepsIdentificationAndCompleteness() {
        val j = BackupStore.toJson(listOf(full, partial), vin = null, time = 0L, appVersion = "0.1.17",
            failed = listOf(VagModule(0x15, "Подушки") to "блок отказал"))
        assertEquals("obd-scanner-vag-snapshot-2", j.getString("format"))
        assertEquals("0.1.17", j.getString("appVersion"))
        assertTrue(j.getBoolean("vinMissing"))
        assertFalse(j.getBoolean("complete"))
        assertFalse(j.getBoolean("restoreVerified"))
        val mods = j.getJSONArray("modules")
        val m0 = mods.getJSONObject(0)
        assertEquals("KWP2000", m0.getString("protocol"))
        assertEquals("03", m0.getString("tpAddress"))
        assertEquals("6R0907379AL", m0.getString("partNumber"))
        val m1 = mods.getJSONObject(1)
        assertEquals("714/77E", m1.getString("canIds"))
        assertEquals("0600", m1.getJSONArray("missing").getString(0))
        assertEquals("блок отказал", j.getJSONArray("notRead").getJSONObject(0).getString("reason"))
    }
}
