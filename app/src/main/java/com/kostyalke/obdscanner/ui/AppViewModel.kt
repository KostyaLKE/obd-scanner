package com.kostyalke.obdscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kostyalke.obdscanner.data.HistoryStore
import com.kostyalke.obdscanner.data.ReportMeta
import com.kostyalke.obdscanner.data.SavedReport
import com.kostyalke.obdscanner.obd.OutcomeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kostyalke.obdscanner.elm.BluetoothTransport
import com.kostyalke.obdscanner.elm.ConnectProblem
import com.kostyalke.obdscanner.elm.DemoTransport
import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ElmTimeoutException
import com.kostyalke.obdscanner.elm.LogLine
import com.kostyalke.obdscanner.elm.ObdTransport
import com.kostyalke.obdscanner.obd.DtcDatabase
import com.kostyalke.obdscanner.obd.DtcReport
import com.kostyalke.obdscanner.obd.AfterRead
import com.kostyalke.obdscanner.obd.ClearFlow
import com.kostyalke.obdscanner.obd.ClearOutcome
import com.kostyalke.obdscanner.obd.ClearResult
import com.kostyalke.obdscanner.obd.ClearStatus
import com.kostyalke.obdscanner.obd.ControlRead
import com.kostyalke.obdscanner.vag.VagErrorKind
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
import com.kostyalke.obdscanner.update.UpdateJobService
import com.kostyalke.obdscanner.update.Updater
import java.io.File
import com.kostyalke.obdscanner.elm.catching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Текст последнего снимка данных блоков (JSON) — для «Поделиться». */
    val backupJson: String? = null,
    val backupInfo: String? = null,
)

data class DtcState(
    val report: DtcReport? = null,
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** Последнее чтение не удалось или связь обрывалась: прежний результат нельзя выдавать за свежий. */
    val stale: Boolean = false,
    /** Номер подключения, в котором получен [report]. */
    val session: Int = 0,
    /** Подготовленный сброс: свежее чтение есть, отчёт «до» сохранён (или нет) — ждём подтверждения. */
    val clearPrep: ClearPrep? = null,
    /** Фактический итог последнего сброса (по контрольному чтению). */
    val outcome: ClearOutcome? = null,
    val warning: String? = null,
)

