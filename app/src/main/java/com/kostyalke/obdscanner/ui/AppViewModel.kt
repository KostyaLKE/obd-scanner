package com.kostyalke.obdscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kostyalke.obdscanner.data.HistoryStore
import com.kostyalke.obdscanner.data.SavedReport
import com.kostyalke.obdscanner.elm.BluetoothTransport
import com.kostyalke.obdscanner.elm.DemoTransport
import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ElmTimeoutException
import com.kostyalke.obdscanner.elm.LogLine
import com.kostyalke.obdscanner.elm.ObdTransport
import com.kostyalke.obdscanner.obd.DtcDatabase
import com.kostyalke.obdscanner.obd.DtcReport
import com.kostyalke.obdscanner.obd.ObdService
import com.kostyalke.obdscanner.obd.PidDef
import com.kostyalke.obdscanner.obd.Pids
import com.kostyalke.obdscanner.obd.Readiness
import com.kostyalke.obdscanner.obd.VehicleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val step: String) : ConnState
    data class Connected(val deviceName: String, val protocol: String, val elmVersion: String) : ConnState
    data class Failed(val message: String) : ConnState
}

data class PairedDevice(val name: String, val address: String)

data class LiveValue(val value: Double, val min: Double, val max: Double, val history: List<Float>)

data class LiveState(
    val supported: List<PidDef> = emptyList(),
    val values: Map<Int, LiveValue> = emptyMap(),
    val favorites: Set<Int> = emptySet(),
    val onlyFavorites: Boolean = false,
    val cycleMs: Long = 0,
    val loading: Boolean = false,
)

