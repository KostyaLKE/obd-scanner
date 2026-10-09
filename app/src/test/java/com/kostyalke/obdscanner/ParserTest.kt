package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.DemoTransport
import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ObdProtocol
import com.kostyalke.obdscanner.obd.DtcDecoder
import com.kostyalke.obdscanner.obd.DtcKind
import com.kostyalke.obdscanner.obd.MonitorState
import com.kostyalke.obdscanner.obd.ObdService
import com.kostyalke.obdscanner.obd.Pids
import com.kostyalke.obdscanner.obd.Readiness
import com.kostyalke.obdscanner.obd.ResponseParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    @Test
    fun dtcDecoding() {
        assertEquals("P0133", DtcDecoder.decode(0x01, 0x33))
        assertEquals("P1557", DtcDecoder.decode(0x15, 0x57))
        assertEquals("C0300", DtcDecoder.decode(0x43, 0x00))
        assertEquals("B1234", DtcDecoder.decode(0x92, 0x34))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
        assertEquals("P2A01", DtcDecoder.decode(0x2A, 0x01))
    }

    @Test
    fun vagCodes() {
        assertEquals(16485, DtcDecoder.vagCode("P0101"))
        assertEquals(17965, DtcDecoder.vagCode("P1557"))
        assertEquals(17544, DtcDecoder.vagCode("P1136"))
        assertEquals(17978, DtcDecoder.vagCode("P1570"))
        assertNull(DtcDecoder.vagCode("P2002"))
        assertNull(DtcDecoder.vagCode("U0100"))
    }

    @Test
    fun canSingleFrame() {
        val msgs = ResponseParser.parse(listOf("7E8 06 41 00 BE 3F A8 13"), ObdProtocol.CAN_11_500)
        assertEquals(1, msgs.size)
        assertEquals("Двигатель (7E8)", msgs[0].ecu)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), msgs[0].data.toList())
    }

    @Test
    fun canMultiFrameVin() {
        val lines = listOf(
            "7E8 10 14 49 02 01 57 56 57",
            "7E8 21 5A 5A 5A 36 52 5A 37",
            "7E8 22 35 30 37 38 37 39 31",
        )
        val msgs = ResponseParser.parse(lines, ObdProtocol.CAN_11_500)
        assertEquals(1, msgs.size)
        val d = msgs[0].data
        assertEquals(0x14, d.size)
        val vin = d.drop(3).map { it.toChar() }.joinToString("")
        assertEquals("WVWZZZ6RZ75078791", vin)
    }

    @Test
    fun canDtcsWithCountAndTwoEcus() {
        val lines = listOf("7E8 06 43 02 01 01 15 57", "7E9 02 43 00")
        val msgs = ResponseParser.parse(lines, ObdProtocol.CAN_11_500)
        val dtcs = DtcDecoder.parse(msgs, DtcKind.STORED)
        assertEquals(listOf("P0101", "P1557"), dtcs.map { it.code })
    }

    @Test
    fun can29bit() {
        val msgs = ResponseParser.parse(listOf("18 DA F1 10 03 41 0D 3C"), ObdProtocol.CAN_29_500)
        assertEquals(1, msgs.size)
        assertEquals(listOf(0x41, 0x0D, 0x3C), msgs[0].data.toList())
        assertTrue(msgs[0].ecu.startsWith("Двигатель"))
    }

    @Test
    fun kLineDtcsMultiLine() {
        val lines = listOf(
            "48 6B 10 43 04 01 15 57 02 34 00",
            "48 6B 10 43 03 80 00 00 00 00 00",
        )
        val msgs = ResponseParser.parse(lines, ObdProtocol.ISO_9141)
        val dtcs = DtcDecoder.parse(msgs, DtcKind.STORED)
        assertEquals(listOf("P0401", "P1557", "P0234", "P0380"), dtcs.map { it.code })
        assertEquals("Двигатель (10)", dtcs[0].ecu)
    }

    @Test
    fun kLineNoSpaces() {
        val msgs = ResponseParser.parse(listOf("486B10410C1AF8C4"), ObdProtocol.KWP_FAST)
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), msgs[0].data.toList())
        val rpm = Pids.byId[0x0C]!!.decode(intArrayOf(0x1A, 0xF8))
        assertEquals(1726.0, rpm, 0.01)
    }

    @Test
    fun readinessDiesel() {
        val r = Readiness.decode(intArrayOf(0x82, 0x0F, 0x89, 0x80))!!
        assertTrue(r.milOn)
        assertEquals(2, r.dtcCount)
        assertTrue(r.diesel)
        val egr = r.monitors.first { it.name.startsWith("Рециркуляция") }
        assertEquals(MonitorState.INCOMPLETE, egr.state)
        assertEquals(MonitorState.COMPLETE, r.monitors.first { it.name == "Давление наддува" }.state)
    }

    @Test
    fun protocolFromDpn() {
        assertEquals(ObdProtocol.KWP_FAST, ObdProtocol.fromDpn("A5"))
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("6"))
        assertEquals(ObdProtocol.ISO_9141, ObdProtocol.fromDpn("A3"))
    }

    @Test
    fun cleanLinesStripsEchoAndSearching() {
        val lines = Elm327.cleanLines("0100", "0100\rSEARCHING...\rBUS INIT: ...OK\r48 6B 10 41 00 BE 3F A8 13 B9\r\r")
        assertEquals(listOf("48 6B 10 41 00 BE 3F A8 13 B9"), lines)
    }

    /** Полный сценарий на эмуляторе: подключение, ошибки, стоп-кадр, VIN, стирание. */
    @Test
    fun demoEndToEnd() = runBlocking {
        val t = DemoTransport(kLine = true)
        t.open()
        val elm = Elm327(t) {}
        elm.initialize(null) {}
        assertEquals(ObdProtocol.KWP_FAST, elm.protocol)
        val obd = ObdService(elm)

        val pids = obd.supportedPids(1)
        assertTrue(0x0C in pids && 0x10 in pids && 0x49 in pids)

        val report = obd.readDtcs()
        assertEquals(listOf("P0401", "P1557"), report.stored.map { it.code })
        assertEquals(listOf("P0234"), report.pending.map { it.code })
        assertTrue(report.readiness!!.diesel)
        val ff = report.freezeFrame!!
        assertEquals("P0401", ff.dtc)
        assertEquals(3750.0, ff.values.first { it.first.pid == 0x0C }.second, 0.01)

        val info = obd.vehicleInfo()
        assertEquals("TMBPY16Y123456789", info.vin)
        assertEquals("EOBD (Европа)", info.obdStandard)

        obd.clearDtcs()
        val after = obd.readDtcs()
        assertTrue(after.all.isEmpty())
    }

    @Test
    fun protocolFallbackWithKw0() = runBlocking {
        val t = DemoTransport(strictKeyword = true)
        t.open()
        val elm = Elm327(t) {}
        elm.initialize(null) {}
        assertEquals(ObdProtocol.KWP_FAST, elm.protocol)
        assertEquals(13.9, elm.voltage!!, 0.2)
        // Второй раз с запомненным протоколом подключается сразу.
        val elm2 = Elm327(t) {}
        elm2.initialize('5') {}
        assertEquals(ObdProtocol.KWP_FAST, elm2.protocol)
    }

    @Test
    fun noCarAnswerGivesTriedList() = runBlocking {
        val t = object : com.kostyalke.obdscanner.elm.ObdTransport {
            var last = ""
            override val name = "x"
            override suspend fun open() {}
            override suspend fun write(text: String) { last = text.trim() }
            override suspend fun readUntilPrompt(timeoutMs: Long) =
                if (last.startsWith("AT")) "OK\r" else "UNABLE TO CONNECT\r"
            override suspend fun clearInput() {}
            override fun close() {}
        }
        try {
            Elm327(t) {}.initialize(null) {}
            org.junit.Assert.fail()
        } catch (e: com.kostyalke.obdscanner.elm.ConnectProblem) {
            assertEquals(com.kostyalke.obdscanner.elm.ConnectProblem.Stage.CAR, e.stage)
            assertEquals(8, e.tried.size)
        }
    }

    @Test
    fun releaseInfoParsing() {
        val r = com.kostyalke.obdscanner.update.ReleaseInfo.parse(
            """{"versionCode": 9, "versionName": "0.1.9", "sha256": "ABCDEF", "size": 6600000, "notes": "Автообновление"}"""
        )
        assertEquals(9, r.versionCode)
        assertEquals("abcdef", r.sha256)
        assertEquals("Автообновление", r.notes)
    }

    /** Fabia II 2012, 1.6 TDI CR на CAN: ISO-TP, VIN в несколько кадров, сажевый фильтр. */
    @Test
    fun demoCanCommonRail() = runBlocking {
        val t = DemoTransport()
        t.open()
        val elm = Elm327(t) {}
        elm.initialize(null) {}
        assertEquals(ObdProtocol.CAN_11_500, elm.protocol)
        val obd = ObdService(elm)

        val pids = obd.supportedPids(1)
        assertTrue(0x23 in pids && 0x7A in pids && 0x7C in pids && 0x62 in pids)

        val report = obd.readDtcs()
        assertEquals(listOf("P0401", "P2463"), report.stored.map { it.code })
        assertEquals(listOf("P0299"), report.pending.map { it.code })
        assertTrue(report.permanent.isEmpty())
        assertTrue(report.stored.all { it.ecu == "Двигатель (7E8)" })

        val dpf = obd.readPid(Pids.byId[0x7A]!!)!!
        assertTrue(dpf in 5.0..16.0)
        val rail = obd.readPid(Pids.byId[0x23]!!)!!
        assertTrue(rail in 250.0..1200.0)

        val info = obd.vehicleInfo()
        assertEquals("TMBEG25J5C3057412", info.vin)
        assertEquals("ECM-EngineControl", info.ecuName)
    }

    @Test
    fun dpfPidWithoutSensorIsNaN() {
        val v = Pids.byId[0x7A]!!.decode(intArrayOf(0x00, 0x01, 0x00))
        assertTrue(v.isNaN())
        assertEquals(12.5, Pids.byId[0x7A]!!.decode(intArrayOf(0x01, 0x04, 0xE2)), 0.001)
    }

    private suspend fun demoElm(): Elm327 {
        val t = DemoTransport()
        t.open()
        val elm = Elm327(t) {}
        elm.initialize(null) {}
        return elm
    }

    /**
     * Полный опрос, как в приложении: «сырой» CAN с переменной длиной кадра, таблица шлюза
     * (адреса TP2.0 ≠ VCDS), KWP2000; приборка и ГУР — только по UDS.
     */
    @Test
    fun vagScanAll() = runBlocking {
        val elm = demoElm()
        val logLines = ArrayList<String>()
        val results = elm.exclusive { send ->
            com.kostyalke.obdscanner.vag.VagScanner.scanAll(send, elm.protocol.code, { logLines += it }) { _, _ -> }
        }
        val by = results.associateBy { it.module.address }
        assertEquals(listOf(16785), by[0x01]!!.dtcs.map { it.code })
        assertEquals(1, by[0x01]!!.tpAddress)
        assertEquals(listOf(283), by[0x03]!!.dtcs.map { it.code })
        assertEquals("6R0907379AL · ESP MK60EC1", by[0x03]!!.partNumber)
        // BCM: VCDS 09, а логический адрес TP2.0 — 0x20 (из таблицы шлюза)
        assertEquals(0x20, by[0x09]!!.tpAddress)
        assertTrue(by[0x15]!!.responded && by[0x15]!!.tpAddress == 0x05)
        // Приборка и ГУР ответили по UDS
        assertEquals("UDS", by[0x17]!!.protocol)
        assertEquals("5J0920810C · KOMBI", by[0x17]!!.partNumber)
        assertEquals("UDS", by[0x44]!!.protocol)
        assertTrue(!by[0x02]!!.responded)
        assertTrue(logLines.any { it.contains("ATPB C0 01") })
        assertTrue(logLines.any { it.contains("09→TP20") })
        // После опроса обычный OBD работает
        assertEquals(listOf("P0401", "P2463"), ObdService(elm).readDtcs().stored.map { it.code })
    }

    /** Без таблицы шлюза и без протокола B (клон): ATSP6+CAF0+ATV1, известные адреса + перебор. */
    @Test
    fun vagRawModeFallback() = runBlocking {
        val elm = demoElm()
        val sent = ArrayList<String>()
        val results = elm.exclusive { send ->
            val wrapped: suspend (String, Long) -> com.kostyalke.obdscanner.elm.ElmReply = { c, t ->
                sent += c
                if (c == "ATPBC001") com.kostyalke.obdscanner.elm.ElmReply(c, "?", listOf("?")) else send(c, t)
            }
            com.kostyalke.obdscanner.vag.VagScanner.scanAll(wrapped, elm.protocol.code, {}) { _, _ -> }
        }
        assertTrue("ATV1" in sent)
        assertEquals(listOf(283), results.first { it.module.address == 0x03 }.dtcs.map { it.code })
    }

    /** Сброс ошибок блока по логическому адресу TP2.0 и резервная копия. */
    @Test
    fun vagClearAndBackup() = runBlocking {
        val elm = demoElm()
        val V = com.kostyalke.obdscanner.vag.VagModules
        val abs = V.scanList.first { it.address == 0x03 }
        val (after, backup) = elm.exclusive { send ->
            val tp = com.kostyalke.obdscanner.vag.Tp20(send)
            tp.enterRawMode()
            try {
                assertTrue(tp.open(0x03))
                tp.startSession()
                tp.clearDtcs()
                tp.close()
                com.kostyalke.obdscanner.vag.Tp20.scanModule(tp, abs, 0x03) to
                    com.kostyalke.obdscanner.vag.Tp20.backupModule(tp, abs, 0x03)
            } finally {
                tp.restore()
            }
        }
        assertTrue(after.dtcs.isEmpty())
        assertEquals(setOf(0x9B, 0x9A, 0x91), backup!!.records.keys)
        val json = com.kostyalke.obdscanner.data.BackupStore.toJson(listOf(backup), "TMBEG25J5C3057412", 0L)
        assertEquals(1, json.getJSONArray("modules").length())
    }

    @Test
    fun vagCodeConversion() {
        assertEquals("P0401", com.kostyalke.obdscanner.vag.VagModules.toObdCode(16785))
        assertEquals("P1557", com.kostyalke.obdscanner.vag.VagModules.toObdCode(17965))
        assertEquals(null, com.kostyalke.obdscanner.vag.VagModules.toObdCode(283))
        assertEquals("00283", com.kostyalke.obdscanner.vag.VagModules.format(283))
    }
}