/** Сброс ждёт подтверждения. [module] = null — ошибки OBD, иначе — блок VAG. */
data class ClearPrep(
    val session: Int,
    val module: VagModule?,
    val title: String,
    val lines: List<String>,
    /** Идентификатор отчёта «до сброса» в истории; null — сохранить не удалось. */
    val beforeName: String?,
    /** Коды до сброса — для сравнения «исчезли / остались / появились». */
    val beforeCodes: List<String> = emptyList(),
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

    /**
     * Задачи текущего подключения (операции и опрос датчиков). Отменяются все разом
     * при отключении и переподключении — поздний результат старой связи не попадёт на экран.
     */
    private var sessionScope: CoroutineScope? = null

    /** Одна диагностическая операция за раз: чтение, сброс, VAG, снимок, ручные команды. */
    private val opLock = Mutex()

    /**
     * Шина адаптера: операция держит её целиком, опрос датчиков берёт на каждый запрос.
     * Так датчики не вклиниваются между командами операции, а операция ждёт не дольше одного PID.
     */
    private val busLock = Mutex()

    /** Запускает операцию в задачах подключения. false — нет связи или идёт другая операция. */
    private fun launchOp(block: suspend CoroutineScope.() -> Unit): Boolean {
        val scope = sessionScope ?: return false
        if (!opLock.tryLock()) return false
        val sid = session
        scope.launch {
            adapterDirty = false
            val op = this
            try {
                busLock.withLock { op.block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Страховка: ни одна операция не должна уронить приложение необработанной ошибкой.
                log("Ошибка операции: ${humanError(e)}")
                _dtc.update { it.copy(warning = "Операция не выполнена: ${humanError(e)}") }
            } finally {
                // ELM не вернулся к OBD — переинициализация и при ошибке операции,
                // но только если это всё ещё то же подключение.
                if (adapterDirty && sid == session && service != null) reinitAdapter() else adapterDirty = false
            }
        }.invokeOnCompletion { opLock.unlock() }
        return true
    }

    /** Признак текущей операции VAG: настройки ELM не вернулись. */
    private var adapterDirty = false

    /** ELM не вернулся к OBD после опроса блоков — инициализируем адаптер заново. */
    private fun reinitAdapter() {
        adapterDirty = false
        log("⚠ адаптер не вернулся к обычному OBD после опроса блоков — переподключение")
        start(reconnect = true)
    }
    private var liveWanted = false
    private var consecutiveTimeouts = 0
    private var autoConnectTried = false

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateFile: File? = null

    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) {
            prefs.edit().putBoolean("autoUpdate", v).apply()
            UpdateJobService.schedule(getApplication(), v)
        }

    val versionName: String = runCatching { Updater.currentVersionName(app) }.getOrDefault("?")

    /** Идёт подключение, связь с машиной или операция — обновление в это время не ставится. */
    private fun diagnosticsActive(): Boolean =
        _conn.value is ConnState.Connected || _conn.value is ConnState.Connecting || opLock.isLocked

    init {
        Updater.isBusy = ::diagnosticsActive
        UpdateJobService.schedule(app, autoUpdate)
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
        // Автопроверка — не чаще раза в час: без интернета не долбим сеть при каждом запуске.
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastUpdateCheck", 0) < 60 * 60 * 1000L && st !is UpdateState.Ready) return
        prefs.edit().putLong("lastUpdateCheck", now).apply()
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
            // Диагностику ради обновления не обрываем: поставим после отключения.
            diagnosticsActive() -> _update.value = UpdateState.Ready(rel, needPermission = false)
            else -> {
                _update.value = UpdateState.Installing(rel)
                viewModelScope.launch {
                    try {
                        Updater.install(ctx, file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _update.value = UpdateState.Failed("Не удалось запустить установку: ${e.message}")
                    }
                }
            }
        }
    }

    /** Кнопка «Установить» в баннере — человек сам решил, даже если машина подключена. */
    fun installNow() {
        val st = _update.value as? UpdateState.Ready ?: return
        // Идёт чтение, сброс или опрос блоков — не прерываем, даже по кнопке.
        if (opLock.isLocked) return
        if (_conn.value is ConnState.Connected || _conn.value is ConnState.Connecting) disconnect()
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
                    session++
                    sessionScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
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
                        catching { loadSupported() }
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
        // Диагностика закончена — ставим скачанное обновление, если оно ждало.
        (_update.value as? UpdateState.Ready)?.takeIf { autoUpdate && !it.needPermission }?.let { tryInstall(it.rel) }
    }

    private fun closeTransport() {
        sessionScope?.cancel()
        sessionScope = null
        transport?.close()
        transport = null
        service = null
    }

    private fun log(text: String) = appendLog(LogLine(System.currentTimeMillis(), false, text))

    private fun appendLog(line: LogLine) {
        _log.update { (it + line).takeLast(600) }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun humanError(e: Throwable): String = when (e) {
        is SecurityException ->
            "Android не дал приложению доступ к Bluetooth. Откройте Настройки → Приложения → OBD Сканер → " +
                "Разрешения и разрешите «Устройства поблизости»."
        is ElmTimeoutException -> "Адаптер перестал отвечать."
        is IOException -> e.message ?: "Связь с адаптером потеряна"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Связь оборвалась посреди работы — тихо переподключаемся. */
    private fun onConnectionLost() {
        markDtcStale()
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
        launchOp {
            _vag.value = VagState(scanning = true)
            try {
                withService { s ->
                    s.elm.exclusive { send ->
                        val scan = com.kostyalke.obdscanner.vag.VagScanner.scanAll(
                            send, s.elm.protocol.code, ::log, onRestoreFailed = { adapterDirty = true },
                        ) { title, p ->
                            _vag.update { it.copy(current = title, progress = p) }
                        }
                        _vag.update { it.copy(results = scan.results) }
                        if (!scan.adapterRestored) adapterDirty = true
                        val res = _vag.value.results
                        log("VAG: итог — " + res.joinToString(", ") {
                            "${it.module.addressText}:" + when {
                                !it.responded -> "нет"
                                it.error != null -> "ошибка"
                                else -> "${it.protocol}/${it.dtcs.size}"
                            }
                        })
                        if (res.none { it.responded }) {
                            _vag.update {
                                it.copy(error = "Ни один блок не ответил ни по TP2.0, ни по UDS. Причина может быть в адаптере " +
                                    "(дешёвые клоны часто не умеют «сырой» CAN: ATPB/ATSP B или ATCAF0+ATV1, фильтр ATCRA), " +
                                    "в выключенном зажигании или в самой машине. Точно видно по журналу обмена — пришлите его.")
                            }
                        }
                    }
                }
                _vag.update { it.copy(time = System.currentTimeMillis()) }
                autoSaveVag()
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
     * Снимок служебных данных всех ответивших блоков (только чтение): номера, версии, сырые записи.
     * Не проверенная резервная копия — восстановление по нему не подтверждено.
     */
    fun backupVag() {
        val st = _vag.value
        if (st.scanning || st.backingUp || !vagSupported()) return
        val responded = st.results.filter { it.responded }
        val targets = responded.filter { it.protocol != "UDS" && it.tpAddress != null }.map { it.module to it.tpAddress!! }
        val udsTargets = responded.filter { it.protocol == "UDS" }.map { it.module }
        launchOp {
            _vag.update { it.copy(backingUp = true, error = null, backupInfo = null) }
            try {
                val failed = ArrayList<Pair<VagModule, String>>()
                // Ошибки протокола одного блока — в список «не прочитан»; обрыв связи — наверх.
                suspend fun one(m: VagModule, read: suspend () -> com.kostyalke.obdscanner.vag.ModuleBackup?) =
                    try {
                        read().also { if (it == null) failed += m to "блок не ответил" }
                    } catch (e: VagException) {
                        failed += m to (e.message ?: e.kind.label); null
                    }
                val list = withService { s ->
                    s.elm.exclusive { send ->
                        val out = ArrayList<com.kostyalke.obdscanner.vag.ModuleBackup>()
                        if (targets.isNotEmpty()) {
                            val tp = Tp20(send, s.elm.protocol.code, ::log)
                            try {
                                tp.enterRawMode()
                                targets.forEach { (m, tpA) ->
                                    _vag.update { it.copy(current = m.title) }
                                    one(m) { Tp20.backupModule(tp, m, tpA) }?.let { out += it }
                                }
                            } catch (e: VagException) {
                                targets.filter { (m, _) -> out.none { it.module == m } && failed.none { it.first == m } }
                                    .forEach { (m, _) -> failed += m to (e.message ?: "TP2.0 недоступен") }
                            } finally {
                                if (!tp.restore()) adapterDirty = true
                            }
                        }
                        if (udsTargets.isNotEmpty()) {
                            val uds = com.kostyalke.obdscanner.vag.Uds(send)
                            try {
                                uds.begin()
                                udsTargets.forEach { m ->
                                    _vag.update { it.copy(current = m.title) }
                                    one(m) { com.kostyalke.obdscanner.vag.Uds.backupModule(uds, m) }?.let { out += it }
                                }
                            } finally {
                                if (!uds.restore()) adapterDirty = true
                            }
                        }
                        out
                    }
                } ?: return@launchOp
                // Блок ответил, но не отдал ни одной записи — это не успех.
                list.filter { !it.hasData }.forEach { failed += it.module to "ни одной записи не прочитано" }
                val withData = list.filter { it.hasData }
                if (withData.isEmpty()) throw VagException("ни один блок не отдал данные")
                val time = System.currentTimeMillis()
                val vin = _vehicle.value?.vin
                val json = withContext(Dispatchers.IO) { backups.save(withData, vin, time, versionName, failed).readText() }
                val fullCount = withData.count { it.complete }
                _vag.update {
                    it.copy(
                        backupJson = json,
                        backupInfo = "Снимок данных блоков сохранён: полностью — $fullCount, " +
                            "частично — ${withData.size - fullCount}, не прочитаны — ${failed.size}" +
                            (if (failed.isNotEmpty()) " (" + failed.joinToString(", ") { f -> f.first.title } + ")" else "") +
                            (if (_vehicle.value?.vin == null) ". VIN не прочитан" else "") +
                            ". Это снимок для идентификации, а не проверенная резервная копия.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _vag.update { it.copy(error = "Снимок не сохранён: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _vag.update { it.copy(backingUp = false, current = null) }
            }
        }
    }

    fun clearVag(m: VagModule) = prepareClear(m)

    /** Свежее чтение одного блока VAG (TP2.0 или UDS — как он ответил при опросе). */
    private suspend fun readVagModule(m: VagModule): VagModuleResult? {
        val prev = _vag.value.results.firstOrNull { it.module.address == m.address }
        val useUds = prev?.protocol == "UDS"
        val tpA = prev?.tpAddress ?: VagModules.tpFallback[m.address] ?: m.address
        val fresh = withService { s ->
            s.elm.exclusive { send ->
                if (useUds) {
                    val uds = com.kostyalke.obdscanner.vag.Uds(send)
                    try {
                        uds.begin()
                        com.kostyalke.obdscanner.vag.Uds.scanModule(uds, m)
                    } finally {
                        if (!uds.restore()) adapterDirty = true
                    }
                } else {
                    val tp = Tp20(send, s.elm.protocol.code, ::log)
                    try {
                        tp.enterRawMode()
                        Tp20.scanModule(tp, m, tpA)
                    } finally {
                        if (!tp.restore()) adapterDirty = true
                    }
                }
            }
        }
        if (fresh != null) _vag.update { st ->
            st.copy(results = st.results.map { if (it.module.address == m.address) fresh else it })
        }
        return fresh
    }

    /** Одна команда сброса блоку VAG. Обрыв/таймаут — результат неизвестен (без повтора). */
    private suspend fun clearVagModule(m: VagModule): ClearResult {
        val prev = _vag.value.results.firstOrNull { it.module.address == m.address }
        val useUds = prev?.protocol == "UDS"
        val tpA = prev?.tpAddress ?: VagModules.tpFallback[m.address] ?: m.address
        return try {
            withService { s ->
                s.elm.exclusive { send ->
                    if (useUds) {
                        val uds = com.kostyalke.obdscanner.vag.Uds(send)
                        try {
                            uds.begin()
                            val (tx, rx) = VagModules.udsIds.getValue(m.address)
                            uds.target(tx, rx)
                            uds.clearDtcs()
                            ClearResult(ClearStatus.CONFIRMED)
                        } finally {
                            if (!uds.restore()) adapterDirty = true
                        }
                    } else {
                        val tp = Tp20(send, s.elm.protocol.code, ::log)
                        try {
                            tp.enterRawMode()
                            if (!tp.open(tpA)) return@exclusive ClearResult(ClearStatus.NOT_CONFIRMED, "блок не ответил, команда не отправлена")
                            try {
                                tp.startSession()
                                tp.clearDtcs()
                                ClearResult(ClearStatus.CONFIRMED)
                            } finally {
                                tp.close()
                            }
                        } finally {
                            if (!tp.restore()) adapterDirty = true
                        }
                    }
                }
            } ?: ClearResult(ClearStatus.UNKNOWN, "связь с адаптером прервалась")
        } catch (e: CancellationException) {
            throw e
        } catch (e: VagException) {
            ClearResult(if (e.kind == VagErrorKind.REFUSED) ClearStatus.REFUSED else ClearStatus.NOT_CONFIRMED, e.message)
        } catch (e: Exception) {
            ClearResult(ClearStatus.UNKNOWN, humanError(e))
        }
    }

    // ---------- Ошибки ----------

    /** Номер текущего подключения; растёт при каждом успешном подключении, в т. ч. переподключении. */
    private var session = 0

    private fun markDtcStale() = _dtc.update { if (it.report != null) it.copy(stale = true) else it }

    fun readDtcs() {
        launchOp { doReadDtcs() }
    }

    /**
     * Чтение ошибок OBD. [save] = false — без автосохранения (в процессе сброса отчёты
     * «до» и «после» сохраняются отдельно и связываются). null — прочитать не удалось.
     */
    private suspend fun doReadDtcs(save: Boolean = true): DtcReport? {
        _dtc.update { it.copy(loading = true, error = null, message = null) }
        try {
            val sid = session
            val r = withService { it.readDtcs() }
            if (r != null && sid == session) {
                _dtc.update { it.copy(report = r, stale = false, session = sid) }
                _readiness.value = r.readiness
                if (save) autoSave()
                return r
            }
            // Нет связи, обрыв посреди чтения или результат от прежнего подключения.
            markDtcStale()
            _dtc.update { it.copy(error = "Связь с адаптером потеряна — ошибки не прочитаны") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _dtc.update { it.copy(error = humanError(e), stale = it.report != null) }
        } finally {
            _dtc.update { it.copy(loading = false) }
        }
        return null
    }

    fun clearDtcs() = prepareClear(null)

    /** Сохраняет отчёт; ошибка сохранения возвращается текстом, а не глотается. */
    /**
     * Сохраняет отчёт (не в главном потоке); ошибка сохранения возвращается текстом, а не глотается.
     * Историю обрезает только явная настройка [keepReports] (0 — хранить всё).
     */
    private suspend fun saveReport(text: String, time: Long, meta: ReportMeta): Pair<SavedReport?, String?> =
        withContext(Dispatchers.IO) {
            try {
                val saved = history.save(text, time, meta.copy(vin = meta.vin ?: _vehicle.value?.vin, demo = isDemo))
                history.prune(keepReports)
                saved to null
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            } finally {
                _reports.value = listReports()
            }
        }

    private fun listReports() = runCatching { history.list() }.getOrDefault(emptyList())

    private val isDemo: Boolean get() = target == Target.Demo

    /** Демо-данные помечаются в каждом отчёте: их нельзя принять за данные автомобиля. */
    private fun demoNote(): List<String> =
        if (isDemo) listOf("ДЕМО-РЕЖИМ: данные эмулятора, а не автомобиля.") else emptyList()

    /** Сколько отчётов хранить; 0 — все (по умолчанию). Меняет только владелец в настройках. */
    var keepReports: Int
        get() = prefs.getInt("keepReports", 0)
        set(v) = prefs.edit().putInt("keepReports", v.coerceAtLeast(0)).apply()

    private fun metaOf(r: DtcReport, kind: String? = null, linkedTo: String? = null) =
        ReportMeta(kind, errors = r.all.size, complete = r.isComplete && r.mismatch == null, linkedTo = linkedTo)

    private fun metaOf(m: VagModuleResult, kind: String, linkedTo: String? = null) =
        ReportMeta(kind, errors = m.dtcs.size, complete = m.responded && m.error == null, linkedTo = linkedTo)

    private fun reportText(r: DtcReport, kind: String, extra: List<String> = emptyList()) =
        ReportFormatter.format(r, _vehicle.value, _conn.value as? ConnState.Connected, db, _vag.value.results, kind, demoNote() + extra)

    private fun vagReportText(m: VagModuleResult, time: Long, kind: String, extra: List<String> = emptyList()) =
        ReportFormatter.formatVagModule(m, time, _vehicle.value, _conn.value as? ConnState.Connected, db, kind, demoNote() + extra)

    /**
     * Шаги 1–3 сброса: актуальное чтение → отчёт «до сброса» в историю → подтверждение.
     * Неполнота данных и ошибка сохранения попадают в текст подтверждения.
     */
    private fun prepareClear(m: VagModule?) {
        if (_dtc.value.clearPrep != null) return
        launchOp {
            _dtc.update { it.copy(clearing = true, error = null, message = null, warning = null, outcome = null) }
            if (m != null) _vag.update { it.copy(clearing = m.address, error = null) }
            try {
                val sid = session
                val now = System.currentTimeMillis()
                val text: String
                val incomplete: List<String>
                val permanent: Int
                val codes: List<String>
                val meta: ReportMeta
                if (m == null) {
                    val r = doReadDtcs(save = false) ?: return@launchOp
                    meta = metaOf(r, "до сброса")
                    text = reportText(r, "до сброса")
                    incomplete = r.failed.map { it.text }
                    permanent = r.permanent.size
                    codes = obdCodes(r)
                } else {
                    val fresh = readVagModule(m)
                    if (fresh == null || !fresh.responded) {
                        _dtc.update { it.copy(warning = "Блок «${m.title}» сейчас не отвечает — сброс невозможен") }
                        return@launchOp
                    }
                    meta = metaOf(fresh, "до сброса · блок ${m.addressText}")
                    text = vagReportText(fresh, now, "до сброса")
                    incomplete = listOfNotNull(fresh.error?.let { "${m.title}: $it" })
                    permanent = 0
                    codes = vagCodes(fresh)
                }
                val (saved, saveError) = saveReport(text, now, meta)
                val scope = if (m != null) "Будут стёрты ошибки только блока «${m.title}»."
                else if (service?.clearIsBroadcast == true) "На этом протоколе команда уйдёт всем блокам OBD, а не только двигателю."
                else "Будут стёрты ошибки только блока двигателя; ошибки других блоков останутся."
                val lines = listOf(scope) + ClearFlow.confirmLines(incomplete, saved?.title, saveError, permanent) +
                    listOfNotNull(m?.let {
                        "Ошибки подушек безопасности с записью об аварии (crash data) так не сбрасываются."
                    })
                _dtc.update {
                    it.copy(clearPrep = ClearPrep(sid, m, if (m == null) "Сбросить ошибки двигателя?" else
                        "Сбросить ошибки блока «${m.title}»?", lines, saved?.id, codes))
                }
            } finally {
                _dtc.update { it.copy(clearing = false) }
                if (m != null) _vag.update { it.copy(clearing = null) }
            }
        }
    }

    fun cancelClear() = _dtc.update { it.copy(clearPrep = null) }

    private fun obdCodes(r: DtcReport) = r.all.map { "${it.code} (${it.ecu})" }.distinct()

    private fun vagCodes(m: VagModuleResult) =
        m.dtcs.map { if (it.uds) "%06X".format(it.code) else VagModules.format(it.code) }

    /**
     * Шаги 4–7: одна команда сброса → контрольное чтение → связанный отчёт «после» →
     * фактический итог. Подтверждение действует только в том подключении, где читали.
     */
    fun confirmClear() {
        val prep = _dtc.value.clearPrep ?: return
        _dtc.update { it.copy(clearPrep = null) }
        val stale = "Связь переподключалась после чтения — прочитайте ошибки заново и повторите сброс."
        if (prep.session != session || service == null) {
            _dtc.update { it.copy(warning = stale) }
            return
        }
        val started = launchOp {
            if (prep.session != session) {
                _dtc.update { it.copy(warning = stale) }
                return@launchOp
            }
            val m = prep.module
            _dtc.update { it.copy(clearing = true, error = null, message = null, warning = null, outcome = null) }
            if (m != null) _vag.update { it.copy(clearing = m.address, error = null) }
            try {
                // Весь ход — в ClearFlow.run: он сохраняет запись и публикует итог даже при
                // обрыве связи (переподключение отменяет эту операцию) и при любой ошибке.
                ClearFlow.run(
                    beforeCodes = prep.beforeCodes,
                    send = {
                        val cmd = if (m == null) {
                            withService { it.clearDtcs() } ?: ClearResult(ClearStatus.UNKNOWN, "связь с адаптером прервалась")
                        } else clearVagModule(m)
                        log("Сброс: ${cmd.text}")
                        cmd
                    },
                    settle = { delay(1500) },
                    read = {
                        if (session != prep.session) null
                        else if (m == null) doReadDtcs(save = false)?.let { r ->
                            AfterRead(
                                ControlRead(r.isComplete, r.stored.size + r.pending.size, r.permanent.size,
                                    r.failed.joinToString("; ") { f -> f.text }.ifEmpty { null }),
                                obdCodes(r), reportText(r, "после сброса"), metaOf(r, "после сброса", prep.beforeName),
                            )
                        } ?: AfterRead(ControlRead(false, 0, problem = _dtc.value.error ?: "связь потеряна"), null, "")
                        else readVagModule(m)?.let { f ->
                            AfterRead(ControlRead(f.responded && f.error == null, f.dtcs.size, problem = f.error),
                                vagCodes(f), vagReportText(f, System.currentTimeMillis(), "после сброса"),
                                metaOf(f, "после сброса · блок ${m.addressText}", prep.beforeName))
                        } ?: AfterRead(ControlRead(false, 0, problem = "блок не ответил"), null, "")
                    },
                    save = { outcome, after ->
                        val now = System.currentTimeMillis()
                        val kind = "после сброса" + (m?.let { " · блок ${it.addressText}" } ?: "")
                        val head = listOf("Связан с отчётом до сброса: " + (prep.beforeName ?: "не сохранён"),
                            "Итог сброса: ${outcome.title}") + outcome.details
                        val body = after?.body.orEmpty().ifEmpty {
                            "Отчёт диагностики · " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru"))
                                .format(java.util.Date(now)) + " · $kind\n" + demoNote().joinToString("") { "$it\n" }
                        }
                        val text = body.substringBefore("\n") + "\n" + head.joinToString("\n") + "\n" + body.substringAfter("\n", "")
                        var meta = (after?.meta as? ReportMeta) ?: ReportMeta(kind, complete = false, linkedTo = prep.beforeName)
                        // Неподтверждённый или непроверенный сброс — отчёт помечается неполным.
                        if (outcome.kind != OutcomeKind.CLEARED && outcome.kind != OutcomeKind.REMAINING) meta = meta.copy(complete = false)
                        saveReport(text, now, meta).second
                    },
                    publish = { o -> _dtc.update { it.copy(outcome = o) } },
                )
            } finally {
                _dtc.update { it.copy(clearing = false) }
                if (m != null) _vag.update { it.copy(clearing = null) }
            }
        }
        if (!started) _dtc.update { it.copy(warning = "Идёт другая операция — сброс не выполнен. Повторите после её окончания.") }
    }

    fun buildReportText(): String? {
        val r = _dtc.value.report ?: return null
        return ReportFormatter.format(r, _vehicle.value, (_conn.value as? ConnState.Connected), db, _vag.value.results,
            extra = demoNote())
    }

    /**
     * Каждое чтение ошибок попадает в историю — в том числе неполное. Ошибка сохранения
     * показывается, а результат остаётся на экране.
     */
    private suspend fun autoSave() {
        val r = _dtc.value.report ?: return
        val text = buildReportText() ?: return
        val (_, err) = saveReport(text, r.time, metaOf(r))
        if (err != null) _dtc.update { it.copy(warning = "Отчёт не сохранён в историю: $err. Результат остаётся на экране.") }
    }

    /** Опрос блоков VAG тоже сохраняется сам — вместе с частичными результатами. */
    private suspend fun autoSaveVag() {
        val res = _vag.value.results
        if (res.isEmpty()) return
        val now = System.currentTimeMillis()
        val text = ReportFormatter.formatVagScan(res, now, _vehicle.value, _conn.value as? ConnState.Connected, db, demoNote())
        val meta = ReportMeta("блоки VAG", errors = res.sumOf { it.dtcs.size }, complete = res.none { it.error != null } && res.any { it.responded })
        val (_, err) = saveReport(text, now, meta)
        if (err != null) _vag.update { it.copy(error = "Отчёт по блокам не сохранён в историю: $err") }
    }

    fun shareLog(): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        return _log.value.joinToString("\n") { "${fmt.format(java.util.Date(it.time))} ${if (it.outgoing) ">>" else "<<"} ${it.text}" }
    }

    fun refreshReports() {
        viewModelScope.launch(Dispatchers.IO) { _reports.value = listReports() }
    }

    fun deleteReport(r: SavedReport) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { history.delete(r) }
            _reports.value = listReports()
        }
    }

    /** Заметка владельца к отчёту («заменил датчик», «после мойки» и т.п.). */
    fun setReportNote(r: SavedReport, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { history.setNote(r, note) }
            _reports.value = listReports()
        }
    }

    // ---------- Готовность и информация ----------

    private val _sinceCleared = MutableStateFlow<List<Pair<PidDef, Double>>>(emptyList())
    val sinceCleared: StateFlow<List<Pair<PidDef, Double>>> = _sinceCleared.asStateFlow()

    fun readReadiness() = runOp {
        withService { it.readiness() }?.let { _readiness.value = it }
        val result = ArrayList<Pair<PidDef, Double>>()
        for (pid in SINCE_CLEARED) {
            val def = Pids.byId[pid] ?: continue
            val v = catching { withService { it.readPid(def) } }.getOrNull() ?: continue
            result += def to v
        }
        _sinceCleared.value = result
    }

    fun readVehicleInfo() = runOp {
        withService { it.vehicleInfo() }?.let { _vehicle.value = it }
    }

    private fun runOp(block: suspend () -> Unit) {
        if (_busy.value) return
        launchOp {
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
        val scope = sessionScope ?: return
        liveJob = scope.launch {
            if (_live.value.supported.isEmpty()) busLock.withLock { loadSupported() }
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
                        // Если идёт операция (чтение, сброс, VAG…) — ждём её конца.
                        busLock.withLock { withService { it.readPid(def) } } ?: continue
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
        Updater.isBusy = { false }
        disconnect()
        super.onCleared()
    }

    companion object {
        private val SINCE_CLEARED = listOf(0x31, 0x4E, 0x30, 0x21, 0x4D)
        private val OBD_NAMES = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VIECAR")
    }
}