data class DtcState(
    val report: DtcReport? = null,
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val db: DtcDatabase = DtcDatabase.load(app)
    private val history = HistoryStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _dtc = MutableStateFlow(DtcState())
    val dtc: StateFlow<DtcState> = _dtc.asStateFlow()

    private val _live = MutableStateFlow(
        LiveState(
            favorites = prefs.getStringSet("favorites", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet(),
            onlyFavorites = prefs.getBoolean("onlyFavorites", false),
        )
    )
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _readiness = MutableStateFlow<Readiness?>(null)
    val readiness: StateFlow<Readiness?> = _readiness.asStateFlow()

    private val _vehicle = MutableStateFlow<VehicleInfo?>(null)
    val vehicle: StateFlow<VehicleInfo?> = _vehicle.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _reports = MutableStateFlow<List<SavedReport>>(emptyList())
    val reports: StateFlow<List<SavedReport>> = _reports.asStateFlow()

    val lastDevice: String? get() = prefs.getString("lastDevice", null)

    private var transport: ObdTransport? = null
    private var service: ObdService? = null
    private var liveJob: Job? = null

    init {
        refreshReports()
    }

    // ---------- Подключение ----------

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { PairedDevice(it.name ?: it.address, it.address) }
                .sortedByDescending { d -> OBD_NAMES.any { d.name.contains(it, ignoreCase = true) } }
        }.getOrDefault(emptyList())
    }

    fun isBluetoothOn(): Boolean =
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun connect(address: String) {
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            _conn.value = ConnState.Failed("На телефоне нет Bluetooth")
            return
        }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        prefs.edit().putString("lastDevice", address).apply()
        start(BluetoothTransport(adapter, device))
    }

    fun connectDemo() = start(DemoTransport())

    private fun start(t: ObdTransport) {
        disconnect()
        _conn.value = ConnState.Connecting("Подключение к «${t.name}»…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { t.open() }
                transport = t
                val elm = Elm327(t) { line -> appendLog(line) }
                elm.initialize { step -> _conn.value = ConnState.Connecting(step) }
                val s = ObdService(elm)
                service = s
                _conn.value = ConnState.Connected(t.name, elm.protocol.title, elm.version)
                _live.update { it.copy(supported = emptyList(), values = emptyMap()) }
                _vehicle.value = null
                runCatching { loadSupported() }
                _live.update { it.copy(loading = false) }
                readDtcs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                t.close()
                transport = null
                service = null
                _conn.value = ConnState.Failed(humanError(e))
            }
        }
    }

    fun disconnect() {
        stopLive()
        transport?.close()
        transport = null
        service = null
        _conn.value = ConnState.Disconnected
    }

    private fun appendLog(line: LogLine) {
        _log.update { (it + line).takeLast(600) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun humanError(e: Throwable): String = when (e) {
        is ElmTimeoutException -> "Адаптер не отвечает. Проверьте, что он вставлен в разъём OBD и зажигание включено."
        is IOException -> e.message ?: "Связь с адаптером потеряна"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Выполняет операцию с машиной; при потере связи переводит в состояние ошибки. */
    private suspend fun <T> withService(block: suspend (ObdService) -> T): T? {
        val s = service ?: return null
        return try {
            block(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            transport?.close()
            transport = null
            service = null
            _conn.value = ConnState.Failed("Связь с адаптером потеряна: ${e.message ?: ""}".trim())
            null
        }
    }

    // ---------- Ошибки ----------

    fun readDtcs() {
        if (_dtc.value.loading) return
        viewModelScope.launch {
            _dtc.update { it.copy(loading = true, error = null, message = null) }
            try {
                val r = withService { it.readDtcs() }
                if (r != null) {
                    _dtc.update { it.copy(report = r) }
                    _readiness.value = r.readiness
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _dtc.update { it.copy(error = humanError(e)) }
            } finally {
                _dtc.update { it.copy(loading = false) }
            }
        }
    }

    fun clearDtcs() {
        viewModelScope.launch {
            _dtc.update { it.copy(clearing = true, error = null, message = null) }
            try {
                val ok = withService { it.clearDtcs(); true }
                if (ok == true) {
                    _dtc.update { it.copy(message = "Ошибки стёрты. Повторное чтение…") }
                    delay(1500)
                    _dtc.update { it.copy(clearing = false) }
                    readDtcs()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _dtc.update { it.copy(error = "Не удалось стереть: ${humanError(e)}") }
            } finally {
                _dtc.update { it.copy(clearing = false) }
            }
        }
    }

    fun buildReportText(): String? {
        val r = _dtc.value.report ?: return null
        return ReportFormatter.format(r, _vehicle.value, (_conn.value as? ConnState.Connected), db)
    }

    fun saveReport(): Boolean {
        val r = _dtc.value.report ?: return false
        val text = buildReportText() ?: return false
        history.save(text, r.time)
        refreshReports()
        _dtc.update { it.copy(message = "Отчёт сохранён в истории") }
        return true
    }

    fun refreshReports() {
        _reports.value = runCatching { history.list() }.getOrDefault(emptyList())
    }

    fun deleteReport(r: SavedReport) {
        history.delete(r)
        refreshReports()
    }

    // ---------- Готовность и информация ----------

    private val _sinceCleared = MutableStateFlow<List<Pair<PidDef, Double>>>(emptyList())
    val sinceCleared: StateFlow<List<Pair<PidDef, Double>>> = _sinceCleared.asStateFlow()

    fun readReadiness() = runOp {
        withService { it.readiness() }?.let { _readiness.value = it }
        val result = ArrayList<Pair<PidDef, Double>>()
        for (pid in SINCE_CLEARED) {
            val def = Pids.byId[pid] ?: continue
            val v = runCatching { withService { it.readPid(def) } }.getOrNull() ?: continue
            result += def to v
        }
        _sinceCleared.value = result
    }

    fun readVehicleInfo() = runOp {
        withService { it.vehicleInfo() }?.let { _vehicle.value = it }
    }

    private fun runOp(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog(LogLine(System.currentTimeMillis(), false, "Ошибка: ${humanError(e)}"))
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------- Текущие данные ----------

    private suspend fun loadSupported() {
        _live.update { it.copy(loading = true) }
        val ids = withService { it.supportedPids(1) } ?: emptySet()
        _live.update { st ->
            st.copy(supported = Pids.all.filter { it.pid in ids && it.pid in Pids.liveIds }, loading = false)
        }
    }

    fun startLive() {
        if (liveJob?.isActive == true || service == null) return
        liveJob = viewModelScope.launch {
            if (_live.value.supported.isEmpty()) loadSupported()
            while (isActive && service != null) {
                val st = _live.value
                val list = if (st.onlyFavorites && st.favorites.isNotEmpty()) {
                    st.supported.filter { it.pid in st.favorites }
                } else st.supported
                if (list.isEmpty()) {
                    delay(1000); continue
                }
                val t0 = System.currentTimeMillis()
                for (def in list) {
                    if (!isActive) break
                    val v = try {
                        withService { it.readPid(def) } ?: continue
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        continue
                    }
                    _live.update { s ->
                        val old = s.values[def.pid]
                        val nv = if (old == null) LiveValue(v, v, v, listOf(v.toFloat()))
                        else LiveValue(v, minOf(old.min, v), maxOf(old.max, v), (old.history + v.toFloat()).takeLast(60))
                        s.copy(values = s.values + (def.pid to nv))
                    }
                }
                _live.update { it.copy(cycleMs = System.currentTimeMillis() - t0) }
                delay(50)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    fun toggleFavorite(pid: Int) {
        _live.update { s ->
            val f = if (pid in s.favorites) s.favorites - pid else s.favorites + pid
            prefs.edit().putStringSet("favorites", f.map { it.toString() }.toSet()).apply()
            s.copy(favorites = f)
        }
    }

    fun setOnlyFavorites(v: Boolean) {
        prefs.edit().putBoolean("onlyFavorites", v).apply()
        _live.update { it.copy(onlyFavorites = v) }
    }

    fun resetMinMax() {
        _live.update { s -> s.copy(values = emptyMap()) }
    }

    // ---------- Терминал ----------

    fun sendRaw(cmd: String) = runOp {
        withService { it.elm.send(cmd.trim().uppercase(), 10000) }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    companion object {
        private val SINCE_CLEARED = listOf(0x31, 0x4E, 0x30, 0x21, 0x4D)
        private val OBD_NAMES = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VIECAR")
    }
}
