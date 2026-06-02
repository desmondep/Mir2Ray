package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PluginServiceManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val DELAY_TEST_MAX_PARALLEL_DEFAULT = 30
        private const val DELAY_TEST_TIMEOUT_MS = 8_000L
        private val DELAY_TEST_PARALLEL_OPTIONS = intArrayOf(20, 30, 40, 50, 60)
        private const val AUTO_PING_STABILIZATION_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        } else {
            updateProcessState(getString(R.string.neon_connect_failed))
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()
    private var connectPulseAnimator: ObjectAnimator? = null
    private var giveConfigsPulseAnimator: ObjectAnimator? = null
    private var optimizePulseAnimator: ObjectAnimator? = null
    private var scanlineAnimator: ObjectAnimator? = null
    private var pingLoopJob: Job? = null
    private var emptyConfigCheckJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var lastPingText: String? = null
    private var lastPingMillis: Long? = null
    private var pendingConnectAttempt = false
    private val toggleInProgress = AtomicBoolean(false)
    private var connectAttemptStartedAt = 0L
    private var isGiveConfigsRunning = false
    private var isOptimizeRunning = false
    private var isAutoSwitching = false
    private var nextAutoPingCheckAtMs: Long = 0L

    private data class DelayFilterResult(
        val testedCount: Int,
        val removedCount: Int,
        val goodCount: Int
    )

    private val fixedSubscriptionUrl =
        "https://raw.githubusercontent.com/miraali1372/mirsub2/main/subscription.txt"

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, getString(R.string.title_file_chooser)))

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.title_server)
        setSupportActionBar(binding.toolbar)

        setupMinimalUiMode()
        startScanlineEffect()

        binding.btnGiveConfigs.setOnClickListener {
            lifecycleScope.launch {
                processGiveNewConfigs()
            }
        }

        binding.btnOptimize.setOnClickListener {
            lifecycleScope.launch {
                processOptimizeConfigs()
            }
        }

        binding.btnOptimize.setOnLongClickListener {
            showDelayParallelSelector()
            true
        }

        binding.btnConnect.setOnClickListener {
            lifecycleScope.launch {
                toggleConnect()
            }
        }

        binding.btnNextConfig.setOnClickListener {
            lifecycleScope.launch {
                skipToNextConfig()
            }
        }

        binding.tvBrandTitle.setOnClickListener {
            lifecycleScope.launch {
                if (mainViewModel.isRunning.value == true) {
                    updateProcessState("درحال تست پینگ واقعی کانفیگ فعال…")
                    val switched = evaluateServersAndMaybeSwitch(autoSwitch = true)
                    if (!switched) {
                        val pingText = lastPingMillis?.let { "${it}ms" }
                            ?: getString(R.string.neon_ping_unavailable_short)
                        updateProcessState("تست انجام شد • پینگ فعلی: $pingText • تعداد کانفیگ: ${availableServerCount()}")
                    }
                } else {
                    updateProcessState("اول Connect بزنید، بعد روی Mir2Ray برای تست پینگ کلیک کنید")
                }
            }
        }

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                lifecycleScope.launch {
                    evaluateServersAndMaybeSwitch(autoSwitch = false)
                }
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        initGroupTab()
        setupViewModel()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
            updateConfigCountBadge()
        }
        mainViewModel.updateTestResultAction.observe(this) {
            lastPingText = it
            lastPingMillis = parsePingMillis(it)
            val displayText = if (mainViewModel.isRunning.value == true && isPingErrorText(it)) {
                getString(R.string.neon_ping_unavailable_short)
            } else {
                it
            }
            setTestState(displayText)
            updateConnectionStateText(mainViewModel.isRunning.value == true)
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            if (isRunning) {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.fab.contentDescription = getString(R.string.action_stop_service)
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
                binding.btnConnect.text = getString(R.string.neon_disconnect)
                binding.pbConnect.isVisible = false
                stopConnectPulse()
                pendingConnectAttempt = false
                updateProcessState(getString(R.string.neon_connected))
                binding.btnNextConfig.isVisible = true
                if (mainViewModel.isRunning.value == true) {
                    scheduleNextAutoPingCheck(AUTO_PING_STABILIZATION_MS)
                    startPingLoop()
                    lifecycleScope.launch {
                        delay(4_000)
                        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
                        if (selectedGuid.isNotBlank()) {
                            val delay = withContext(Dispatchers.IO) { measureRealDelayForGuid(selectedGuid) }
                            if (delay > 0L) {
                                val pingText = "${delay}ms"
                                lastPingText = pingText
                                lastPingMillis = delay
                                setTestState(pingText)
                                updateConnectionStateText(true)
                                updateProcessState("وصل شد • پینگ کانفیگ: $pingText")
                            }
                        }
                    }
                }
            } else {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                emptyConfigCheckJob?.cancel()
                emptyConfigCheckJob = null
                pingLoopJob?.cancel()
                pingLoopJob = null
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                binding.fab.contentDescription = getString(R.string.tasker_start_service)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                binding.btnConnect.text = getString(R.string.neon_connect)
                binding.btnNextConfig.isVisible = false
                stopPingLoop()
                if (pendingConnectAttempt) {
                    updateProcessState(getString(R.string.neon_connect_failed))
                    pendingConnectAttempt = false
                    binding.pbConnect.isVisible = false
                    stopConnectPulse()
                } else {
                    updateProcessState(getString(R.string.neon_disconnected))
                }
            }
            updateConnectionStateText(isRunning)
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                } else {
                    //toast(getString(R.string.migration_fail))
                }
            }

        }
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = true
    }

    private fun startV2Ray(): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            updateProcessState(getString(R.string.neon_connect_failed))
            return false
        }
        V2RayServiceManager.startVService(this)
        return true
    }

    private fun restartV2Ray() {
        scheduleNextAutoPingCheck(AUTO_PING_STABILIZATION_MS)
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
        updateConfigCountBadge()
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        stopPingLoop()
        pingLoopJob?.cancel()
        pingLoopJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        stopConnectPulse()
        setGiveConfigsLoading(false)
        setOptimizeLoading(false)
        scanlineAnimator?.cancel()
        scanlineAnimator = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false

//        menuInflater.inflate(R.menu.menu_main, menu)
//        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.intelligent_selection_all -> {
            lifecycleScope.launch {
                updateProcessState(getString(R.string.neon_optimizing))
                val fastestGuid = selectFastestDirectServerGuid()
                if (!fastestGuid.isNullOrBlank()) {
                    MmkvManager.setSelectServer(fastestGuid)
                    mainViewModel.reloadServerList()
                    toast(R.string.toast_success)
                    updateProcessState(getString(R.string.neon_optimized_ready))
                } else {
                    toastError(R.string.toast_failure)
                    updateProcessState(getString(R.string.neon_failed_configs))
                }
            }
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }


        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    private fun setupMinimalUiMode() {
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.navView.isVisible = false
        binding.tabGroup.isVisible = false
        binding.recyclerView.isVisible = false
        binding.layoutTest.isVisible = false
        binding.fab.isVisible = false
        binding.tvConnectionState.text = getString(R.string.neon_disconnected)
        updateOptimizeButtonLabel()
        updateConfigCountBadge()
        updateProcessState("${getString(R.string.neon_idle)} • تعداد کانفیگ: ${availableServerCount()}")
    }

    private fun getDelayTestParallel(): Int {
        val saved = MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_PARALLEL)?.toIntOrNull()
        val candidate = saved ?: DELAY_TEST_MAX_PARALLEL_DEFAULT
        return if (DELAY_TEST_PARALLEL_OPTIONS.contains(candidate)) candidate else DELAY_TEST_MAX_PARALLEL_DEFAULT
    }

    private fun setDelayTestParallel(value: Int) {
        val sanitized = if (DELAY_TEST_PARALLEL_OPTIONS.contains(value)) value else DELAY_TEST_MAX_PARALLEL_DEFAULT
        MmkvManager.encodeSettings(AppConfig.PREF_DELAY_TEST_PARALLEL, sanitized.toString())
        updateOptimizeButtonLabel()
        updateProcessState("تعداد تست موازی روی $sanitized تنظیم شد")
    }

    private fun updateOptimizeButtonLabel() {
        val current = getDelayTestParallel()
        binding.btnOptimize.text = "${getString(R.string.neon_optimize)} ($current)"
    }

    private fun showDelayParallelSelector() {
        val current = getDelayTestParallel()
        val options = DELAY_TEST_PARALLEL_OPTIONS.map { it.toString() }.toTypedArray()
        val checkedIndex = DELAY_TEST_PARALLEL_OPTIONS.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("تعداد تست موازی")
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                setDelayTestParallel(DELAY_TEST_PARALLEL_OPTIONS[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun availableServerCount(): Int {
        return MmkvManager.decodeServerList()
            .count { guid ->
                val cfg = MmkvManager.decodeServerConfig(guid)
                cfg != null && cfg.configType != EConfigType.CUSTOM
            }
    }

    private suspend fun skipToNextConfig() {
        if (mainViewModel.isRunning.value != true) return
        if (isAutoSwitching || isGiveConfigsRunning || isOptimizeRunning) return

        val currentGuid = MmkvManager.getSelectServer().orEmpty()
        if (currentGuid.isBlank()) {
            updateProcessState("کانفیگ فعلی یافت نشد")
            return
        }

        binding.btnNextConfig.isEnabled = false
        updateProcessState("درحال سوییچ به کانفیگ بعدی…")

        try {
            val nextGuid = withContext(Dispatchers.IO) {
                mainViewModel.sortByTestResults()
                findFirstSortedDirectServerGuid(excludeGuid = currentGuid)
            }

            if (nextGuid.isNullOrBlank()) {
                updateProcessState("کانفیگ دیگری وجود ندارد؛ نمی‌توان سوییچ کرد")
                return
            }

            // Delete current config
            withContext(Dispatchers.IO) {
                runCatching { MmkvManager.removeServer(currentGuid) }
            }
            mainViewModel.reloadServerList()

            if (availableServerCount() <= 0) {
                handleEmptyConfigsWhileConnected()
                return
            }

            // Switch to next config
            MmkvManager.setSelectServer(nextGuid)
            mainViewModel.reloadServerList()
            updateConfigCountBadge()
            val nextHost = MmkvManager.decodeServerConfig(nextGuid)?.server.orEmpty()
            updateProcessState(
                if (nextHost.isNotBlank()) {
                    "سوییچ به کانفیگ بعدی: $nextHost • تعداد: ${availableServerCount()}"
                } else {
                    "سوییچ به کانفیگ بعدی انجام شد • تعداد: ${availableServerCount()}"
                }
            )
            scheduleNextAutoPingCheck(AUTO_PING_STABILIZATION_MS)
            restartV2Ray()
        } finally {
            binding.btnNextConfig.isEnabled = true
        }
    }

    private suspend fun toggleConnect() {
        // Use compareAndSet for safe, atomic operation
        if (!toggleInProgress.compareAndSet(false, true)) {
            return
        }

        try {
            toggleConnectInner()
        } finally {
            toggleInProgress.set(false)
        }
    }

    private suspend fun toggleConnectInner() {
        // If already connected → disconnect
        if (mainViewModel.isRunning.value == true) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            pingLoopJob?.cancel()
            pingLoopJob = null
            pendingConnectAttempt = false
            stopConnectPulse()
            binding.pbConnect.isVisible = false
            V2RayServiceManager.stopVService(this)
            updateProcessState(getString(R.string.neon_disconnected))
            return
        }

        // No configs available
        val availableCount = availableServerCount()
        if (availableCount <= 0) {
            updateProcessState("تعداد کانفیگ: 0 • لیست خالیه؛ لطفاً روی Give بزنید")
            return
        }

        // Find and select best server
        val firstSortedGuid = findFirstSortedDirectServerGuid()
        if (!firstSortedGuid.isNullOrBlank()) {
            MmkvManager.setSelectServer(firstSortedGuid)
            mainViewModel.reloadServerList()
            val selectedHost = MmkvManager.decodeServerConfig(firstSortedGuid)?.server.orEmpty()
            if (selectedHost.isNotBlank()) {
                updateProcessState("درحال اتصال به: $selectedHost • تعداد کانفیگ: $availableCount")
            }
        }

        // Mark connection attempt as pending
        pendingConnectAttempt = true
        connectAttemptStartedAt = System.currentTimeMillis()
        binding.pbConnect.isVisible = true
        startConnectPulse()
        updateProcessState(getString(R.string.neon_connecting))

        // Set timeout for connection attempt
        connectTimeoutJob?.cancel()
        connectTimeoutJob = lifecycleScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (pendingConnectAttempt && mainViewModel.isRunning.value != true) {
                updateProcessState(getString(R.string.neon_connect_failed))
            }
        }

        // Request VPN permission if needed, then start service
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN
        if (mode == VPN) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                // VPN permission already granted
                startV2Ray()
            } else {
                // Need to request VPN permission
                requestVpnPermission.launch(intent)
            }
        } else {
            // Non-VPN mode
            startV2Ray()
        }
    }

    private suspend fun processGiveNewConfigs() {
        if (isGiveConfigsRunning) return
        isGiveConfigsRunning = true
        setGiveConfigsLoading(true)
        updateGiveProgress(0)
        binding.pbWaiting.show()
        updateProcessState(getString(R.string.neon_fetching_configs))

        val success = try {
            withContext(Dispatchers.IO) {
                try {
