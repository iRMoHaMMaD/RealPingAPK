package com.realping.app.ui

import com.realping.app.vpn.VpnStateObserver
import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realping.app.config.ConfigPatcher
import com.realping.app.data.*
import com.realping.app.mtu.MtuDiscovery
import com.realping.app.proxy.HotspotIpPicker
import com.realping.app.proxy.ProxyManager
import com.realping.app.vpn.VpnController
import com.realping.app.vpn.VpnProtectHolder
import com.realping.app.net.VpnStateWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import java.net.InetAddress

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val repo = RoutingRepo(ctx)
    private val store = ConnectionStore(ctx)

    // --- Proxy ---
    private val proxy = ProxyManager(port = 8080)
    private val _proxyEnabled = MutableStateFlow(false)
    val proxyEnabled: StateFlow<Boolean> = _proxyEnabled.asStateFlow()
    private val _proxyBindInfo = MutableStateFlow<String?>(null)
    val proxyBindInfo: StateFlow<String?> = _proxyBindInfo.asStateFlow()
    private var triedProxyAutostart = false

    // --- VPN / UI ---
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _stats = MutableStateFlow(0L to 0L) // delta
    val stats: StateFlow<Pair<Long, Long>> = _stats

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private val _routings = MutableStateFlow<List<RoutingEntry>>(emptyList())
    val routings: StateFlow<List<RoutingEntry>> = _routings

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId

    private var pollingJob: Job? = null
    private var timerJob: Job? = null

    // baselines سشن فعال
    private var baseTx = 0L
    private var baseRx = 0L
    private var startAt = 0L

    init {
        // واچر VPN را بالا بیاور
        VpnStateWatcher.start(ctx)
        // به‌محض قطع‌شدن VPN (از تنظیمات گوشی) → ریست فوری
        viewModelScope.launch {
            VpnStateWatcher.active.collectLatest { active ->
                if (!active) {
                    // اگر فکر می‌کنیم وصلیم (state یا prefs)، ریست کامل کن
                    val hadSession = loadSession(ctx) != null
                    if (_isConnected.value || hadSession) {
                        // پاک‌سازی Prefs/UI/Proxy؛ هیچ reconnect خودکاری انجام نده
                        hardResetAfterExternalDown()
                    }
                }
            }
        }

        // 1) لیست روتینگ‌ها
        viewModelScope.launch {
            repo.entries.collectLatest { _routings.value = it }
        }

        // 2) انتخاب آخرین انتخاب‌شده
        viewModelScope.launch {
            ctx.realPingDataStore.data.collectLatest { p ->
                _selectedId.value = p[PrefKeys.SELECTED_ID]
            }
        }

        // 3) منبع حقیقت وضعیت اتصال + رزومه پراکسی (بدون reconnect خودکار)
        viewModelScope.launch {
            ctx.realPingDataStore.data.collectLatest {
                val snap = loadSession(ctx)
                val connected = snap != null
                val wasConnected = _isConnected.value
                _isConnected.value = connected

                if (connected) {
                    // اگر از روی Prefs «وصل» است، فقط رزومهٔ UI/تایمر/آمار را انجام بده
                    if (!wasConnected || pollingJob == null || timerJob == null) {
                        resumeFromSnapshot(snap!!)
                    }
                    // پراکسی اتواستارت مثل قبل
                    if (!triedProxyAutostart) {
                        triedProxyAutostart = true
                        resumeProxyIfNeeded()
                    }
                    // *** توجه: دیگر اینجا ensureBackendHandle() صدا زده نمی‌شود تا reconnect خودکار نشود ***
                } else {
                    triedProxyAutostart = false
                    if (proxy.isRunning()) proxy.stop()
                    _proxyEnabled.value = false
                    _proxyBindInfo.value = null

                    stopTimers()
                    _stats.value = 0L to 0L
                    _elapsedMs.value = 0L
                }
            }
        }

        // ناظر VPN را یک‌بار برای کل Process فعال کن
        VpnStateObserver.start(ctx)

        // روی رویداد «VPN از دست رفت» واکنش بدهیم (بدون polling)
        viewModelScope.launch {
            VpnStateObserver.vpnActive.collectLatest { active ->
                if (active == false) {
                    // اگر هنوز فکر می‌کنیم سشن فعال است، فوراً منبع حقیقت را پاک کن
                    val snap = loadSession(ctx)
                    if (snap != null) {
                        // پراکسی را هم در Persist خاموش کن تا Auto-start ناخواسته نشود
                        setProxyEnabled(ctx, false)
                        saveProxyBind(ctx, null)

                        // این، DataStore را به «قطع» می‌برد؛
                        // کالکتورِ موجودِ خودت بقیه‌ی کارها (ریست UI، توقف پراکسی، توقف تایمرها…) را انجام می‌دهد.
                        markDisconnected(ctx)
                    }
                }
                // روی active==true کاری نمی‌کنیم؛
                // همچنان فقط وقتی خودِ کاربر Connect زد، markConnected می‌زنیم و مانیتورها را راه می‌اندازیم.
            }
        }
    }

    // ---------- Import / CRUD ----------
    fun importPlain(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            repo.addPlainFromUri(nameHint = null, uri = uri)
        }.onSuccess { added ->
            withContext(Dispatchers.Main) { select(added.id) }
        }.onFailure { e ->
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    ctx,
                    e.message ?: "خطا: فایل .conf نامعتبر است یا ساختار WireGuard ندارد.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun importEncrypted(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            repo.addEncryptedFromUri(nameHint = null, uri = uri)
        }.onSuccess { added ->
            withContext(Dispatchers.Main) { select(added.id) }
        }.onFailure { e ->
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    ctx,
                    e.message ?: "خطا: فایل .realping نامعتبر یا غیرقابل دیکریپت است.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** دکمه‌ی واحد (.conf / .realping): هر خطایی رخ دهد با Toast نمایش داده می‌شود و اپ کرش نمی‌کند. */
    fun importFile(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            repo.addAutoFromUri(nameHint = null, uri = uri)
        }.onSuccess { added ->
            withContext(Dispatchers.Main) { select(added.id) }
        }.onFailure { e ->
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    ctx,
                    (e.message ?: "نوع فایل پشتیبانی نمی‌شود یا محتوای فایل نامعتبر است. فقط فایل .realping"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun importClipboard() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.addFromClipboard() }
            .onSuccess { added ->
                withContext(Dispatchers.Main) {
                    select(added.id)
                    Toast.makeText(ctx, "روتینگ «${added.name}» اضافه شد.", Toast.LENGTH_SHORT).show()
                }
            }
            .onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        ctx,
                        (e.message ?: "ورودی نامعتبر است. لطفاً فرمت «n : نام : متن Base64» را وارد کنید."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    fun removeRouting(id: String) = viewModelScope.launch {
        val snap = loadSession(ctx)
        if (snap?.id == id) {
            runCatching { withContext(Dispatchers.IO) { VpnController.disconnect(ctx) } }
            markDisconnected(ctx)
            stopTimers()
            _isConnected.value = false
            _elapsedMs.value = 0L
            _stats.value = 0L to 0L
        }
        repo.delete(id)
        if (selectedId.value == id) {
            _selectedId.value = null
            saveSelectedId(ctx, null)
        }
    }

    fun delete(id: String) = removeRouting(id)

    fun select(id: String) = viewModelScope.launch {
        _selectedId.value = id
        saveSelectedId(ctx, id)
    }

    fun onVpnDenied() { _isConnecting.value = false }

    // ---------- اتصال ----------
    fun performConnect() = viewModelScope.launch {
        val id = _selectedId.value ?: return@launch
        val confOriginal = repo.readConfigText(id)

        _isConnecting.value = true
        val mtu = try { MtuDiscovery.discoverBestMtu() } catch (_: Throwable) { 1420 }
        val patched = ConfigPatcher.overrideMtu(confOriginal, mtu)

        try {
            val ok = runCatching {
                VpnController.connectWithConfigText(ctx, patched)
            }.isSuccess
            if (!ok) {
                Toast.makeText(ctx, "خطا در اتصال.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val (tx0, rx0) = VpnController.getStatisticsNow(ctx)
            baseTx = tx0
            baseRx = rx0
            startAt = System.currentTimeMillis()

            markConnected(ctx, id, startAt, baseTx, baseRx)
            startMonitors(startAt, baseTx, baseRx)

            triedProxyAutostart = false
            resumeProxyIfNeeded()

            _isConnected.value = true
        } finally {
            _isConnecting.value = false
        }
    }

    fun disconnect() = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) { VpnController.disconnect(ctx) }
        }
        _isConnected.value = false
        stopTimers()
        _stats.value = 0L to 0L
        _elapsedMs.value = 0L
        store.clearConnectionMeta()
        markDisconnected(ctx)

        if (proxy.isRunning()) proxy.stop()
        _proxyEnabled.value = false
        _proxyBindInfo.value = null
        setProxyEnabled(ctx, false)
        saveProxyBind(ctx, null)
    }

    // ---------- سوییچ پراکسی ----------
    fun setProxyEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Main) {
        if (enabled) {
            if (!_isConnected.value) {
                Toast.makeText(ctx, "ابتدا به روتینگ وصل شوید.", Toast.LENGTH_SHORT).show()
                setProxyEnabled(ctx, false)
                _proxyEnabled.value = false
                _proxyBindInfo.value = null
                return@launch
            }

            // اگر همین حالا در حال اجراست، فقط UI/Persist را سینک کن
            if (proxy.isRunning()) {
                _proxyEnabled.value = true
                val saved = withContext(Dispatchers.IO) { loadProxyBind(ctx) }
                _proxyBindInfo.value = saved ?: proxy.currentBindInfo()
                setProxyEnabled(ctx, true)
                return@launch
            }

            proxy.setProtector(VpnProtectHolder.protectFd)

            val ip: InetAddress? = withContext(Dispatchers.Default) { HotspotIpPicker.pick() }
            if (ip == null) {
                Toast.makeText(ctx, "IP هات‌اسپات پیدا نشد. هات‌اسپات را روشن کنید.", Toast.LENGTH_LONG).show()
                setProxyEnabled(ctx, false)
                _proxyEnabled.value = false
                _proxyBindInfo.value = null
                return@launch
            }

            val ok = try { proxy.start(ip) } catch (_: Throwable) { false }
            if (!ok) {
                Toast.makeText(ctx, "راه‌اندازی پراکسی ناموفق: پورت مشغول یا خطای bind.", Toast.LENGTH_LONG).show()
                setProxyEnabled(ctx, false)
                _proxyEnabled.value = false
                _proxyBindInfo.value = null
                return@launch
            }

            _proxyEnabled.value = true
            _proxyBindInfo.value = "${ip.hostAddress}:8080"
            setProxyEnabled(ctx, true) // Persist
            saveProxyBind(ctx, _proxyBindInfo.value)
            Toast.makeText(ctx, "پراکسی روی ${_proxyBindInfo.value} فعال شد.", Toast.LENGTH_SHORT).show()
        } else {
            if (proxy.isRunning()) proxy.stop()
            _proxyEnabled.value = false
            _proxyBindInfo.value = null
            setProxyEnabled(ctx, false) // Persist
            saveProxyBind(ctx, null)
            Toast.makeText(ctx, "پراکسی غیرفعال شد.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- رزومه ----------
    private fun resumeFromSnapshot(s: SessionSnapshot) {
        baseTx = s.baseTx
        baseRx = s.baseRx
        startAt = s.startMs
        _elapsedMs.value = max(0L, System.currentTimeMillis() - startAt)
        startMonitors(startAt, baseTx, baseRx)
    }

    // اگر کاربر PROXY_ENABLED را روشن ذخیره کرده و تونل بالاست:
    // یا اگر پراکسی قبلاً در حال اجراست، فقط UI را سینک می‌کنیم.
    private fun resumeProxyIfNeeded() = viewModelScope.launch(Dispatchers.Main) {
        if (!_isConnected.value) return@launch

        // اگر همین الآن در حال اجراست، فقط UI/Persist را سینک کن
        if (proxy.isRunning()) {
            _proxyEnabled.value = true
            val saved = withContext(Dispatchers.IO) { loadProxyBind(ctx) }
            _proxyBindInfo.value = saved ?: proxy.currentBindInfo()
            return@launch
        }

        val want = withContext(Dispatchers.IO) { isProxyEnabled(ctx) }
        if (!want) {
            _proxyEnabled.value = false
            _proxyBindInfo.value = null
            return@launch
        }

        proxy.setProtector(VpnProtectHolder.protectFd)
        val ip = withContext(Dispatchers.Default) { HotspotIpPicker.pick() }
        if (ip == null) {
            _proxyEnabled.value = false
            _proxyBindInfo.value = null
            return@launch
        }
        val ok = proxy.start(ip)
        if (ok) {
            _proxyEnabled.value = true
            _proxyBindInfo.value = "${ip.hostAddress}:8080"
            saveProxyBind(ctx, _proxyBindInfo.value)
        } else {
            _proxyEnabled.value = false
            _proxyBindInfo.value = null
            setProxyEnabled(ctx, false)
        }
    }

    // ---------- مانیتورها ----------
    private fun startMonitors(startMs: Long, baseTx: Long, baseRx: Long) {
        stopTimers()

        // مدت‌زمان
        timerJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                _elapsedMs.value = max(0L, System.currentTimeMillis() - startMs)
                delay(1000)
            }
        }

        // آمار
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val (txCum, rxCum) = VpnController.getStatisticsNow(ctx)
                val dTx = (txCum - baseTx).coerceAtLeast(0L)
                val dRx = (rxCum - baseRx).coerceAtLeast(0L)
                _stats.value = dTx to dRx
                delay(1000)
            }
        }
    }

    private fun stopTimers() {
        pollingJob?.cancel(); pollingJob = null
        timerJob?.cancel(); timerJob = null
    }

    // ریست کامل وقتی تونل خارج از اپ قطع می‌شود
    private suspend fun hardResetAfterExternalDown() {
        _isConnected.value = false
        stopTimers()
        _stats.value = 0L to 0L
        _elapsedMs.value = 0L
        store.clearConnectionMeta()
        markDisconnected(ctx)

        if (proxy.isRunning()) proxy.stop()
        _proxyEnabled.value = false
        _proxyBindInfo.value = null
        setProxyEnabled(ctx, false)
        saveProxyBind(ctx, null)
    }

    override fun onCleared() {
        if (proxy.isRunning()) proxy.stop()
        VpnStateWatcher.stop()
        super.onCleared()
    }
}
