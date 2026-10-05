package com.kostyalke.obdscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kostyalke.obdscanner.data.HistoryStore
import com.kostyalke.obdscanner.data.SavedReport
import com.kostyalke.obdscanner.elm.BluetoothTransport
import com.kostyalke.obdscanner.elm.ConnectProblem
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
import com.kostyalke.obdscanner.elm.ObdProtocol
import com.kostyalke.obdscanner.vag.Tp20
import com.kostyalke.obdscanner.vag.VagException
import com.kostyalke.obdscanner.vag.VagModule
import com.kostyalke.obdscanner.vag.VagModuleResult
import com.kostyalke.obdscanner.vag.VagModules
import com.kostyalke.obdscanner.update.ReleaseInfo
import com.kostyalke.obdscanner.update.Updater
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val rel: ReleaseInfo) : UpdateState
    data class Downloading(val rel: ReleaseInfo, val progress: Float) : UpdateState
    /** Скачано, но установка ждёт: нужно разрешение или идёт диагностика. */
    data class Ready(val rel: ReleaseInfo, val needPermission: Boolean) : UpdateState
    data class Installing(val rel: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** На каком этапе сломалось подключение — от этого зависят советы пользователю. */
enum class FailStage { BLUETOOTH, ADAPTER, CAR, LOST }

sealed interface ConnState {
    data object Disconnected : ConnState
    data class Connecting(val step: String) : ConnState
    data class Connected(
        val deviceName: String,
        val protocol: String,
        val elmVersion: String,
        val voltage: Double? = null,
        val warnings: List<String> = emptyList(),
    ) : ConnState
    data class Failed(
        val message: String,
        val stage: FailStage? = null,
        val tried: List<String> = emptyList(),
        val voltage: Double? = null,
    ) : ConnState
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

/** Опрос всех блоков VAG (TP2.0). */
data class VagState(
    val scanning: Boolean = false,
    val current: String? = null,
    val progress: Float = 0f,
    val results: List<VagModuleResult> = emptyList(),
    val time: Long? = null,
    val error: String? = null,
    val clearing: Int? = null,
    val backingUp: Boolean = false,
    /** Текст последней сохранённой копии (JSON) — для «Поделиться». */
    val backupJson: String? = null,
    val backupInfo: String? = null,
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

    var autoConnect: Boolean
        get() = prefs.getBoolean("autoConnect", true)
        set(v) = prefs.edit().putBoolean("autoConnect", v).apply()

    private sealed interface Target {
        val key: String
        data class Bt(val address: String) : Target { override val key get() = address }
        data object Demo : Target { override val key get() = "demo" }
    }

    private var target: Target? = null
    private var transport: ObdTransport? = null
    private var service: ObdService? = null
    private var connectJob: Job? = null
    private var liveJob: Job? = null
    private var liveWanted = false
    private var consecutiveTimeouts = 0
    private var autoConnectTried = false

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateFile: File? = null

    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) = prefs.edit().putBoolean("autoUpdate", v).apply()

    val versionName: String = runCatching { Updater.currentVersionName(app) }.getOrDefault("?")

    init {
        refreshReports()
        checkForUpdate(manual = false)
        viewModelScope.launch {
            Updater.installResult.collect { msg -> if (msg != null) _update.value = UpdateState.Failed(msg) }
        }
    }

    // ---------- Обновление ----------

    /**
     * Проверка новой сборки на GitHub. В авто-режиме скачивает сразу, а ставит только когда
     * нет связи с машиной: установка перезапускает приложение и оборвала бы диагностику.
     */
    fun checkForUpdate(manual: Boolean) {
        val st = _update.value
        if (st is UpdateState.Checking || st is UpdateState.Downloading || st is UpdateState.Installing) return
        viewModelScope.launch {
            if (manual) _update.value = UpdateState.Checking
            try {
                val rel = Updater.fetchLatest()
                val current = Updater.currentVersionCode(getApplication())
                if (rel.versionCode > current) {
                    _update.value = UpdateState.Available(rel)
                    if (autoUpdate) startUpdate()
                } else {
                    _update.value = if (manual) UpdateState.UpToDate(versionName) else UpdateState.Idle
                    if (manual) {
                        delay(3000)
                        if (_update.value is UpdateState.UpToDate) _update.value = UpdateState.Idle
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Без интернета в гараже — обычное дело: молчим, если проверка была автоматической.
                _update.value = if (manual) UpdateState.Failed("Не удалось проверить: ${e.message ?: "нет связи"}") else UpdateState.Idle
            }
        }
    }

    fun startUpdate() {
        val rel = when (val st = _update.value) {
            is UpdateState.Available -> st.rel
            is UpdateState.Ready -> st.rel.also { tryInstall(it); return }
            else -> return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(rel, 0f)
            try {
                updateFile = Updater.download(getApplication(), rel) { p ->
                    _update.value = UpdateState.Downloading(rel, p)
                }
                tryInstall(rel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "Не удалось скачать обновление")
            }
        }
    }

    private fun tryInstall(rel: ReleaseInfo) {
        val file = updateFile ?: run { _update.value = UpdateState.Available(rel); return }
        val ctx = getApplication<Application>()
        when {
            !Updater.canInstall(ctx) -> _update.value = UpdateState.Ready(rel, needPermission = true)
            _conn.value is ConnState.Connected || _conn.value is ConnState.Connecting ->
                _update.value = UpdateState.Ready(rel, needPermission = false)
            else -> {
                _update.value = UpdateState.Installing(rel)
                runCatching { Updater.install(ctx, file) }
                    .onFailure { _update.value = UpdateState.Failed("Не удалось запустить установку: ${it.message}") }
            }
        }
    }

    /** Кнопка «Установить» в баннере — человек сам решил, даже если машина подключена. */
    fun installNow() {
        val st = _update.value as? UpdateState.Ready ?: return
        if (_conn.value is ConnState.Connected) disconnect()
        tryInstall(st.rel)
    }

    /** Вернулись из настроек «Установка неизвестных приложений». */
    fun onResume() {
        val st = _update.value
        if (st is UpdateState.Ready && st.needPermission && Updater.canInstall(getApplication())) {
            if (_conn.value !is ConnState.Connected) tryInstall(st.rel)
            else _update.value = st.copy(needPermission = false)
        }
        if (st is UpdateState.Installing) {
            // Система вернула нас без обновления (например, нажали «Отмена»).
            _update.value = UpdateState.Ready(st.rel, needPermission = false)
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }

    // ---------- Подключение ----------

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList()
        val list = runCatching {
            adapter.bondedDevices.map { PairedDevice(it.name ?: it.address, it.address) }
                .sortedByDescending { d -> OBD_NAMES.any { d.name.contains(it, ignoreCase = true) } }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) {
            prefs.edit().putStringSet("knownDevices", list.map { it.address + "|" + it.name }.toSet()).apply()
        }
        return list
    }

    /** Последний известный список — показываем его, пока Bluetooth выключен. */
    fun knownDevices(): List<PairedDevice> =
        (prefs.getStringSet("knownDevices", emptySet()) ?: emptySet()).mapNotNull {
            val i = it.indexOf('|')
            if (i <= 0) null else PairedDevice(it.substring(i + 1), it.substring(0, i))
        }.sortedBy { it.name }

    fun isBluetoothOn(): Boolean =
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Один раз за запуск приложения подключается к последнему адаптеру. */
    fun autoConnectIfNeeded(paired: List<PairedDevice>) {
        if (autoConnectTried || !autoConnect || _conn.value != ConnState.Disconnected) return
        val last = lastDevice ?: return
        if (paired.none { it.address == last }) return
        autoConnectTried = true
        connect(last)
    }

    fun connect(address: String) {
        autoConnectTried = true
        prefs.edit().putString("lastDevice", address).apply()
        target = Target.Bt(address)
        start(reconnect = false)
    }

    fun connectDemo() {
        autoConnectTried = true
        target = Target.Demo
        start(reconnect = false)
    }

    private fun makeTransport(t: Target): ObdTransport? = when (t) {
        Target.Demo -> DemoTransport()
        is Target.Bt -> {
            val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) null else BluetoothTransport(adapter, adapter.getRemoteDevice(t.address))
        }
    }

    /**
     * Подключение. При [reconnect] = true (связь оборвалась) делает до трёх попыток
     * и сохраняет уже прочитанные данные на экране.
     */
    private fun start(reconnect: Boolean) {
        val tg = target ?: return
        pauseLive()
        closeTransport()
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val attempts = if (reconnect) 3 else 1
            var lastError: Exception? = null
            var voltage: Double? = null
            for (attempt in 1..attempts) {
                val t = makeTransport(tg)
                if (t == null) {
                    _conn.value = ConnState.Failed("На телефоне нет Bluetooth", FailStage.BLUETOOTH)
                    return@launch
                }
                val prefix = if (reconnect) "Связь потеряна, переподключаюсь ($attempt из $attempts). " else ""
                _conn.value = ConnState.Connecting(prefix + "Подключение к «${t.name}»…")
                var stage = FailStage.BLUETOOTH
                try {
                    t.open()
                    transport = t
                    stage = FailStage.ADAPTER
                    val elm = Elm327(t) { line -> appendLog(line) }
                    val protoKey = "proto_" + tg.key
                    elm.initialize(prefs.getString(protoKey, null)?.firstOrNull()) { step ->
                        voltage = elm.voltage
                        _conn.value = ConnState.Connecting(prefix + step)
                    }
                    prefs.edit().putString(protoKey, elm.protocol.code.toString()).apply()
                    service = ObdService(elm)
                    consecutiveTimeouts = 0
                    _conn.value = ConnState.Connected(t.name, elm.protocol.title, elm.version, elm.voltage, warningsFor(elm))
                    if (reconnect) {
                        if (liveWanted) launchLive()
                    } else {
                        _live.update { it.copy(supported = emptyList(), values = emptyMap()) }
                        _vehicle.value = null
                        _readiness.value = null
                        _dtc.value = DtcState()
                        _vag.value = VagState()
                        runCatching { loadSupported() }
                        _live.update { it.copy(loading = false) }
                        if (liveWanted) launchLive()
                        readDtcs()
                    }
                    return@launch
                } catch (e: CancellationException) {
                    t.close()
                    throw e
                } catch (e: Exception) {
                    t.close()
                    transport = null
                    service = null
                    lastError = e
                    if (e is ConnectProblem) stage = if (e.stage == ConnectProblem.Stage.CAR) FailStage.CAR else FailStage.ADAPTER
                    if (e is ElmTimeoutException) stage = FailStage.ADAPTER
                    if (attempt == attempts) {
                        _conn.value = ConnState.Failed(
                            humanError(e),
                            if (reconnect && stage == FailStage.BLUETOOTH) FailStage.LOST else stage,
                            (e as? ConnectProblem)?.tried ?: emptyList(),
                            voltage,
                        )
                    } else {
                        delay(2000)
                    }
                }
            }
        }
    }

    private fun warningsFor(elm: Elm327): List<String> = buildList {
        if (elm.version.contains("2.1")) {
            add("Адаптер v2.1 — скорее всего урезанный клон. При сбоях подозревайте его первым.")
        }
        elm.voltage?.let { v ->
            if (v < 11.8) add("Низкое напряжение: %.1f В. Слабый аккумулятор даёт ложные ошибки и обрывы связи.".format(Ru, v))
        }
    }

    fun cancelConnect() = disconnect()

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        pauseLive()
        closeTransport()
        _conn.value = ConnState.Disconnected
    }

    private fun closeTransport() {
        transport?.close()
        transport = null
        service = null
    }

    private fun appendLog(line: LogLine) {
        _log.update { (it + line).takeLast(600) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun humanError(e: Throwable): String = when (e) {
        is ElmTimeoutException -> "Адаптер перестал отвечать."
        is IOException -> e.message ?: "Связь с адаптером потеряна"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Связь оборвалась посреди работы — тихо переподключаемся. */
    private fun onConnectionLost() {
        if (_conn.value !is ConnState.Connected) return
        appendLog(LogLine(System.currentTimeMillis(), false, "⚠ связь потеряна, переподключение"))
        start(reconnect = true)
    }

    /**
     * Выполняет операцию с машиной. Обрыв Bluetooth или три таймаута подряд
     * (клон «завис») запускают автоматическое переподключение.
     */
    private suspend fun <T> withService(block: suspend (ObdService) -> T): T? {
        val s = service ?: return null
        return try {
            block(s).also { consecutiveTimeouts = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            onConnectionLost()
            null
        } catch (e: ElmTimeoutException) {
            if (++consecutiveTimeouts >= 3) {
                onConnectionLost()
                null
            } else throw e
        }
    }

    // ---------- Все блоки VAG ----------

    private val _vag = MutableStateFlow(VagState())
    val vag: StateFlow<VagState> = _vag.asStateFlow()

    /** TP2.0 работает только на CAN 11 бит / 500 кбит/с (VAG примерно с 2008 года). */
    fun vagSupported(): Boolean = service?.elm?.protocol == ObdProtocol.CAN_11_500

    fun scanVag() {
        if (_vag.value.scanning || !vagSupported()) return
        stopLive()
        viewModelScope.launch {
            _vag.value = VagState(scanning = true)
            try {
                withService { s ->
                    s.elm.exclusive { send ->
                        val tp = Tp20(send)
                        try {
                            tp.enterRawMode()
                            val list = VagModules.scanList
                            list.forEachIndexed { i, m ->
                                _vag.update { it.copy(current = m.title, progress = i.toFloat() / list.size) }
                                val r = Tp20.scanModule(tp, m)
                                _vag.update { it.copy(results = it.results + r) }
                            }
                        } finally {
                            tp.restore()
                        }
                    }
                }
                _vag.update { it.copy(time = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VagException) {
                _vag.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _vag.update { it.copy(error = humanError(e)) }
            } finally {
                _vag.update { it.copy(scanning = false, current = null, progress = 1f) }
            }
        }
    }

    private val backups by lazy { com.kostyalke.obdscanner.data.BackupStore(getApplication()) }

    /**
     * Резервная копия служебных данных всех ответивших блоков (только чтение).
     * Нужна до любого кодирования: из неё можно вернуть всё как было.
     */
    fun backupVag() {
        val st = _vag.value
        if (st.scanning || st.backingUp || !vagSupported()) return
        val targets = st.results.filter { it.responded }.map { it.module }.ifEmpty { VagModules.scanList }
        viewModelScope.launch {
            _vag.update { it.copy(backingUp = true, error = null, backupInfo = null) }
            try {
                val list = withService { s ->
                    s.elm.exclusive { send ->
                        val tp = Tp20(send)
                        try {
                            tp.enterRawMode()
                            targets.mapNotNull { m ->
                                _vag.update { it.copy(current = m.title) }
                                runCatching { Tp20.backupModule(tp, m) }.getOrNull()
                            }
                        } finally {
                            tp.restore()
                        }
                    }
                } ?: return@launch
                if (list.isEmpty()) throw VagException("Ни один блок не отдал данные")
                val time = System.currentTimeMillis()
                val vin = _vehicle.value?.vin
                val file = backups.save(list, vin, time)
                _vag.update {
                    it.copy(
                        backupJson = file.readText(),
                        backupInfo = "Копия сохранена: ${list.size} ${plural(list.size, "блок", "блока", "блоков")}, " +
                            "${list.sumOf { b -> b.records.size }} записей",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _vag.update { it.copy(error = "Копия не сохранена: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _vag.update { it.copy(backingUp = false, current = null) }
            }
        }
    }

    fun clearVag(m: VagModule) {
        if (_vag.value.scanning || _vag.value.clearing != null) return
        viewModelScope.launch {
            _vag.update { it.copy(clearing = m.address, error = null) }
            try {
                val fresh = withService { s ->
                    s.elm.exclusive { send ->
                        val tp = Tp20(send)
                        try {
                            tp.enterRawMode()
                            if (!tp.open(m)) throw VagException("Блок «${m.title}» не ответил")
                            try {
                                tp.startSession()
                                tp.clearDtcs()
                            } finally {
                                tp.close()
                            }
                            Tp20.scanModule(tp, m)
                        } finally {
                            tp.restore()
                        }
                    }
                }
                if (fresh != null) _vag.update { st ->
                    st.copy(results = st.results.map { if (it.module.address == m.address) fresh else it })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _vag.update { it.copy(error = "Не удалось сбросить: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _vag.update { it.copy(clearing = null) }
            }
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
                    autoSave()
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
        return ReportFormatter.format(r, _vehicle.value, (_conn.value as? ConnState.Connected), db, _vag.value.results)
    }

    /** Каждое чтение ошибок попадает в историю — форумчане жалуются, что «забыл сохранить». */
    private fun autoSave() {
        val r = _dtc.value.report ?: return
        val text = buildReportText() ?: return
        runCatching { history.save(text, r.time) }
        refreshReports()
    }

    fun shareLog(): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        return _log.value.joinToString("\n") { "${fmt.format(java.util.Date(it.time))} ${if (it.outgoing) ">>" else "<<"} ${it.text}" }
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
        liveWanted = true
        launchLive()
    }

    private fun launchLive() {
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
        liveWanted = false
        pauseLive()
    }

    private fun pauseLive() {
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
