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
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val DELAY_TEST_MAX_PARALLEL_DEFAULT = 30
        private const val DELAY_TEST_TIMEOUT_MS = 8_000L
        private val DELAY_TEST_PARALLEL_OPTIONS = intArrayOf(20, 30, 40, 50, 60)
    }

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }
    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            if (!startV2Ray()) {
                pendingConnectAttempt = false
                binding.pbConnect.isVisible = false
                stopConnectPulse()
                updateProcessState(getString(R.string.neon_connect_failed))
            }
        } else {
            pendingConnectAttempt = false
            binding.pbConnect.isVisible = false
            stopConnectPulse()
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
    private var connectTimeoutJob: Job? = null
    private var lastPingText: String? = null
    private var lastPingMillis: Long? = null
    private var pendingConnectAttempt = false
    private var isGiveConfigsRunning = false
    private var isOptimizeRunning = false
    private var isAutoSwitching = false

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
                if (mainViewModel.isRunning.value == true) {
                    startPingLoop()
                    lifecycleScope.launch {
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
                pingLoopJob?.cancel()
                pingLoopJob = null
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                binding.fab.contentDescription = getString(R.string.tasker_start_service)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                binding.btnConnect.text = getString(R.string.neon_connect)
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
            pendingConnectAttempt = false
            binding.pbConnect.isVisible = false
            stopConnectPulse()
            updateProcessState(getString(R.string.neon_connect_failed))
            return false
        }
        V2RayServiceManager.startVService(this)
        return true
    }

    private fun restartV2Ray() {
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

    private suspend fun toggleConnect() {
        if (pendingConnectAttempt && mainViewModel.isRunning.value != true) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            pendingConnectAttempt = false
            stopConnectPulse()
            binding.pbConnect.isVisible = false
            V2RayServiceManager.stopVService(this)
            updateProcessState(getString(R.string.neon_connect_failed))
            return
        }

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

        val availableCount = availableServerCount()
        if (availableCount <= 0) {
            updateProcessState("تعداد کانفیگ: 0 • لیست خالیه؛ لطفاً روی Give بزنید")
            return
        }

        val firstSortedGuid = findFirstSortedDirectServerGuid()
        if (!firstSortedGuid.isNullOrBlank()) {
            MmkvManager.setSelectServer(firstSortedGuid)
            mainViewModel.reloadServerList()
            val selectedHost = MmkvManager.decodeServerConfig(firstSortedGuid)?.server.orEmpty()
            if (selectedHost.isNotBlank()) {
                updateProcessState("درحال اتصال به: $selectedHost • تعداد کانفیگ: $availableCount")
                Log.i(AppConfig.TAG, "Selected connect server host: $selectedHost")
            }
        }

        pendingConnectAttempt = true
        binding.pbConnect.isVisible = true
        startConnectPulse()
        updateProcessState(getString(R.string.neon_connecting))
        connectTimeoutJob?.cancel()
        connectTimeoutJob = lifecycleScope.launch {
            delay(15_000)
            if (pendingConnectAttempt && mainViewModel.isRunning.value != true) {
                pendingConnectAttempt = false
                binding.pbConnect.isVisible = false
                stopConnectPulse()
                updateProcessState(getString(R.string.neon_connect_failed))
            }
        }

        if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                if (!startV2Ray()) return
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            if (!startV2Ray()) return
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
                    withContext(Dispatchers.Main) {
                        updateProcessState("مرحله ۱/۴: دریافت کانفیگ‌ها…")
                    }
                    MmkvManager.removeAllServer()
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(8) }

                    val fetched = HttpUtil.getUrlContentWithUserAgent(fixedSubscriptionUrl, null)
                    val normalized = normalizeConfigRows(fetched)
                    if (normalized.isBlank()) {
                        return@withContext false
                    }
                    val importedCount = normalized.lineSequence().count { it.isNotBlank() }
                    runOnUiThread { updateGiveProgress(20) }

                    AngConfigManager.importBatchConfig(normalized, "", true)
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(35) }

                    val beforeDedup = MmkvManager.decodeServerList().size

                    withContext(Dispatchers.Main) {
                        updateProcessState("مرحله ۲/۴: حذف تکراری‌ها…")
                    }
                    mainViewModel.removeDuplicateServer()
                    mainViewModel.reloadServerList()
                    val afterDedup = MmkvManager.decodeServerList().size
                    val duplicateRemoved = (beforeDedup - afterDedup).coerceAtLeast(0)
                    runOnUiThread { updateGiveProgress(45) }

                    withContext(Dispatchers.Main) {
                        updateProcessState("مرحله ۳/۴: تست تاخیر واقعی…")
                    }
                    val filterResult = removeSlowAndInvalidServers { done, total ->
                        if (total > 0) {
                            val percent = 45 + (done * 35 / total)
                            runOnUiThread { updateGiveProgress(percent) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        updateProcessState(
                            "مرحله ۴/۴: Imported=$importedCount | DuplicateRemoved=$duplicateRemoved | Tested=${filterResult.testedCount} | RemovedBad=${filterResult.removedCount} | Good=${filterResult.goodCount}"
                        )
                    }
                    mainViewModel.sortByTestResults()
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(85) }

                    val bestGuid = findBestDirectServerGuid()
                    if (!bestGuid.isNullOrBlank()) {
                        MmkvManager.setSelectServer(bestGuid)
                    }
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(100) }
                    !bestGuid.isNullOrBlank()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed in Give New Configs flow", e)
                    false
                }
            }
        } finally {
            binding.pbWaiting.hide()
            setGiveConfigsLoading(false)
            isGiveConfigsRunning = false
        }

        if (success) {
            updateProcessState("${getString(R.string.neon_ready_configs)} • تعداد کانفیگ: ${availableServerCount()}")
        } else {
            updateProcessState(getString(R.string.neon_failed_configs))
            toastError(R.string.toast_failure)
        }
    }

    private suspend fun processOptimizeConfigs(autoTriggered: Boolean = false): Boolean {
        if (isOptimizeRunning) return false
        isOptimizeRunning = true
        val optimizeStartedAt = System.currentTimeMillis()
        Log.i(AppConfig.TAG, "OPTIMIZE_BENCH start autoTriggered=$autoTriggered")
        if (!autoTriggered) {
            setOptimizeLoading(true)
            updateOptimizeProgress(0)
            binding.pbWaiting.show()
        }
        updateProcessState(getString(R.string.neon_optimizing))

        val success = try {
            withContext(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) {
                        updateProcessState("مرحله ۱/۴: آماده‌سازی بهینه‌سازی…")
                    }
                    val beforeDedup = MmkvManager.decodeServerList()
                        .count { guid ->
                            val cfg = MmkvManager.decodeServerConfig(guid)
                            cfg != null && cfg.configType != EConfigType.CUSTOM
                        }
                    mainViewModel.removeDuplicateServer()
                    mainViewModel.reloadServerList()
                    val afterDedup = MmkvManager.decodeServerList()
                        .count { guid ->
                            val cfg = MmkvManager.decodeServerConfig(guid)
                            cfg != null && cfg.configType != EConfigType.CUSTOM
                        }
                    val duplicateRemoved = (beforeDedup - afterDedup).coerceAtLeast(0)
                    if (!autoTriggered) {
                        runOnUiThread { updateOptimizeProgress(15) }
                    }
                    withContext(Dispatchers.Main) {
                        updateProcessState("مرحله ۲/۴: تست تاخیر واقعی…")
                    }
                    val filterResult = removeSlowAndInvalidServers { done, total ->
                        if (!autoTriggered && total > 0) {
                            val percent = 15 + (done * 60 / total)
                            runOnUiThread { updateOptimizeProgress(percent) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        updateProcessState(
                            "مرحله ۳/۴: DuplicateRemoved=$duplicateRemoved | Tested=${filterResult.testedCount} | RemovedBad=${filterResult.removedCount} | Good=${filterResult.goodCount}"
                        )
                    }
                    mainViewModel.sortByTestResults()
                    mainViewModel.reloadServerList()
                    if (!autoTriggered) {
                        runOnUiThread { updateOptimizeProgress(82) }
                    }

                    val bestGuid = findBestDirectServerGuid()
                    if (!bestGuid.isNullOrBlank()) {
                        MmkvManager.setSelectServer(bestGuid)
                    }
                    mainViewModel.reloadServerList()
                    if (!autoTriggered) {
                        runOnUiThread { updateOptimizeProgress(100) }
                    }
                    withContext(Dispatchers.Main) {
                        updateProcessState("مرحله ۴/۴: بهینه‌سازی کامل شد")
                    }
                    !bestGuid.isNullOrBlank()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed in Optimize flow", e)
                    false
                }
            }
        } finally {
            if (!autoTriggered) {
                binding.pbWaiting.hide()
                setOptimizeLoading(false)
            }
            isOptimizeRunning = false
        }

        if (success) {
            updateProcessState("${getString(R.string.neon_optimized_ready)} • تعداد کانفیگ: ${availableServerCount()}")
        } else {
            updateProcessState(getString(R.string.neon_failed_configs))
            if (!autoTriggered) {
                toastError(R.string.toast_failure)
            }
        }
        val optimizeElapsedMs = System.currentTimeMillis() - optimizeStartedAt
        Log.i(AppConfig.TAG, "OPTIMIZE_BENCH end success=$success autoTriggered=$autoTriggered elapsedMs=$optimizeElapsedMs")
        return success
    }

    private suspend fun removeSlowAndInvalidServers(): DelayFilterResult {
        return removeSlowAndInvalidServers(null)
    }

    private suspend fun removeSlowAndInvalidServers(onProgress: ((done: Int, total: Int) -> Unit)?): DelayFilterResult {
        mainViewModel.reloadServerList()
        val candidates = mainViewModel.serversCache
            .filter { it.profile.configType != EConfigType.CUSTOM }
            .map { it.guid }
            .toList()

        if (candidates.isEmpty()) {
            return DelayFilterResult(testedCount = 0, removedCount = 0, goodCount = 0)
        }

        val removeList = mutableListOf<String>()
        val measuredDelay = measureDelaysInBatches(candidates, onProgress)
        measuredDelay.forEach { (guid, delay) ->
            if (delay < 0L || delay > 500L) {
                removeList.add(guid)
            }
        }

        if (removeList.size >= candidates.size && candidates.isNotEmpty()) {
            val keepGuid = measuredDelay
                .filterValues { it > 0L }
                .minByOrNull { it.value }
                ?.key
                ?: candidates.first()
            removeList.remove(keepGuid)
        }

        removeList.forEach { MmkvManager.removeServer(it) }
        val goodCount = (candidates.size - removeList.size).coerceAtLeast(0)
        return DelayFilterResult(
            testedCount = candidates.size,
            removedCount = removeList.size,
            goodCount = goodCount
        )
    }

    private fun measureRealDelayForGuid(guid: String): Long {
        val config = MmkvManager.decodeServerConfig(guid) ?: return -1L
        return if (config.configType == EConfigType.HYSTERIA2) {
            PluginServiceManager.realPingHy2(this, config)
        } else {
            val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(this, guid)
            if (!configResult.status) {
                -1L
            } else {
                SpeedtestManager.realPing(configResult.content)
            }
        }
    }

    private fun normalizeConfigRows(content: String): String {
        if (content.isBlank()) {
            return ""
        }
        val pattern = Regex("(vmess|vless|trojan|ss|socks|wireguard|hysteria2|hy2)://[^\\s]+")
        val links = pattern.findAll(content).map { it.value.trim() }.toList()
        return if (links.isEmpty()) content else links.joinToString("\n")
    }

    private fun findBestDirectServerGuid(excludeGuid: String? = null): String? {
        return MmkvManager.decodeServerList()
            .asSequence()
            .filter { it != excludeGuid }
            .filter { guid ->
                val cfg = MmkvManager.decodeServerConfig(guid)
                cfg != null && cfg.configType != EConfigType.CUSTOM
            }
            .sortedBy { directServerDelayScore(it) }
            .firstOrNull()
    }

    private fun findFirstSortedDirectServerGuid(excludeGuid: String? = null): String? {
        return MmkvManager.decodeServerList()
            .asSequence()
            .filter { it != excludeGuid }
            .filter { guid ->
                val cfg = MmkvManager.decodeServerConfig(guid)
                cfg != null && cfg.configType != EConfigType.CUSTOM
            }
            .firstOrNull()
    }

    private data class DirectServerEvaluation(
        val bestGuid: String?,
        val delayByGuid: Map<String, Long>
    )

    private suspend fun selectFastestDirectServerGuid(): String? {
        return evaluateDirectServers().bestGuid
    }

    private suspend fun evaluateDirectServers(): DirectServerEvaluation = withContext(Dispatchers.IO) {
        val candidates = MmkvManager.decodeServerList()
            .mapNotNull { guid ->
                val cfg = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                if (cfg.configType == EConfigType.CUSTOM) return@mapNotNull null
                guid
            }

        if (candidates.isEmpty()) {
            return@withContext DirectServerEvaluation(null, emptyMap())
        }

        val delayMap = measureDelaysInBatches(candidates, null)

        val bestByFreshDelay = delayMap
            .filterValues { it > 0L }
            .minByOrNull { it.value }
            ?.key
        val fallback = candidates.minByOrNull { directServerDelayScore(it) }
        DirectServerEvaluation(bestByFreshDelay ?: fallback, delayMap)
    }

    private fun directServerDelayScore(guid: String): Long {
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: Long.MAX_VALUE
        return if (delay > 0L) delay else Long.MAX_VALUE
    }

    private suspend fun measureDelaysInBatches(
        candidates: List<String>,
        onProgress: ((done: Int, total: Int) -> Unit)?
    ): MutableMap<String, Long> {
        val delayMap = mutableMapOf<String, Long>()
        val processedCount = AtomicInteger(0)
        val total = candidates.size

        val selectedParallel = getDelayTestParallel()
        val semaphore = Semaphore(selectedParallel)
        coroutineScope {
            candidates.map { guid ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val delay = withTimeoutOrNull(DELAY_TEST_TIMEOUT_MS) {
                            measureRealDelayForGuid(guid)
                        } ?: -1L
                        MmkvManager.encodeServerTestDelayMillis(guid, delay)
                        synchronized(delayMap) {
                            delayMap[guid] = delay
                        }
                        val done = processedCount.incrementAndGet()
                        onProgress?.invoke(done, total)
                    }
                }
            }.awaitAll()
        }
        return delayMap
    }

    private suspend fun evaluateServersAndMaybeSwitch(autoSwitch: Boolean): Boolean {
        if (isGiveConfigsRunning || isOptimizeRunning || pendingConnectAttempt || isAutoSwitching) return false
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (selectedGuid.isBlank()) return false

        val currentDelay = withContext(Dispatchers.IO) { measureRealDelayForGuid(selectedGuid) }

        lastPingMillis = currentDelay.takeIf { it > 0L }
        lastPingText = lastPingMillis?.let { "${it}ms" } ?: getString(R.string.neon_ping_unavailable_short)
        setTestState(lastPingText ?: getString(R.string.neon_ping_unavailable_short))
        updateConnectionStateText(mainViewModel.isRunning.value == true)

        if (!autoSwitch || mainViewModel.isRunning.value != true) {
            return false
        }

        val currentTooHigh = currentDelay > 500L
        if (!currentTooHigh) return false

        isAutoSwitching = true
        return try {
            updateProcessState("پینگ ${currentDelay}ms بالای 500 شد؛ حذف کانفیگ فعلی و سوییچ به بعدی…")

            withContext(Dispatchers.IO) {
                runCatching { MmkvManager.removeServer(selectedGuid) }
                mainViewModel.sortByTestResults()
            }
            mainViewModel.reloadServerList()

            val nextGuid = findFirstSortedDirectServerGuid(excludeGuid = selectedGuid)
            if (nextGuid.isNullOrBlank()) {
                updateProcessState("کانفیگ سالم بعدی وجود ندارد • تعداد کانفیگ: ${availableServerCount()}")
                return false
            }

            MmkvManager.setSelectServer(nextGuid)
            mainViewModel.reloadServerList()
            val nextHost = MmkvManager.decodeServerConfig(nextGuid)?.server.orEmpty()
            updateProcessState(
                if (nextHost.isNotBlank()) {
                    "سوییچ به کانفیگ بعدی: $nextHost"
                } else {
                    "سوییچ به کانفیگ بعدی انجام شد"
                }
            )
            restartV2Ray()
            true
        } finally {
            isAutoSwitching = false
        }
    }

    private fun startPingLoop() {
        stopPingLoop()
        pingLoopJob = lifecycleScope.launch {
            while (isActive && mainViewModel.isRunning.value == true) {
                evaluateServersAndMaybeSwitch(autoSwitch = true)
                delay(12_000)
            }
        }
    }

    private fun stopPingLoop() {
        pingLoopJob?.cancel()
        pingLoopJob = null
    }

    private fun parsePingMillis(result: String?): Long? {
        if (result.isNullOrBlank()) return null
        val lower = result.lowercase()
        if (lower.contains("fail") || lower.contains("unavailable") || lower.contains("error")) {
            return null
        }
        return Regex("(\\d+)\\s*ms", RegexOption.IGNORE_CASE)
            .find(result)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private fun isPingErrorText(result: String?): Boolean {
        if (result.isNullOrBlank()) return true
        val lower = result.lowercase()
        return lower.contains("fail") || lower.contains("error") || lower.contains("unavailable")
    }

    private fun updateProcessState(message: String) {
        binding.tvProcessState.text = message
        updateConfigCountBadge()
    }

    private fun updateConfigCountBadge() {
        binding.tvConfigCountBadge.text = "Configs: ${availableServerCount()}"
    }

    private fun updateConnectionStateText(isConnected: Boolean) {
        val pingText = lastPingText?.takeIf { it.isNotBlank() } ?: getString(R.string.neon_ping_unknown)
        binding.tvConnectionState.text = if (isConnected) {
            getString(R.string.neon_connected_with_ping, pingText)
        } else {
            getString(R.string.neon_disconnected)
        }
    }

    private fun startConnectPulse() {
        stopConnectPulse()
        connectPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.btnConnect,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f, 1f)
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun startButtonPulse(button: MaterialButton): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            button,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f, 1f)
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopButtonPulse(button: MaterialButton, animator: ObjectAnimator?) {
        animator?.cancel()
        button.scaleX = 1f
        button.scaleY = 1f
        button.isEnabled = true
    }

    private fun setGiveConfigsLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.btnGiveConfigs.isEnabled = false
            binding.pbGiveProgress.isVisible = true
            giveConfigsPulseAnimator?.cancel()
            giveConfigsPulseAnimator = startButtonPulse(binding.btnGiveConfigs)
        } else {
            stopButtonPulse(binding.btnGiveConfigs, giveConfigsPulseAnimator)
            giveConfigsPulseAnimator = null
            binding.pbGiveProgress.isVisible = false
            binding.pbGiveProgress.progress = 0
        }
    }

    private fun setOptimizeLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.btnOptimize.isEnabled = false
            binding.pbOptimizeProgress.isVisible = true
            optimizePulseAnimator?.cancel()
            optimizePulseAnimator = startButtonPulse(binding.btnOptimize)
        } else {
            stopButtonPulse(binding.btnOptimize, optimizePulseAnimator)
            optimizePulseAnimator = null
            binding.pbOptimizeProgress.isVisible = false
            binding.pbOptimizeProgress.progress = 0
        }
    }

    private fun updateGiveProgress(percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (!binding.pbGiveProgress.isVisible) {
            binding.pbGiveProgress.isVisible = true
        }
        binding.pbGiveProgress.setProgressCompat(value, true)
    }

    private fun updateOptimizeProgress(percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (!binding.pbOptimizeProgress.isVisible) {
            binding.pbOptimizeProgress.isVisible = true
        }
        binding.pbOptimizeProgress.setProgressCompat(value, true)
    }

    private fun stopConnectPulse() {
        connectPulseAnimator?.cancel()
        connectPulseAnimator = null
        binding.btnConnect.scaleX = 1f
        binding.btnConnect.scaleY = 1f
    }

    private fun startScanlineEffect() {
        scanlineAnimator = ObjectAnimator.ofFloat(binding.scanlineOverlay, "translationY", -120f, 120f).apply {
            duration = 2200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            start()
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.sub_setting -> requestSubSettingActivity.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestSubSettingActivity.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> startActivity(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra("isRunning", mainViewModel.isRunning.value == true)
            )

            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}