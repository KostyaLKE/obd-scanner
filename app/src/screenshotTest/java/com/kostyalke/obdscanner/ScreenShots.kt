package com.kostyalke.obdscanner

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.kostyalke.obdscanner.obd.Dtc
import com.kostyalke.obdscanner.obd.DtcDatabase
import com.kostyalke.obdscanner.obd.DtcKind
import com.kostyalke.obdscanner.obd.DtcReport
import com.kostyalke.obdscanner.obd.FreezeFrame
import com.kostyalke.obdscanner.obd.Monitor
import com.kostyalke.obdscanner.obd.MonitorState
import com.kostyalke.obdscanner.obd.Pids
import com.kostyalke.obdscanner.obd.Readiness
import com.kostyalke.obdscanner.obd.VehicleInfo
import com.kostyalke.obdscanner.ui.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Рендер экранов без эмулятора: ./gradlew recordPaparazziDebug -Pscreenshots */
class ScreenShots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.NEXUS_5.copy(softButtons = false), maxPercentDifference = 0.1)

    private val db = DtcDatabase(
        DtcDatabase.parseTsv(File("src/main/assets/dtc_ru.tsv").readText()),
        DtcDatabase.parseTsv(File("src/main/assets/dtc_hints_ru.tsv").readText()),
    )
    private val conn = ConnState.Connected("OBDII", "ISO 14230-4 KWP2000, быстрая инициализация (K-line)", "ELM327 v1.5", 12.4,
        listOf("Адаптер v2.1 — скорее всего урезанный клон. При сбоях подозревайте его первым."))
    private val engine = "Двигатель (10)"
    private val ff = FreezeFrame("P0401", listOf(
        Pids.byId[0x0C]!! to 3750.0, Pids.byId[0x05]!! to 87.0, Pids.byId[0x0B]!! to 232.0, Pids.byId[0x0D]!! to 96.0))
    private val readiness = Readiness(true, 2, true, listOf(
        Monitor("Пропуски воспламенения", MonitorState.COMPLETE),
        Monitor("Топливная система", MonitorState.COMPLETE),
        Monitor("Компоненты (общий контроль)", MonitorState.COMPLETE),
        Monitor("Катализатор NMHC", MonitorState.COMPLETE),
        Monitor("Давление наддува", MonitorState.COMPLETE),
        Monitor("Рециркуляция ОГ (EGR) / фазы", MonitorState.INCOMPLETE),
    ))
    private val report = DtcReport(
        1_759_603_440_000,
        stored = listOf(Dtc("P0401", engine, DtcKind.STORED), Dtc("P1557", engine, DtcKind.STORED)),
        pending = listOf(Dtc("P0234", engine, DtcKind.PENDING)),
        permanent = emptyList(), readiness = readiness, freezeFrame = ff,
        notes = listOf("Постоянные ошибки (сервис 0A) есть только на машинах с CAN — пропущено"),
    )

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) =
        paparazzi.snapshot(name + if (dark) "_dark" else "_light") { AppTheme(dark = dark) { content() } }

    private fun shell(tab: Tab, errors: Int = 2, content: @Composable () -> Unit) = @Composable {
        AppScaffold(tab.title, conn, tab, errors, showTabs = true, showBack = false,
            onBack = {}, onTab = {}, onLog = {}, onHistory = {}, onDisconnect = {}) { content() }
    }

    @Test fun errors() = listOf(true, false).forEach { dark ->
        shot("01_errors", dark, shell(Tab.ERRORS) {
            ErrorsContent(DtcState(report = report), conn.warnings, db::info, {}, {}, {}, {})
        })
    }

    @Test fun errorsNone() = shot("02_errors_none", true, shell(Tab.ERRORS, 0) {
        ErrorsContent(DtcState(report = report.copy(stored = emptyList(), pending = emptyList(),
            readiness = readiness.copy(milOn = false, dtcCount = 0), freezeFrame = null, notes = emptyList())),
            emptyList(), db::info, {}, {}, {}, {})
    })

    @Test fun live() = listOf(true, false).forEach { dark ->
        val defs = listOf(0x0C, 0x0D, 0x05, 0x0B, 0x10, 0x04, 0x0F, 0x49).map { Pids.byId[it]!! }
        val vals = mapOf(0x0C to 1840.0, 0x0D to 62.0, 0x05 to 88.0, 0x0B to 164.0, 0x10 to 21.4, 0x04 to 37.0, 0x0F to 31.0, 0x49 to 22.0)
        val st = LiveState(
            supported = defs,
            values = vals.mapValues { (_, v) ->
                LiveValue(v, v * 0.8, v * 1.2, List(40) { i -> (v * (1 + 0.15 * kotlin.math.sin(i / 4.0))).toFloat() })
            },
            favorites = setOf(0x0C, 0x0B, 0x05),
            cycleMs = 1240,
        )
        shot("03_live", dark, shell(Tab.LIVE) { LiveContent(st, {}, {}, {}) })
    }

    @Test fun readiness() = shot("04_readiness", true, shell(Tab.READY) {
        ReadinessContent(readiness, listOf(Pids.byId[0x31]!! to 800.0, Pids.byId[0x4E]!! to 1260.0, Pids.byId[0x30]!! to 14.0),
            false) {}
    })

    @Test fun vehicle() = shot("05_vehicle", false, shell(Tab.CAR) {
        VehicleContent(VehicleInfo(null, emptyList(), null, "EOBD (Европа)", conn.protocol, conn.elmVersion, "12.4V"),
            conn, false) {}
    })

    @Test fun connect() = listOf(true, false).forEach { dark ->
        shot("06_connect_failed", dark, @Composable {
            AppScaffold("OBD Сканер", null, Tab.ERRORS, 0, false, false, {}, {}, {}, {}, {}) {
                ConnectContent(
                    ConnState.Failed("Адаптер работает, но машина не ответила.", FailStage.CAR,
                        listOf("Автопоиск: нет данных", "ISO 14230-4 KWP2000: BUS INIT ERROR"), 12.4),
                    BtAccess.OK,
                    listOf(PairedDevice("OBDII", "00:1D:A5:68:98:8B"), PairedDevice("JBL Flip 5", "F8:DF:15:A1:22:30")),
                    "00:1D:A5:68:98:8B", true, {}, {}, {}, {}, {}, {},
                )
            }
        })
    }

    @Test fun connecting() = shot("07_connecting", true, @Composable {
        AppScaffold("OBD Сканер", null, Tab.ERRORS, 0, false, false, {}, {}, {}, {}, {}) {
            ConnectContent(ConnState.Connecting("Пробую протокол: ISO 9141-2 (K-line)…"), BtAccess.OK,
                listOf(PairedDevice("OBDII", "00:1D:A5:68:98:8B")), "00:1D:A5:68:98:8B", true, {}, {}, {}, {}, {}, {})
        }
    })
}
