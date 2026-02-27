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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
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
    private var autoOptimizeJob: Job? = null
    private var lastPingText: String? = null
    private var lastPingMillis: Long? = null
    private var lastPingUpdateAtMillis: Long = 0L
    private var lastAutoOptimizeAtMillis: Long = 0L
    private var pendingConnectAttempt = false
    private var isGiveConfigsRunning = false
    private var isOptimizeRunning = false

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

        binding.btnConnect.setOnClickListener {
            toggleConnect()
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
                mainViewModel.testCurrentServerRealPing()
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
        }
        mainViewModel.updateTestResultAction.observe(this) {
            lastPingText = it
            lastPingMillis = parsePingMillis(it)
            lastPingUpdateAtMillis = System.currentTimeMillis()
            val displayText = if (mainViewModel.isRunning.value == true && isPingErrorText(it)) {
                getString(R.string.neon_ping_unavailable_short)
            } else {
                it
            }
            setTestState(displayText)
            updateConnectionStateText(mainViewModel.isRunning.value == true)
            maybeTriggerAutoOptimize("ping-update")
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
                lastPingUpdateAtMillis = System.currentTimeMillis()
                updateProcessState(getString(R.string.neon_connected))
                if (mainViewModel.isRunning.value == true) {
                    startPingLoop()
                }
            } else {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                autoOptimizeJob?.cancel()
                autoOptimizeJob = null
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
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        stopPingLoop()
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
            if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, "1") != "0") {
                toast(getString(R.string.pre_resolving_domain))
            }
            mainViewModel.createIntelligentSelectionAll()
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
        updateProcessState(getString(R.string.neon_idle))
    }

    private fun toggleConnect() {
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
            autoOptimizeJob?.cancel()
            autoOptimizeJob = null
            pendingConnectAttempt = false
            stopConnectPulse()
            binding.pbConnect.isVisible = false
            V2RayServiceManager.stopVService(this)
            updateProcessState(getString(R.string.neon_disconnected))
            return
        }

        val switchedFromIntelligent = switchFromIntelligentSelectionIfNeeded()
        if (switchedFromIntelligent) {
            mainViewModel.reloadServerList()
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
                    MmkvManager.removeAllServer()
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(8) }

                    val fetched = HttpUtil.getUrlContentWithUserAgent(fixedSubscriptionUrl, null)
                    val normalized = normalizeConfigRows(fetched)
                    if (normalized.isBlank()) {
                        return@withContext false
                    }
                    runOnUiThread { updateGiveProgress(20) }

                    AngConfigManager.importBatchConfig(normalized, "", true)
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(35) }

                    withContext(Dispatchers.Main) {
                        updateProcessState(getString(R.string.neon_checking_configs))
                    }
                    mainViewModel.removeDuplicateServer()
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(45) }

                    withContext(Dispatchers.Main) {
                        updateProcessState(getString(R.string.neon_building_intelligent))
                    }
                    val removedCount = removeSlowAndInvalidServers { done, total ->
                        if (total > 0) {
                            val percent = 45 + (done * 35 / total)
                            runOnUiThread { updateGiveProgress(percent) }
                        }
                    }
                    mainViewModel.sortByTestResults()
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(85) }

                    val key = AngConfigManager.createIntelligentSelection(
                        this@MainActivity,
                        mainViewModel.serversCache.map { it.guid },
                        ""
                    )
                    val bestGuid = findBestDirectServerGuid(excludeGuid = key)
                    if (!bestGuid.isNullOrBlank()) {
                        MmkvManager.setSelectServer(bestGuid)
                    } else if (!key.isNullOrBlank()) {
                        MmkvManager.setSelectServer(key)
                    }
                    mainViewModel.reloadServerList()
                    runOnUiThread { updateGiveProgress(100) }
                    (!bestGuid.isNullOrBlank() || !key.isNullOrBlank()) && removedCount >= 0
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
            updateProcessState(getString(R.string.neon_ready_configs))
        } else {
            updateProcessState(getString(R.string.neon_failed_configs))
            toastError(R.string.toast_failure)
        }
    }

    private suspend fun processOptimizeConfigs(autoTriggered: Boolean = false): Boolean {
        if (isOptimizeRunning) return false
        isOptimizeRunning = true
        if (!autoTriggered) {
            setOptimizeLoading(true)
            updateOptimizeProgress(0)
            binding.pbWaiting.show()
        }
        updateProcessState(getString(R.string.neon_optimizing))

        val success = try {
            withContext(Dispatchers.IO) {
                try {
                    removePreviousIntelligentConfigs()
                    mainViewModel.reloadServerList()
                    if (!autoTriggered) {
                        runOnUiThread { updateOptimizeProgress(15) }
                    }
                    removeSlowAndInvalidServers { done, total ->
                        if (!autoTriggered && total > 0) {
                            val percent = 15 + (done * 60 / total)
                            runOnUiThread { updateOptimizeProgress(percent) }
                        }
                    }
                    mainViewModel.sortByTestResults()
                    mainViewModel.reloadServerList()
                    if (!autoTriggered) {
                        runOnUiThread { updateOptimizeProgress(82) }
                    }

                    val key = AngConfigManager.createIntelligentSelection(
                        this@MainActivity,
                        mainViewModel.serversCache.map { it.guid },
                        ""
                    )
                    val bestGuid = findBestDirectServerGuid(excludeGuid = key)
                    if (!bestGuid.isNullOrBlank()) {
                        MmkvManager.setSelectServer(bestGuid)
                    } else if (!key.isNullOrBlank()) {
                        MmkvManager.setSelectServer(key)
                    }
                    mainViewModel.reloadServerList()
                    if (!autoTriggered) {
                        runOnUiThread { updateOptimizeProgress(100) }
                    }
                    !bestGuid.isNullOrBlank() || !key.isNullOrBlank()
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
            updateProcessState(getString(R.string.neon_optimized_ready))
        } else {
            updateProcessState(getString(R.string.neon_failed_configs))
            if (!autoTriggered) {
                toastError(R.string.toast_failure)
            }
        }
        return success
    }

    private suspend fun removeSlowAndInvalidServers(): Int {
        return removeSlowAndInvalidServers(null)
    }

    private suspend fun removeSlowAndInvalidServers(onProgress: ((done: Int, total: Int) -> Unit)?): Int {
        mainViewModel.reloadServerList()
        val candidates = mainViewModel.serversCache
            .filter { it.profile.configType != EConfigType.CUSTOM }
            .toList()

        val removeList = mutableListOf<String>()
        val measuredDelay = mutableMapOf<String, Long>()
        val processedCount = AtomicInteger(0)
        val limiter = Semaphore(24)
        coroutineScope {
            candidates.map { item ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        val delay = measureRealDelayForGuid(item.guid)
                        MmkvManager.encodeServerTestDelayMillis(item.guid, delay)
                        synchronized(measuredDelay) {
                            measuredDelay[item.guid] = delay
                        }
                        if (delay < 0L || delay > 600L) {
                            synchronized(removeList) {
                                removeList.add(item.guid)
                            }
                        }
                        val done = processedCount.incrementAndGet()
                        onProgress?.invoke(done, candidates.size)
                    }
                }
            }.awaitAll()
        }

        if (removeList.size >= candidates.size && candidates.isNotEmpty()) {
            val keepGuid = measuredDelay
                .filterValues { it > 0L }
                .minByOrNull { it.value }
                ?.key
                ?: candidates.first().guid
            removeList.remove(keepGuid)
        }

        removeList.forEach { MmkvManager.removeServer(it) }
        return removeList.size
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

    private fun removePreviousIntelligentConfigs() {
        val marker = getString(R.string.intelligent_selection)
        val allGuids = MmkvManager.decodeServerList().toList()
        allGuids.forEach { guid ->
            val cfg = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            if (cfg.configType == EConfigType.CUSTOM && cfg.remarks.contains(marker)) {
                MmkvManager.removeServer(guid)
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
        val marker = getString(R.string.intelligent_selection)
        return mainViewModel.serversCache
            .asSequence()
            .filter { it.guid != excludeGuid }
            .filter { it.profile.configType != EConfigType.CUSTOM }
            .filterNot { it.profile.remarks.contains(marker, ignoreCase = true) }
            .sortedWith(compareBy({ directServerDelayScore(it.guid) }, { it.profile.remarks }))
            .map { it.guid }
            .firstOrNull()
    }

    private fun switchFromIntelligentSelectionIfNeeded(): Boolean {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (selectedGuid.isBlank()) return false

        val selected = MmkvManager.decodeServerConfig(selectedGuid) ?: return false
        val marker = getString(R.string.intelligent_selection)
        val isIntelligent =
            selected.configType == EConfigType.CUSTOM && selected.remarks.contains(marker, ignoreCase = true)
        if (!isIntelligent) return false

        val fallbackGuid = findBestDirectServerGuid(excludeGuid = selectedGuid) ?: return false
        MmkvManager.setSelectServer(fallbackGuid)
        return true
    }

    private fun directServerDelayScore(guid: String): Long {
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: Long.MAX_VALUE
        return if (delay > 0L) delay else Long.MAX_VALUE
    }

    private fun startPingLoop() {
        stopPingLoop()
        pingLoopJob = lifecycleScope.launch {
            while (isActive && mainViewModel.isRunning.value == true) {
                mainViewModel.testCurrentServerRealPing()
                delay(10_000)
                maybeTriggerAutoOptimize("ping-loop")
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

    private fun maybeTriggerAutoOptimize(trigger: String) {
        if (mainViewModel.isRunning.value != true) return
        if (pendingConnectAttempt) return
        if (isGiveConfigsRunning || isOptimizeRunning) return
        if (autoOptimizeJob?.isActive == true) return

        val now = System.currentTimeMillis()
        if (now - lastAutoOptimizeAtMillis < 20_000) return

        val noPingTooLong = now - lastPingUpdateAtMillis >= 10_000
        val pingTooHigh = (lastPingMillis ?: Long.MAX_VALUE) > 600L
        if (!noPingTooLong && !pingTooHigh) return

        lastAutoOptimizeAtMillis = now
        autoOptimizeJob = lifecycleScope.launch {
            updateProcessState(getString(R.string.neon_auto_optimizing))
            val optimized = processOptimizeConfigs(autoTriggered = true)
            if (optimized && mainViewModel.isRunning.value == true) {
                updateProcessState(getString(R.string.neon_auto_reconnecting))
                restartV2Ray()
            } else if (!optimized) {
                Log.w(AppConfig.TAG, "Auto optimize failed via $trigger")
            }
        }
    }

    private fun updateProcessState(message: String) {
        binding.tvProcessState.text = message
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