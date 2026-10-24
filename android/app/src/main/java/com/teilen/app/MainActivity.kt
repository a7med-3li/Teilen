package com.teilen.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.integration.android.IntentIntegrator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modern 3-tab interface: Send, Feed, and Account.
 * Features an integrated multimodal composer (Text, Files, Photos, Audio),
 * tactile haptics, tabular counters, pre-flight size checks, live route badges,
 * 1-tap blob downloads to public MediaStore.Downloads, in-app ZXing QR scanner,
 * Android 10+ clipboard watcher support, and a sleek 1,400 ms brand launch screen.
 */
class MainActivity : Activity() {

    enum class Tab { SEND, FEED, ACCOUNT }
    enum class SendMode { TEXT, FILES, PHOTOS, AUDIO }

    // TTL values in seconds
    private val ttls = listOf(600L, 1800L, 3600L, 14400L, 86400L)
    private var selectedTtlSeconds = 3600L

    private val main = Handler(Looper.getMainLooper())
    private var currentTab = Tab.SEND
    private var currentSendMode = SendMode.TEXT

    // Selected files for upload
    private val selectedFiles = ArrayList<Uri>()

    // WebSocket Real-Time Sync (Part 3)
    private val okHttpClient = OkHttpClient.Builder().build()
    private var feedWebSocket: WebSocket? = null
    private val wsReconnectHandler = Handler(Looper.getMainLooper())

    // Splash overlay
    private lateinit var splashOverlay: View
    private lateinit var splashLogo: ImageView
    private lateinit var splashTitle: TextView
    private lateinit var splashStatus: TextView

    // Header & Route Badges
    private lateinit var headerRouteBadge: TextView
    private lateinit var headerSessionBadge: TextView

    // Tab 1: Unified Send Composer (4 Modes: Text, Files, Photos, Audio)
    private lateinit var modePillText: TextView
    private lateinit var modePillFiles: TextView
    private lateinit var modePillPhotos: TextView
    private lateinit var modePillAudio: TextView

    private lateinit var composerTextLayout: View
    private lateinit var content: EditText
    private lateinit var btnPasteSend: TextView

    private lateinit var composerFileLayout: View
    private lateinit var filePickerPrompt: View
    private lateinit var pickFile: Button

    private lateinit var fileSelectedCard: View
    private lateinit var filePreviewIcon: ImageView
    private lateinit var filePreviewName: TextView
    private lateinit var filePreviewSize: TextView
    private lateinit var btnChangeFile: Button
    private lateinit var btnRemoveFile: Button

    private lateinit var fileErrorBox: LinearLayout
    private lateinit var fileErrorText: TextView
    private lateinit var fileProgress: ProgressBar
    private lateinit var fileStatus: TextView

    private lateinit var ttlPill10m: TextView
    private lateinit var ttlPill30m: TextView
    private lateinit var ttlPill1h: TextView
    private lateinit var ttlPill4h: TextView
    private lateinit var ttlPill24h: TextView
    private lateinit var ttlPillCustom: TextView
    private lateinit var spinner: Spinner
    private lateinit var send: Button
    private lateinit var status: TextView

    // Multi-Session Spaces
    private lateinit var sessionScrollView: View
    private lateinit var sessionPillContainer: LinearLayout
    private val currentSessions = ArrayList<TeilenApi.SessionInfo>()

    // Tab 2: Feed
    private lateinit var feedList: ListView
    private lateinit var feedEmpty: View
    private lateinit var feedSpinner: ProgressBar
    private lateinit var feedCount: TextView
    private lateinit var feedRefresh: TextView
    private lateinit var feedAdapter: InlineFeedAdapter
    private var feedLoading = false

    // Tab 3: Account
    private lateinit var account: AccountCard
    private lateinit var switchClipboard: Switch
    private lateinit var server: EditText
    private lateinit var saveServer: Button
    private lateinit var btnPrivacyPolicy: Button
    private lateinit var btnAccountDelete: Button
    private lateinit var accountVersionInfo: TextView
    private lateinit var btnCheckUpdates: Button

    // In-App Updates & Versioning
    private lateinit var appUpdateHelper: AppUpdateHelper
    private lateinit var updateBanner: View
    private lateinit var btnRestartUpdate: Button
    private lateinit var cardUpdateRequired: View
    private lateinit var btnUpdateNow: Button

    // Containers & Gating Banners
    private lateinit var pageSend: View
    private lateinit var pageFeed: View
    private lateinit var pageAccount: View

    private lateinit var cardText: View
    private lateinit var cardFeed: View
    private lateinit var cardClipboard: View

    // Bottom Navigation Bar
    private lateinit var navTabSend: LinearLayout
    private lateinit var navTabFeed: LinearLayout
    private lateinit var navTabAccount: LinearLayout

    private lateinit var navIconSend: ImageView
    private lateinit var navIconFeed: ImageView
    private lateinit var navIconAccount: ImageView

    private lateinit var navLabelSend: TextView
    private lateinit var navLabelFeed: TextView
    private lateinit var navLabelAccount: TextView

    // Connectivity monitoring
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                updateRouteBadge()
                ensureAuthenticated(onSuccess = {
                    fetchDevicesAndUpdateBanners()
                })
            }
        }
        override fun onLost(network: Network) {
            main.post { updateRouteBadge() }
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            main.post { updateRouteBadge() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        setupWindowInsets()
        bindViews()
        setupTtlPills()
        setupNavigation()
        setupSendTab()
        setupFeedTab()
        setupAccountTab()
        setupUpdateComponents()
        setupUniversalPairing()

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        account = AccountCard(
            this,
            onScanQr = { startQrScanner() },
            onReplayGuide = { showOnboardingWizard(isReplay = true) },
            onDevicesCountChanged = { count -> updateCompanionBanners(count) }
        ) { applyGating() }
        account.render()

        ensureAuthenticated(onSuccess = {
            fetchDevicesAndUpdateBanners()
            refreshFeed(silent = true)
        })

        takeHandedOverText(intent)
        checkDeepLink(intent)

        if (savedInstanceState == null) {
            startLaunchAnimation()
        } else {
            splashOverlay.visibility = View.GONE
        }
    }

    private fun startLaunchAnimation() {
        splashOverlay.visibility = View.VISIBLE
        splashLogo.alpha = 0f
        splashLogo.scaleX = 0.85f
        splashLogo.scaleY = 0.85f
        splashTitle.alpha = 0f
        splashStatus.alpha = 0f
        splashStatus.text = getString(R.string.launch_status_vault)

        // Phase 1 (0-500ms): Entrance scale and fade-in
        splashLogo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start()
        splashTitle.animate().alpha(1f).setDuration(450).start()
        splashStatus.animate().alpha(1f).setDuration(450).start()

        // Phase 3 (650ms): Micro-status transition to Feed Sync
        main.postDelayed({
            splashStatus.animate().alpha(0f).setDuration(120).withEndAction {
                splashStatus.text = getString(R.string.launch_status_feed)
                splashStatus.animate().alpha(1f).setDuration(120).start()
            }.start()
        }, 650)

        // Phase 4 (1200ms - 1400ms): Cross-fade to main dashboard
        main.postDelayed({
            splashOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                splashOverlay.visibility = View.GONE
                checkFirstLaunchOnboarding()
            }.start()
        }, 1200)
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(R.id.main_root)
        val topBarView = findViewById<View>(R.id.header_bar)
        val contentContainer = findViewById<View>(R.id.page_container)
        val bottomTabContainer = findViewById<View>(R.id.nav_bar)

        val initialTopBarTop = topBarView.paddingTop
        val initialTopBarBottom = topBarView.paddingBottom
        val initialTopBarLeft = topBarView.paddingLeft
        val initialTopBarRight = topBarView.paddingRight

        val initialBottomTabTop = bottomTabContainer.paddingTop
        val initialBottomTabBottom = bottomTabContainer.paddingBottom
        val initialBottomTabLeft = bottomTabContainer.paddingLeft
        val initialBottomTabRight = bottomTabContainer.paddingRight

        val initialContentBottom = contentContainer.paddingBottom
        val initialContentLeft = contentContainer.paddingLeft
        val initialContentRight = contentContainer.paddingRight
        val initialContentTop = contentContainer.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // 1. Pad top header by status bar
            topBarView.updatePadding(
                top = initialTopBarTop + systemBars.top,
                left = initialTopBarLeft + systemBars.left,
                right = initialTopBarRight + systemBars.right,
                bottom = initialTopBarBottom
            )

            // 2. Dynamically pad bottom tabs so 3-button nav never covers them
            bottomTabContainer.updatePadding(
                bottom = initialBottomTabBottom + systemBars.bottom,
                left = initialBottomTabLeft + systemBars.left,
                right = initialBottomTabRight + systemBars.right,
                top = initialBottomTabTop
            )

            // 3. Scrollable content adjustments for IME keyboard
            val imeOffset = maxOf(0, imeInsets.bottom - systemBars.bottom)
            contentContainer.updatePadding(
                bottom = initialContentBottom + imeOffset,
                left = initialContentLeft + systemBars.left,
                right = initialContentRight + systemBars.right,
                top = initialContentTop
            )

            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateHelper.onResumeCheck()
        checkMinimumVersionGate()
        updateRouteBadge()
        registerNetworkCallback()
        updatePasteButtonPreview()
        if (Session.isPaired(this)) {
            fetchDevicesAndUpdateBanners()
            connectFeedWebSocket()
        }
        if (currentTab == Tab.FEED && Session.isPaired(this)) {
            refreshFeed(silent = true)
        }
        main.post(feedTicker)
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
        main.removeCallbacks(feedTicker)
        disconnectFeedWebSocket()
    }

    override fun onDestroy() {
        super.onDestroy()
        appUpdateHelper.stop()
        main.removeCallbacks(feedTicker)
        disconnectFeedWebSocket()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updatePasteButtonPreview()
        }
    }

    private fun updatePasteButtonPreview() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull()
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            val preview = text.replace('\n', ' ').replace('\r', ' ').take(16)
            btnPasteSend.text = getString(
                R.string.quick_paste_with_preview,
                preview + (if (text.length > 16) "…" else "")
            )
        } else {
            btnPasteSend.text = getString(R.string.paste_and_send)
        }
    }

    private fun bindViews() {
        splashOverlay = findViewById(R.id.splash_overlay)
        splashLogo = findViewById(R.id.splash_logo)
        splashTitle = findViewById(R.id.splash_title)
        splashStatus = findViewById(R.id.splash_status)

        headerRouteBadge = findViewById(R.id.header_route_badge)
        headerSessionBadge = findViewById(R.id.header_session_badge)

        modePillText = findViewById(R.id.mode_pill_text)
        modePillFiles = findViewById(R.id.mode_pill_files)
        modePillPhotos = findViewById(R.id.mode_pill_photos)
        modePillAudio = findViewById(R.id.mode_pill_audio)

        composerTextLayout = findViewById(R.id.composer_text_layout)
        content = findViewById(R.id.content)
        btnPasteSend = findViewById(R.id.btn_paste_send)

        composerFileLayout = findViewById(R.id.composer_file_layout)
        filePickerPrompt = findViewById(R.id.file_picker_prompt)
        pickFile = findViewById(R.id.pick_file)

        fileSelectedCard = findViewById(R.id.file_selected_card)
        filePreviewIcon = findViewById(R.id.file_preview_icon)
        filePreviewName = findViewById(R.id.file_preview_name)
        filePreviewSize = findViewById(R.id.file_preview_size)
        btnChangeFile = findViewById(R.id.btn_change_file)
        btnRemoveFile = findViewById(R.id.btn_remove_file)

        fileErrorBox = findViewById(R.id.file_error_box)
        fileErrorText = findViewById(R.id.file_error_text)
        fileProgress = findViewById(R.id.file_progress)
        fileStatus = findViewById(R.id.file_status)

        ttlPill10m = findViewById(R.id.ttl_pill_10m)
        ttlPill30m = findViewById(R.id.ttl_pill_30m)
        ttlPill1h = findViewById(R.id.ttl_pill_1h)
        ttlPill4h = findViewById(R.id.ttl_pill_4h)
        ttlPill24h = findViewById(R.id.ttl_pill_24h)
        ttlPillCustom = findViewById(R.id.ttl_pill_custom)
        sessionScrollView = findViewById(R.id.session_scroll_view)
        sessionPillContainer = findViewById(R.id.session_pill_container)
        spinner = findViewById(R.id.ttl)
        send = findViewById(R.id.send)
        status = findViewById(R.id.status)

        feedList = findViewById(R.id.feed_tab_list)
        feedEmpty = findViewById(R.id.feed_tab_empty)
        feedSpinner = findViewById(R.id.feed_tab_spinner)
        feedCount = findViewById(R.id.feed_tab_count)
        feedRefresh = findViewById(R.id.feed_tab_refresh)

        switchClipboard = findViewById(R.id.switch_clipboard)
        server = findViewById(R.id.server)
        saveServer = findViewById(R.id.save_server)
        btnPrivacyPolicy = findViewById(R.id.btn_privacy_policy)
        btnAccountDelete = findViewById(R.id.btn_account_delete)
        accountVersionInfo = findViewById(R.id.account_version_info)
        btnCheckUpdates = findViewById(R.id.btn_check_updates)

        updateBanner = findViewById(R.id.update_banner)
        btnRestartUpdate = findViewById(R.id.btn_restart_update)
        cardUpdateRequired = findViewById(R.id.card_update_required)
        btnUpdateNow = findViewById(R.id.btn_update_now)

        pageSend = findViewById(R.id.page_send)
        pageFeed = findViewById(R.id.page_feed)
        pageAccount = findViewById(R.id.page_account)

        cardText = findViewById(R.id.card_text)
        cardFeed = findViewById(R.id.card_feed)
        cardClipboard = findViewById(R.id.card_clipboard)

        navTabSend = findViewById(R.id.nav_tab_send)
        navTabFeed = findViewById(R.id.nav_tab_feed)
        navTabAccount = findViewById(R.id.nav_tab_account)

        navIconSend = findViewById(R.id.nav_icon_send)
        navIconFeed = findViewById(R.id.nav_icon_feed)
        navIconAccount = findViewById(R.id.nav_icon_account)

        navLabelSend = findViewById(R.id.nav_label_send)
        navLabelFeed = findViewById(R.id.nav_label_feed)
        navLabelAccount = findViewById(R.id.nav_label_account)
    }

    // ------------------------------------------------------------- Navigation

    private fun setupNavigation() {
        navTabSend.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.SEND)
        }
        navTabFeed.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.FEED)
        }
        navTabAccount.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.ACCOUNT)
        }

        findViewById<Button>(R.id.open_feed).setOnClickListener {
            startActivity(Intent(this, FeedActivity::class.java))
        }

        selectTab(Tab.SEND)
    }

    fun selectTab(tab: Tab) {
        currentTab = tab
        pageSend.visibility = if (tab == Tab.SEND) View.VISIBLE else View.GONE
        pageFeed.visibility = if (tab == Tab.FEED) View.VISIBLE else View.GONE
        pageAccount.visibility = if (tab == Tab.ACCOUNT) View.VISIBLE else View.GONE

        updateTabItem(navTabSend, navIconSend, navLabelSend, tab == Tab.SEND)
        updateTabItem(navTabFeed, navIconFeed, navLabelFeed, tab == Tab.FEED)
        updateTabItem(navTabAccount, navIconAccount, navLabelAccount, tab == Tab.ACCOUNT)

        if (tab == Tab.FEED && Session.isPaired(this)) {
            refreshFeed()
        }
    }

    private fun updateTabItem(container: LinearLayout, icon: ImageView, label: TextView, active: Boolean) {
        val color = getColor(if (active) R.color.nav_item_selected else R.color.nav_item_unselected)
        label.setTextColor(color)
        label.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        icon.imageTintList = ColorStateList.valueOf(color)
    }

    // ------------------------------------------------------------- Route Indicator

    private fun updateRouteBadge() {
        val cm = connectivityManager ?: return
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val isLan = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        if (caps != null && (isLan || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
            headerRouteBadge.visibility = View.VISIBLE
            if (isLan) {
                headerRouteBadge.text = getString(R.string.status_direct_lan)
                headerRouteBadge.setBackgroundResource(R.drawable.bg_status_lan)
                headerRouteBadge.setTextColor(getColor(R.color.lan_active))
            } else {
                headerRouteBadge.text = getString(R.string.status_encrypted_relay)
                headerRouteBadge.setBackgroundResource(R.drawable.bg_status_relay)
                headerRouteBadge.setTextColor(getColor(R.color.accent))
            }
        } else {
            headerRouteBadge.visibility = View.GONE
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback)
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignored if not registered
        }
    }

    // ------------------------------------------------------------- TTL Segmented Selector

    private fun setupTtlPills() {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ttls.map { Server.label(it) }
        )

        ttlPill10m.setOnClickListener { selectTtl(600L, it) }
        ttlPill30m.setOnClickListener { selectTtl(1800L, it) }
        ttlPill1h.setOnClickListener { selectTtl(3600L, it) }
        ttlPill4h.setOnClickListener { selectTtl(14400L, it) }
        ttlPill24h.setOnClickListener { selectTtl(86400L, it) }
        ttlPillCustom.setOnClickListener { showCustomTtlDialog() }

        selectTtl(3600L, ttlPill1h)
    }

    private fun selectTtl(seconds: Long, clickedView: View) {
        performHaptic(clickedView, HapticFeedbackConstants.CLOCK_TICK)
        selectedTtlSeconds = seconds
        val pills = listOf(
            ttlPill10m to 600L,
            ttlPill30m to 1800L,
            ttlPill1h to 3600L,
            ttlPill4h to 14400L,
            ttlPill24h to 86400L
        )
        for ((pill, ttlVal) in pills) {
            val selected = ttlVal == seconds
            pill.setBackgroundResource(
                if (selected) R.drawable.bg_segmented_selected else android.R.color.transparent
            )
            pill.setTextColor(
                getColor(if (selected) R.color.accent else R.color.pill_unselected_text)
            )
            pill.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        ttlPillCustom.setBackgroundResource(android.R.color.transparent)
        ttlPillCustom.setTextColor(getColor(R.color.pill_unselected_text))
        ttlPillCustom.setTypeface(null, Typeface.NORMAL)
        ttlPillCustom.text = "Custom"
    }

    private fun showCustomTtlDialog() {
        performHaptic(ttlPillCustom, HapticFeedbackConstants.CLOCK_TICK)
        val input = EditText(this).apply {
            hint = "Minutes (e.g. 90)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("90")
        }
        val container = FrameLayout(this).apply {
            setPadding(48, 24, 48, 12)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Set Custom Expiration")
            .setMessage("Enter the item lifetime in minutes (e.g. 90 for 1.5 hours):")
            .setView(container)
            .setPositiveButton("Set") { _, _ ->
                val minutes = input.text.toString().toLongOrNull() ?: 90L
                val ttlSec = (minutes * 60L).coerceIn(60L, 30L * 86400L)
                selectCustomTtl(ttlSec)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectCustomTtl(seconds: Long) {
        selectedTtlSeconds = seconds
        val pills = listOf(ttlPill10m, ttlPill30m, ttlPill1h, ttlPill4h, ttlPill24h)
        for (pill in pills) {
            pill.setBackgroundResource(android.R.color.transparent)
            pill.setTextColor(getColor(R.color.pill_unselected_text))
            pill.setTypeface(null, Typeface.NORMAL)
        }
        ttlPillCustom.setBackgroundResource(R.drawable.bg_segmented_selected)
        ttlPillCustom.setTextColor(getColor(R.color.accent))
        ttlPillCustom.setTypeface(null, Typeface.BOLD)
        val mins = seconds / 60
        ttlPillCustom.text = when {
            mins < 60 -> "${mins}m"
            mins < 1440 -> "${mins / 60}h"
            else -> "${mins / 1440}d"
        }
    }

    // ------------------------------------------------------------- Tab 1: Send Composer

    private fun setupSendTab() {
        content.movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        content.setOnTouchListener { v, event ->
            if (v.id == R.id.content) {
                when (event.action and android.view.MotionEvent.ACTION_MASK) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            false
        }

        modePillText.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            setSendMode(SendMode.TEXT)
        }
        modePillFiles.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            setSendMode(SendMode.FILES)
            launchPicker("*/*")
        }
        modePillPhotos.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            setSendMode(SendMode.PHOTOS)
            launchPicker("image/*")
        }
        modePillAudio.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            setSendMode(SendMode.AUDIO)
            launchPicker("audio/*")
        }

        btnPasteSend.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
            setSendMode(SendMode.TEXT)
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                content.setText(text)
                content.setSelection(text.length)
                content.requestFocus()
                Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        pickFile.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            launchCurrentPicker()
        }

        btnChangeFile.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            launchCurrentPicker()
        }

        btnRemoveFile.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            clearSelectedFiles()
        }

        send.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            if (currentSendMode == SendMode.TEXT) {
                submitText()
            } else {
                submitFiles()
            }
        }

        setSendMode(SendMode.TEXT)
    }

    private fun launchCurrentPicker() {
        when (currentSendMode) {
            SendMode.PHOTOS -> launchPicker("image/*")
            SendMode.AUDIO -> launchPicker("audio/*")
            else -> launchPicker("*/*")
        }
    }

    private fun setSendMode(mode: SendMode) {
        currentSendMode = mode
        val modes = listOf(
            modePillText to SendMode.TEXT,
            modePillFiles to SendMode.FILES,
            modePillPhotos to SendMode.PHOTOS,
            modePillAudio to SendMode.AUDIO
        )
        for ((pill, m) in modes) {
            val selected = m == mode
            pill.setBackgroundResource(
                if (selected) R.drawable.bg_segmented_selected else android.R.color.transparent
            )
            pill.setTextColor(
                getColor(if (selected) R.color.accent else R.color.pill_unselected_text)
            )
            pill.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        if (mode == SendMode.TEXT) {
            composerTextLayout.visibility = View.VISIBLE
            composerFileLayout.visibility = View.GONE
        } else {
            composerTextLayout.visibility = View.GONE
            composerFileLayout.visibility = View.VISIBLE
        }
    }

    private fun launchPicker(mimeType: String) {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(open, REQ_OPEN_DOCUMENT)
    }

    @Deprecated("platform activities only; there is no AndroidX result API here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == PortraitCaptureActivity.RESULT_MANUAL_CODE) {
            promptManualPairCode()
            return
        }
        if (requestCode == AppUpdateHelper.REQ_APP_UPDATE) {
            if (resultCode != RESULT_OK) {
                Log.w("MainActivity", "Update flow failed or canceled by user: $resultCode")
            }
            return
        }
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            val content = scanResult.contents
            if (content != null) {
                if (content.startsWith("teilen://session/join") || (content.startsWith("teilen://session") && content.contains("code="))) {
                    handleSessionJoinUri(Uri.parse(content))
                } else {
                    val code = PairActivity.cleanCode(content)
                    if (code != null) {
                        pairWithScannedCode(code)
                    } else {
                        account.handleApproveCode(content)
                    }
                }
            }
            return
        }
        if (requestCode != REQ_OPEN_DOCUMENT || resultCode != RESULT_OK || data == null) {
            return
        }
        val picked = pickedUris(data)
        if (picked.isEmpty()) return

        // Tiered Size Policy: files <= 30 MB dual-path; files > 30 MB Direct LAN Only
        val oversized = picked.mapNotNull { uri ->
            val size = TeilenApi.declaredSize(this, uri)
            val name = TeilenApi.displayName(this, uri)
            if (size != null && size > TeilenApi.MAX_UPLOAD_BYTES) {
                name to size
            } else null
        }

        if (oversized.isNotEmpty()) {
            val (name, size) = oversized.first()
            fileErrorBox.visibility = View.VISIBLE
            fileErrorText.text = "⚡ Direct LAN Only · $name (${TeilenApi.humanSize(size)}) will stream peer-to-peer without server storage."
            fileErrorText.setTextColor(getColor(R.color.lan_active))
        } else {
            fileErrorBox.visibility = View.GONE
        }

        picked.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        selectedFiles.clear()
        selectedFiles.addAll(picked)

        // Show file selected card
        filePickerPrompt.visibility = View.GONE
        fileSelectedCard.visibility = View.VISIBLE

        val firstUri = picked.first()
        val firstName = TeilenApi.displayName(this, firstUri)
        val totalSize = picked.sumOf { TeilenApi.declaredSize(this, it) ?: 0L }
        filePreviewName.text = if (picked.size > 1) {
            getString(R.string.share_files_sent, picked.size) + " · $firstName"
        } else {
            firstName
        }
        filePreviewSize.text = if (totalSize > 0) TeilenApi.humanSize(totalSize) else "Ready"
        fileStatus.visibility = View.GONE
        fileProgress.visibility = View.GONE

        // Theme-matched vector icon based on current send mode and MIME type
        val mime = firstUri.let { contentResolver.getType(it) } ?: ""
        when {
            currentSendMode == SendMode.PHOTOS || mime.startsWith("image/") -> {
                filePreviewIcon.setImageResource(R.drawable.ic_image)
            }
            currentSendMode == SendMode.AUDIO || mime.startsWith("audio/") -> {
                filePreviewIcon.setImageResource(R.drawable.ic_audio)
            }
            else -> {
                filePreviewIcon.setImageResource(R.drawable.ic_file)
            }
        }
        filePreviewIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
    }

    private fun clearSelectedFiles() {
        selectedFiles.clear()
        fileSelectedCard.visibility = View.GONE
        filePickerPrompt.visibility = View.VISIBLE
        fileErrorBox.visibility = View.GONE
        fileProgress.visibility = View.GONE
        fileStatus.visibility = View.GONE
        setSendMode(SendMode.TEXT)
    }

    private fun pickedUris(data: Intent): List<Uri> {
        val single = data.data
        val many = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        return many?.filterNotNull() ?: listOfNotNull(single)
    }

    private fun submitText() {
        val text = content.text.toString().trim()
        if (text.isEmpty()) {
            status.text = getString(R.string.nothing_to_send)
            return
        }

        val url = Server.normalize(server.text.toString())
        Server.set(this, url)

        val isUrl = text.startsWith("http://", ignoreCase = true) ||
                    text.startsWith("https://", ignoreCase = true) ||
                    android.util.Patterns.WEB_URL.matcher(text).matches()
        val itemType = if (isUrl) "LINK" else "TEXT"
        val activeSession = Session.activeSessionId(this)

        setBusy(true)
        ensureAuthenticated(onSuccess = { token ->
            TeilenApi.send(url, token, text, selectedTtlSeconds, itemType, activeSession) { ok, message ->
                setBusy(false)
                when {
                    ok -> {
                        content.setText("")
                        status.text = getString(R.string.sent, message)
                        Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
                        if (currentTab == Tab.FEED) refreshFeed()
                    }
                    message == TeilenApi.UNPAIRED_MESSAGE -> {
                        Session.clear(this)
                        account.render()
                        applyGating()
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        status.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, onError = { err ->
            setBusy(false)
            status.text = err
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        })
    }

    private fun submitFiles() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, R.string.select_file_first, Toast.LENGTH_SHORT).show()
            launchCurrentPicker()
            return
        }

        val url = Server.normalize(server.text.toString())
        Server.set(this, url)
        val activeSession = Session.activeSessionId(this)

        // Tiered Transfer: Single file > 30 MB is Direct LAN Only
        val firstUri = selectedFiles.first()
        val declared = TeilenApi.declaredSize(this, firstUri) ?: 0L
        if (selectedFiles.size == 1 && declared > TeilenApi.MAX_UPLOAD_BYTES) {
            val name = TeilenApi.displayName(this, firstUri)
            val mime = contentResolver.getType(firstUri) ?: "application/octet-stream"

            setBusy(true)
            showFileStatus("⚡ Direct LAN P2P: Connecting to peer...", false)
            fileProgress.visibility = View.VISIBLE
            fileProgress.progress = 0

            val engine = LanTransferEngine(this)
            val payload = LanTransferEngine.Payload(name, mime, declared) {
                contentResolver.openInputStream(firstUri) ?: throw java.io.FileNotFoundException("cannot open $name")
            }
            engine.attempt(
                payload = payload,
                onProgress = { written, total ->
                    val pct = if (total > 0) ((written * 100) / total).toInt() else 0
                    fileProgress.progress = pct
                    showFileStatus("⚡ Direct LAN P2P · ${TeilenApi.humanSize(written)} / ${TeilenApi.humanSize(total)} ($pct%)", false)
                }
            ) { sent ->
                setBusy(false)
                fileProgress.visibility = View.GONE
                if (sent) {
                    showFileStatus(getString(R.string.file_sent, name) + " (⚡ Direct LAN P2P)", false)
                    Toast.makeText(this, "Transferred via Direct LAN (0 server bytes used)", Toast.LENGTH_SHORT).show()
                    clearSelectedFiles()
                    if (currentTab == Tab.FEED) refreshFeed()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Direct LAN Peer Unavailable")
                        .setMessage("Files over 30 MB transfer directly device-to-device for free without server storage. Make sure the receiving device is open on the same Wi-Fi/Hotspot.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    showFileStatus("Direct LAN peer unreachable. Open receiver on same Wi-Fi.", true)
                }
            }
            return
        }

        setBusy(true)
        showFileStatus(getString(R.string.uploading, TeilenApi.displayName(this, selectedFiles.first())), false)
        fileProgress.visibility = View.VISIBLE
        fileProgress.progress = 0

        ensureAuthenticated(onSuccess = { token ->
            TeilenApi.uploadAll(
                context = this,
                baseUrl = url,
                token = token,
                uris = ArrayList(selectedFiles),
                ttlSeconds = selectedTtlSeconds,
                sessionId = activeSession,
                onProgress = { index, count, written, total ->
                    if (total != null && total > 0) {
                        fileProgress.progress = ((written * 100) / total).toInt()
                        showFileStatus(
                            getString(
                                R.string.uploading_n,
                                count,
                                index,
                                count
                            ) + " · " + getString(
                                R.string.uploading_bytes,
                                TeilenApi.humanSize(written)
                            ),
                            false
                        )
                    } else if (written > 0) {
                        showFileStatus(
                            getString(R.string.uploading_bytes, TeilenApi.humanSize(written)),
                            false
                        )
                    }
                },
                onDone = { outcome ->
                    setBusy(false)
                    fileProgress.visibility = View.GONE
                    if (outcome.lastError == TeilenApi.UNPAIRED_MESSAGE) {
                        Session.clear(this)
                        account.render()
                        applyGating()
                    }
                    if (outcome.failed == 0) {
                        val message = if (outcome.sent == 1) {
                            getString(R.string.file_sent, selectedFiles.first().let { TeilenApi.displayName(this, it) })
                        } else {
                            getString(R.string.files_sent, outcome.sent)
                        }
                        showFileStatus(message, false)
                        Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
                        clearSelectedFiles()
                        if (currentTab == Tab.FEED) refreshFeed()
                    } else {
                        showFileStatus(getString(R.string.file_failed, outcome.lastError ?: ""), true)
                        performErrorHaptic(fileStatus)
                    }
                }
            )
        }, onError = { err ->
            setBusy(false)
            fileProgress.visibility = View.GONE
            showFileStatus(err, true)
            performErrorHaptic(fileStatus)
        })
    }

    private fun showFileStatus(text: String, isError: Boolean) {
        fileStatus.visibility = View.VISIBLE
        fileStatus.text = text
        fileStatus.setTextColor(
            getColor(if (isError) R.color.danger else R.color.text_secondary)
        )
    }

    // ------------------------------------------------------------- Tab 2: Feed

    private fun setupFeedTab() {
        feedAdapter = InlineFeedAdapter()
        feedList.adapter = feedAdapter
        feedList.setOnItemClickListener { _, _, position, _ ->
            openItem(feedAdapter.at(position))
        }

        feedRefresh.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            refreshFeed()
        }
    }

    private val feedTicker = object : Runnable {
        override fun run() {
            if (currentTab == Tab.FEED && Session.isPaired(this@MainActivity)) {
                refreshFeed(silent = true)
            }
            main.postDelayed(this, FEED_POLL_MS)
        }
    }

    fun refreshFeed(silent: Boolean = false) {
        if (feedLoading || !Session.isPaired(this)) return
        feedLoading = true
        if (!silent && feedAdapter.count == 0) {
            feedSpinner.visibility = View.VISIBLE
        }
        val activeSession = Session.activeSessionId(this)
        TeilenApi.fetchItems(Server.get(this), Session.token(this), sessionId = activeSession) { items, error ->
            feedLoading = false
            feedSpinner.visibility = View.GONE
            if (error != null) {
                if (error == TeilenApi.UNPAIRED_MESSAGE) {
                    Session.clear(this)
                    account.render()
                    applyGating()
                } else if (!silent) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
                return@fetchItems
            }
            val rows = items ?: return@fetchItems
            feedAdapter.replace(rows)
            feedEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            feedCount.text = getString(R.string.feed_count, rows.size)
        }
    }

    private fun openItem(item: FeedItem) {
        val url = item.blobUrl(Server.get(this))
        when {
            url != null && item.isImage -> startActivity(
                Intent(this, ImageViewActivity::class.java)
                    .putExtra(ImageViewActivity.EXTRA_URL, url)
                    .putExtra(ImageViewActivity.EXTRA_TITLE, item.content)
            )
            url != null -> handToViewer(url, item.mimeType ?: "*/*", item.content)
            item.type == "LINK" -> handToViewer(item.content.trim(), "*/*", item.content)
            else -> showText(item)
        }
    }

    private fun handToViewer(url: String, mimeType: String, label: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.feed_no_viewer, label), Toast.LENGTH_LONG).show()
        }
    }

    private fun showText(item: FeedItem) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(item.content)
            .setPositiveButton(android.R.string.ok, null)
        if (item.type == "LINK") {
            dialog.setNeutralButton(R.string.feed_open_link) { _, _ ->
                handToViewer(item.content.trim(), "*/*", item.content)
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------- Tab 3: Account & Compliance

    private fun setupAccountTab() {
        server.setText(Server.get(this))
        saveServer.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            val url = Server.normalize(server.text.toString())
            if (url.isBlank()) {
                status.text = getString(R.string.server_blank)
            } else {
                Server.set(this, url)
                server.setText(url)
                status.text = getString(R.string.server_saved, url)
                Toast.makeText(this, getString(R.string.server_saved, url), Toast.LENGTH_SHORT).show()
            }
        }

        switchClipboard.isChecked = ClipboardWatchService.isWatching(this)
        switchClipboard.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            performHaptic(button, HapticFeedbackConstants.CLOCK_TICK)
            if (checked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                } else {
                    setWatching(true)
                    ClipboardWatchService.start(this)
                    Toast.makeText(this, R.string.watching_on, Toast.LENGTH_SHORT).show()
                }
            } else {
                setWatching(false)
                ClipboardWatchService.stop(this)
                Toast.makeText(this, R.string.watching_off, Toast.LENGTH_SHORT).show()
            }
        }

        btnPrivacyPolicy.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy)
                .setMessage(
                    "Teilen is designed for private, ephemeral, end-to-end encrypted sharing.\n\n" +
                    "• Zero Tracking: No advertising IDs, no third-party telemetry, no analytics SDKs.\n" +
                    "• End-to-End Encryption: Files and texts are encrypted on-device with libsodium (XChaCha20-Poly1305) before transmission.\n" +
                    "• Ephemeral Storage: All transferred items automatically expire and are wiped after the chosen TTL.\n" +
                    "• Direct LAN: Transfers prioritize local peer-to-peer data channels when available."
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        btnAccountDelete.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            AlertDialog.Builder(this)
                .setTitle(R.string.account_delete)
                .setMessage("Do you want to sign out and wipe all local keys, credentials, and cached tokens from this device?")
                .setPositiveButton("Wipe & Sign Out") { _, _ ->
                    Session.clear(this)
                    account.render()
                    applyGating()
                    Toast.makeText(this, "Local session and keys wiped", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        accountVersionInfo.text = getString(
            R.string.app_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        btnCheckUpdates.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            Toast.makeText(this, R.string.checking_for_updates, Toast.LENGTH_SHORT).show()
            appUpdateHelper.checkForUpdates(isManual = true) { isLatest ->
                if (isLatest) {
                    Toast.makeText(this, R.string.app_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------- In-App Updates & Versioning

    private fun setupUpdateComponents() {
        appUpdateHelper = AppUpdateHelper(this) {
            runOnUiThread {
                updateBanner.visibility = View.VISIBLE
            }
        }
        appUpdateHelper.start()

        btnRestartUpdate.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            appUpdateHelper.completeUpdate()
        }

        btnUpdateNow.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            appUpdateHelper.triggerImmediateUpdate {
                openPlayStore()
            }
        }

        // Silent in-app update check on launch
        appUpdateHelper.checkForUpdates(isManual = false)

        // Backend minimum version check
        checkMinimumVersionGate()
    }

    private fun checkMinimumVersionGate() {
        TeilenApi.fetchVersion(Server.get(this)) { info ->
            if (info != null && BuildConfig.VERSION_CODE < info.minAndroidVersionCode) {
                cardUpdateRequired.visibility = View.VISIBLE
            }
        }
    }

    private fun openPlayStore() {
        val pkg = packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun setWatching(watching: Boolean) {
        ClipboardWatchService.setWatching(this, watching)
        switchClipboard.isChecked = watching
        if (!watching) {
            ClipboardWatchService.stop(this)
        }
    }

    private fun startQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val integrator = IntentIntegrator(this)
        integrator.setCaptureActivity(PortraitCaptureActivity::class.java)
        integrator.setOrientationLocked(true)
        integrator.setBeepEnabled(false)
        integrator.setPrompt("")
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATIONS -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    setWatching(true)
                    ClipboardWatchService.start(this)
                    Toast.makeText(this, R.string.watching_on, Toast.LENGTH_SHORT).show()
                } else {
                    setWatching(false)
                    Toast.makeText(this, R.string.notifications_needed, Toast.LENGTH_LONG).show()
                }
            }
            REQ_CAMERA -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startQrScanner()
                } else {
                    Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STOP_WATCHING, false)) {
            setWatching(false)
        }
        takeHandedOverText(intent)
        checkDeepLink(intent)
    }

    private fun takeHandedOverText(intent: Intent?) {
        val handedOver = intent?.getStringExtra(EXTRA_TEXT) ?: return
        intent.removeExtra(EXTRA_TEXT)
        setSendMode(SendMode.TEXT)
        content.setText(handedOver)
        selectTab(Tab.SEND)
        if (!Session.isPaired(this)) {
            status.text = getString(R.string.pair_then_send)
        }
    }

    private fun setBusy(busy: Boolean) {
        send.isEnabled = !busy
        send.text = getString(if (busy) R.string.sending else R.string.encrypt_and_send)
        pickFile.isEnabled = !busy
        btnChangeFile.isEnabled = !busy
        btnRemoveFile.isEnabled = !busy
    }

    private fun applyGating() {
        val paired = Session.isPaired(this)

        cardText.visibility = View.VISIBLE
        cardFeed.visibility = View.VISIBLE
        cardClipboard.visibility = View.VISIBLE

        sessionScrollView.visibility = if (paired) View.VISIBLE else View.GONE
        if (paired) {
            loadSessions()
        }

        headerSessionBadge.visibility = if (paired) View.VISIBLE else View.GONE
        if (paired) {
            headerSessionBadge.text = Session.phone(this) ?: getString(R.string.signed_in)
        }

        if (paired) {
            status.text = if (content.text.isNullOrBlank()) {
                getString(R.string.ready, Server.get(this))
            } else {
                getString(R.string.sent_when_paired)
            }
            fetchDevicesAndUpdateBanners()
        }
    }

    private fun performHaptic(view: View, constant: Int) {
        view.performHapticFeedback(constant)
    }

    private fun performErrorHaptic(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // ------------------------------------------------------------- Zero-Auth Provisioning & Onboarding

    private var isProvisioningVault = false

    private fun randomAlphanumeric(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val bytes = PairingCrypto.randomBytes(length)
        return bytes.map { chars[(it.toInt() and 0xFF) % chars.length] }.joinToString("")
    }

    fun ensureAuthenticated(onSuccess: (String) -> Unit, onError: ((String) -> Unit)? = null) {
        val existing = Session.token(this)
        if (!existing.isNullOrBlank()) {
            onSuccess(existing)
            return
        }
        provisionSilentVault(
            onSuccess = onSuccess,
            onError = onError
        )
    }

    private fun provisionSilentVault(onSuccess: ((String) -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val existing = Session.token(this)
        if (!existing.isNullOrBlank()) {
            onSuccess?.invoke(existing)
            return
        }
        if (isProvisioningVault) {
            main.postDelayed({
                val token = Session.token(this)
                if (!token.isNullOrBlank()) onSuccess?.invoke(token)
                else onError?.invoke("Vault provisioning in progress")
            }, 500)
            return
        }
        isProvisioningVault = true

        // 1. Ensure 256-bit K_master and Ed25519 identity keypair
        SecureKeyManager.ensureMasterKey(this)
        SecureKeyManager.ensureIdentity(this)

        // 2. Random anonymous workspace handle & cryptographic password (alphanumeric, no hyphens!)
        val randomChars = randomAlphanumeric(10).lowercase()
        val handle = "vault$randomChars"
        val password = randomAlphanumeric(24)
        val devName = listOf(Build.MANUFACTURER, Build.MODEL)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android Device" }

        TeilenApi.createAccount(
            baseUrl = Server.get(this),
            phone = handle,
            password = password,
            displayName = "Personal Vault",
            deviceName = devName,
            clientFingerprint = Session.clientFingerprint(this)
        ) { result ->
            isProvisioningVault = false
            when (result) {
                is TeilenApi.CreateResult.Created -> {
                    Session.save(this, result.signedIn.token, handle, devName, result.signedIn.userId, result.signedIn.deviceId)
                    account.render()
                    applyGating()
                    fetchDevicesAndUpdateBanners()
                    connectFeedWebSocket()
                    onSuccess?.invoke(result.signedIn.token)
                }
                is TeilenApi.CreateResult.Taken -> {
                    // Retry once with a new random handle
                    provisionSilentVault(onSuccess, onError)
                }
                is TeilenApi.CreateResult.Failed -> {
                    Log.w("MainActivity", "Silent provisioning failed: ${result.message}")
                    Toast.makeText(this, "Silent vault setup error: ${result.message}", Toast.LENGTH_LONG).show()
                    onError?.invoke(result.message)
                }
            }
        }
    }

    private fun checkFirstLaunchOnboarding() {
        val prefs = getSharedPreferences("teilen", MODE_PRIVATE)
        val completed = prefs.getBoolean("has_completed_onboarding", false)
        if (!completed) {
            showOnboardingWizard(isReplay = false)
        }
    }

    private var currentOnboardingSlide = 1

    private fun showOnboardingWizard(isReplay: Boolean) {
        val overlay = findViewById<View>(R.id.onboarding_overlay) ?: return
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(250).start()

        val btnBack = overlay.findViewById<View>(R.id.onboarding_btn_back)
        val btnSkip = overlay.findViewById<View>(R.id.onboarding_btn_skip)
        val btnNext = overlay.findViewById<View>(R.id.onboarding_btn_next)
        val btnEnter = overlay.findViewById<View>(R.id.onboarding_btn_enter)

        val dot1 = overlay.findViewById<View>(R.id.onboarding_dot_1)
        val dot2 = overlay.findViewById<View>(R.id.onboarding_dot_2)
        val dot3 = overlay.findViewById<View>(R.id.onboarding_dot_3)

        val slide1 = overlay.findViewById<View>(R.id.onboarding_slide_1)
        val slide2 = overlay.findViewById<View>(R.id.onboarding_slide_2)
        val slide3 = overlay.findViewById<View>(R.id.onboarding_slide_3)

        // Slide 2 controls
        val txtWebUrl = overlay.findViewById<TextView>(R.id.onboarding_web_url)
        val btnCopyUrl = overlay.findViewById<View>(R.id.onboarding_btn_copy_url)
        val btnScanQr = overlay.findViewById<View>(R.id.onboarding_btn_scan_qr)
        val btnEnterCode = overlay.findViewById<View>(R.id.onboarding_btn_enter_code)
        val btnShareLink = overlay.findViewById<View>(R.id.onboarding_btn_share_link)

        txtWebUrl.text = Server.get(this)

        btnCopyUrl.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Teilen Web URL", Server.get(this)))
            Toast.makeText(this, "Web URL copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnScanQr.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            startQrScanner()
        }

        btnEnterCode.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            promptManualPairCode()
        }

        btnShareLink.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, Server.get(this@MainActivity))
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share_link)))
        }

        // Slide 3 controls
        val switchClip = overlay.findViewById<Switch>(R.id.onboarding_switch_clipboard)
        switchClip.isChecked = ClipboardWatchService.isWatching(this)
        switchClip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                } else {
                    setWatching(true)
                    ClipboardWatchService.start(this)
                }
            } else {
                setWatching(false)
                ClipboardWatchService.stop(this)
            }
        }

        fun updateSlideUI(slide: Int) {
            currentOnboardingSlide = slide
            slide1.visibility = if (slide == 1) View.VISIBLE else View.GONE
            slide2.visibility = if (slide == 2) View.VISIBLE else View.GONE
            slide3.visibility = if (slide == 3) View.VISIBLE else View.GONE

            btnBack.visibility = if (slide > 1) View.VISIBLE else View.INVISIBLE

            if (slide == 3) {
                btnNext.visibility = View.GONE
                btnEnter.visibility = View.VISIBLE
            } else {
                btnNext.visibility = View.VISIBLE
                btnEnter.visibility = View.GONE
            }

            fun styleDot(dot: View, active: Boolean) {
                val params = dot.layoutParams
                params.width = if (active) (24 * resources.displayMetrics.density).toInt() else (8 * resources.displayMetrics.density).toInt()
                dot.layoutParams = params
                dot.setBackgroundResource(if (active) R.drawable.bg_segmented_selected else R.drawable.bg_button_outline)
            }
            styleDot(dot1, slide == 1)
            styleDot(dot2, slide == 2)
            styleDot(dot3, slide == 3)
        }

        fun completeOnboarding() {
            getSharedPreferences("teilen", MODE_PRIVATE).edit()
                .putBoolean("has_completed_onboarding", true)
                .apply()
            overlay.animate().alpha(0f).setDuration(250).withEndAction {
                overlay.visibility = View.GONE
                selectTab(Tab.SEND)
            }.start()
        }

        btnBack.setOnClickListener {
            if (currentOnboardingSlide > 1) {
                updateSlideUI(currentOnboardingSlide - 1)
            }
        }

        btnNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (currentOnboardingSlide < 3) {
                updateSlideUI(currentOnboardingSlide + 1)
            }
        }

        btnEnter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            completeOnboarding()
        }

        btnSkip.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            completeOnboarding()
        }

        updateSlideUI(1)
    }

    private fun setupUniversalPairing() {
        findViewById<View>(R.id.header_btn_pair)?.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            showUniversalPairingDialog()
        }
        findViewById<View>(R.id.header_session_selector_pill)?.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            showSessionSelectorDialog()
        }
        findViewById<View>(R.id.header_btn_add_room)?.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            showCreateRoomDialog()
        }
    }

    private fun updateCompanionBanners(deviceCount: Int) {
        // Strict requirement: Never display device count on main screen or inside the Pair button
        findViewById<TextView>(R.id.header_device_count)?.visibility = View.GONE
    }

    fun fetchDevicesAndUpdateBanners() {
        val token = Session.token(this) ?: return
        TeilenApi.getDevices(Server.get(this), token) { devices ->
            val count = devices?.size ?: 1
            main.post {
                updateCompanionBanners(count)
            }
        }
    }

    private fun showUniversalPairingDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_universal_pair, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnClose = dialogView.findViewById<View>(R.id.dialog_pair_close)
        val tabScanQr = dialogView.findViewById<TextView>(R.id.tab_scan_qr)
        val tabMyCode = dialogView.findViewById<TextView>(R.id.tab_my_code)
        val viewScanQr = dialogView.findViewById<View>(R.id.view_scan_qr)
        val viewMyCode = dialogView.findViewById<View>(R.id.view_my_code)

        val btnOpenCam = dialogView.findViewById<View>(R.id.btn_open_camera_scanner)
        val inputCode = dialogView.findViewById<EditText>(R.id.input_manual_pair_code)
        val btnSubmitCode = dialogView.findViewById<View>(R.id.btn_submit_manual_code)
        val statusManual = dialogView.findViewById<TextView>(R.id.status_manual_pair)

        val pairQr = dialogView.findViewById<ImageView>(R.id.dialog_pair_qr)
        val pairSpinner = dialogView.findViewById<View>(R.id.dialog_pair_spinner)
        val pairCode = dialogView.findViewById<TextView>(R.id.dialog_pair_code)
        val btnCopyCode = dialogView.findViewById<View>(R.id.dialog_btn_copy_code)
        val pairStatus = dialogView.findViewById<TextView>(R.id.dialog_pair_status)

        btnClose.setOnClickListener { dialog.dismiss() }

        fun setTab(isScan: Boolean) {
            if (isScan) {
                tabScanQr.setBackgroundResource(R.drawable.bg_segmented_selected)
                tabScanQr.setTextColor(getColor(R.color.accent))
                tabScanQr.setTypeface(null, Typeface.BOLD)

                tabMyCode.background = null
                tabMyCode.setTextColor(getColor(R.color.text_secondary))
                tabMyCode.setTypeface(null, Typeface.NORMAL)

                viewScanQr.visibility = View.VISIBLE
                viewMyCode.visibility = View.GONE
            } else {
                tabMyCode.setBackgroundResource(R.drawable.bg_segmented_selected)
                tabMyCode.setTextColor(getColor(R.color.accent))
                tabMyCode.setTypeface(null, Typeface.BOLD)

                tabScanQr.background = null
                tabScanQr.setTextColor(getColor(R.color.text_secondary))
                tabScanQr.setTypeface(null, Typeface.NORMAL)

                viewScanQr.visibility = View.GONE
                viewMyCode.visibility = View.VISIBLE

                loadMyPairingCode(pairQr, pairSpinner, pairCode, btnCopyCode, pairStatus, dialog)
            }
        }

        tabScanQr.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            setTab(true)
        }
        tabMyCode.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            setTab(false)
        }

        btnOpenCam.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            startQrScanner()
        }

        btnSubmitCode.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            val code = PairActivity.cleanCode(inputCode.text.toString())
            if (code == null) {
                statusManual.visibility = View.VISIBLE
                statusManual.text = getString(R.string.pair_no_code)
                statusManual.setTextColor(getColor(R.color.danger))
                return@setOnClickListener
            }
            statusManual.visibility = View.VISIBLE
            statusManual.text = "Connecting…"
            statusManual.setTextColor(getColor(R.color.text_secondary))

            val token = Session.token(this)
            val devName = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").ifBlank { "Android Device" }
            val mk = SecureKeyManager.getMasterKey(this)
            val mkB64 = if (mk != null) PairingCrypto.b64(mk) else null
            val fp = Session.clientFingerprint(this)

            TeilenApi.pairWithCode(Server.get(this), token ?: "", code, mkB64, devName, fp) { result ->
                result.onSuccess { pairResult ->
                    statusManual.text = "Connected!"
                    Session.save(this, pairResult.token, Session.phone(this), devName, pairResult.userId)
                    if (!pairResult.masterKey.isNullOrBlank()) {
                        val incoming = PairingCrypto.unb64(pairResult.masterKey)
                        val current = SecureKeyManager.getMasterKey(this)
                        if (current != null && !current.contentEquals(incoming)) {
                            SecureKeyManager.addLegacyKey(this, current)
                        }
                        SecureKeyManager.setMasterKey(this, incoming)
                    }
                    dialog.dismiss()
                    Toast.makeText(this, "Device paired successfully!", Toast.LENGTH_SHORT).show()
                    account.render()
                    applyGating()
                    fetchDevicesAndUpdateBanners()
                    refreshFeed()
                    connectFeedWebSocket()
                }.onFailure { err ->
                    statusManual.text = "Pairing failed: ${err.message}"
                    statusManual.setTextColor(getColor(R.color.danger))
                }
            }
        }

        // Bottom Section: Connected Devices (Part 5)
        val devicesTitle = dialogView.findViewById<TextView>(R.id.dialog_devices_title)
        val btnDisconnectOthers = dialogView.findViewById<Button>(R.id.dialog_btn_disconnect_others)
        val devicesContainer = dialogView.findViewById<LinearLayout>(R.id.dialog_devices_container)

        fun loadDevicesList() {
            val token = Session.token(this) ?: return
            TeilenApi.getDevices(Server.get(this), token) { devs ->
                val list = devs ?: emptyList()
                devicesTitle?.text = "CONNECTED DEVICES (${list.size})"
                btnDisconnectOthers?.visibility = if (list.size > 1) View.VISIBLE else View.GONE
                devicesContainer?.removeAllViews()

                for (dev in list) {
                    val row = LayoutInflater.from(this).inflate(R.layout.item_device_row, devicesContainer, false)
                    val nameText = row.findViewById<TextView>(R.id.device_item_name)
                    val currentBadge = row.findViewById<TextView>(R.id.device_item_current_badge)
                    val typeText = row.findViewById<TextView>(R.id.device_item_type)
                    val btnDisconnect = row.findViewById<Button>(R.id.btn_device_disconnect)

                    nameText.text = dev.name
                    typeText.text = dev.deviceType
                    if (dev.current) {
                        currentBadge.visibility = View.VISIBLE
                        btnDisconnect.visibility = View.GONE
                    } else {
                        currentBadge.visibility = View.GONE
                        btnDisconnect.visibility = View.VISIBLE
                        btnDisconnect.setOnClickListener {
                            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
                            btnDisconnect.isEnabled = false
                            TeilenApi.revokeDevice(Server.get(this), token, dev.id) { ok, msg ->
                                if (ok) {
                                    Toast.makeText(this, "Device disconnected", Toast.LENGTH_SHORT).show()
                                    loadDevicesList()
                                } else {
                                    btnDisconnect.isEnabled = true
                                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    devicesContainer?.addView(row)
                }
            }
        }

        btnDisconnectOthers?.setOnClickListener {
            val token = Session.token(this) ?: return@setOnClickListener
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            AlertDialog.Builder(this)
                .setTitle("Disconnect Other Devices")
                .setMessage("Are you sure you want to disconnect all other devices?")
                .setPositiveButton("Disconnect") { _, _ ->
                    TeilenApi.revokeOtherDevices(Server.get(this), token) { ok, msg ->
                        if (ok) {
                            Toast.makeText(this, "All other devices disconnected", Toast.LENGTH_SHORT).show()
                            loadDevicesList()
                        } else {
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadDevicesList()
        dialog.show()
    }

    private fun loadMyPairingCode(
        pairQr: ImageView,
        pairSpinner: View,
        pairCode: TextView,
        btnCopyCode: View,
        pairStatus: TextView,
        dialog: AlertDialog
    ) {
        val url = Server.get(this)
        val devName = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android Device" }

        pairSpinner.visibility = View.VISIBLE
        pairQr.setImageDrawable(null)
        pairCode.text = ""

        val mk = SecureKeyManager.getMasterKey(this)
        val mkB64 = if (mk != null) PairingCrypto.b64(mk) else null
        val fp = Session.clientFingerprint(this)

        ensureAuthenticated(onSuccess = { token ->
            TeilenApi.generateDeviceCode(
                baseUrl = url,
                token = token,
                deviceName = devName,
                clientFingerprint = fp,
                masterKeyPackage = mkB64
            ) { result ->
                if (!dialog.isShowing) return@generateDeviceCode
                result.onSuccess { offer ->
                    pairSpinner.visibility = View.GONE
                    val code = offer.userCode
                    pairCode.text = code
                    pairStatus.text = "Scan or enter this code on the other device"

                    try {
                        val barcodeEncoder = BarcodeEncoder()
                        val bitmap = barcodeEncoder.encodeBitmap(
                            "teilen://pair?code=$code",
                            BarcodeFormat.QR_CODE,
                            400,
                            400
                        )
                        pairQr.setImageBitmap(bitmap)
                    } catch (_: Exception) {}

                    btnCopyCode.setOnClickListener {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Teilen Pairing Code", code))
                        performHaptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
                        Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    pairSpinner.visibility = View.GONE
                    pairStatus.text = "Failed to generate code: ${err.message}"
                }
            }
        }, onError = { err ->
            if (dialog.isShowing) {
                pairSpinner.visibility = View.GONE
                pairStatus.text = err
            }
        })
    }

    fun pairWithScannedCode(code: String) {
        val token = Session.token(this)
        val devName = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").ifBlank { "Android Device" }
        val mk = SecureKeyManager.getMasterKey(this)
        val mkB64 = if (mk != null) PairingCrypto.b64(mk) else null
        val fp = Session.clientFingerprint(this)

        Toast.makeText(this, "Pairing with $code…", Toast.LENGTH_SHORT).show()
        TeilenApi.pairWithCode(Server.get(this), token ?: "", code, mkB64, devName, fp) { result ->
            result.onSuccess { pairResult ->
                Session.save(this, pairResult.token, Session.phone(this), devName, pairResult.userId)
                if (!pairResult.masterKey.isNullOrBlank()) {
                    val incoming = PairingCrypto.unb64(pairResult.masterKey)
                    val current = SecureKeyManager.getMasterKey(this)
                    if (current != null && !current.contentEquals(incoming)) {
                        SecureKeyManager.addLegacyKey(this, current)
                    }
                    SecureKeyManager.setMasterKey(this, incoming)
                }
                Toast.makeText(this, "Device paired successfully!", Toast.LENGTH_SHORT).show()
                account.render()
                applyGating()
                fetchDevicesAndUpdateBanners()
                refreshFeed()
                connectFeedWebSocket()
            }.onFailure { err ->
                Toast.makeText(this, "Pairing failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------- Real-Time WebSocket Sync (Part 3)

    fun connectFeedWebSocket() {
        val token = Session.token(this) ?: return
        val baseUrl = Server.get(this)
        val wsScheme = if (baseUrl.startsWith("https://", ignoreCase = true)) "wss://" else "ws://"
        val hostAndPort = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        var wsUrl = "$wsScheme$hostAndPort/ws?token=${android.net.Uri.encode(token)}"
        val activeSession = Session.activeSessionId(this)
        if (!activeSession.isNullOrBlank()) {
            wsUrl += "&sessionId=${android.net.Uri.encode(activeSession)}"
        }

        disconnectFeedWebSocket()

        try {
            val request = Request.Builder().url(wsUrl).build()
            feedWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    main.post {
                        refreshFeed(silent = true)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    main.post {
                        handleFeedWebSocketMessage(text)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scheduleWebSocketReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scheduleWebSocketReconnect()
                }
            })
        } catch (e: Exception) {
            Log.w("MainActivity", "WebSocket connect error", e)
            scheduleWebSocketReconnect()
        }
    }

    fun disconnectFeedWebSocket() {
        try {
            feedWebSocket?.close(1000, "Normal Closure")
        } catch (_: Exception) {}
        feedWebSocket = null
    }

    private fun scheduleWebSocketReconnect() {
        wsReconnectHandler.removeCallbacksAndMessages(null)
        wsReconnectHandler.postDelayed({
            if (!isDestroyed && !isFinishing && Session.isPaired(this)) {
                connectFeedWebSocket()
            }
        }, 2000L)
    }

    private fun handleFeedWebSocketMessage(jsonStr: String) {
        try {
            val json = org.json.JSONObject(jsonStr)
            val event = json.optString("event")
            when (event) {
                "created" -> {
                    val itemObj = json.optJSONObject("item")
                    if (itemObj != null) {
                        val item = FeedItem.parse(itemObj)
                        if (item != null) {
                            val activeSession = Session.activeSessionId(this)
                            if (activeSession == null || item.sessionId == activeSession) {
                                feedAdapter.insert(item, 0)
                            }
                        } else {
                            refreshFeed(silent = true)
                        }
                    } else {
                        refreshFeed(silent = true)
                    }
                }
                "extended" -> {
                    val itemObj = json.optJSONObject("item")
                    if (itemObj != null) {
                        val item = FeedItem.parse(itemObj)
                        if (item != null) {
                            feedAdapter.update(item)
                        } else {
                            refreshFeed(silent = true)
                        }
                    } else {
                        refreshFeed(silent = true)
                    }
                }
                "deleted" -> {
                    val id = json.optString("id")
                    if (id.isNotBlank()) {
                        feedAdapter.remove(id)
                    }
                }
                "sync_all", "vault_merged" -> {
                    refreshFeed(silent = true)
                    fetchDevicesAndUpdateBanners()
                    loadSessions()
                }
                "kicked" -> {
                    Session.clear(this)
                    disconnectFeedWebSocket()
                    applyGating()
                    Toast.makeText(this, "This device was disconnected", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "WebSocket message parse error", e)
        }
    }

    fun promptManualPairCode() {
        val input = EditText(this).apply {
            hint = getString(R.string.pair_enter_code_hint)
            typeface = android.graphics.Typeface.MONOSPACE
            isAllCaps = true
            letterSpacing = 0.15f
            gravity = android.view.Gravity.CENTER
            setPadding(36, 36, 36, 36)
            filters = arrayOf(android.text.InputFilter.LengthFilter(9))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.scanner_enter_manual_code)
            .setMessage(R.string.pair_manual_code_desc)
            .setView(input)
            .setPositiveButton(R.string.pair_device_button) { _, _ ->
                val code = PairActivity.cleanCode(input.text.toString())
                if (code != null) {
                    pairWithScannedCode(code)
                } else {
                    account.handleApproveCode(input.text.toString().trim())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------- Image Clipboard Copy

    private fun copyImageToClipboard(item: FeedItem) {
        val url = item.blobUrl(Server.get(this)) ?: return
        val rawExt = item.content.substringAfterLast('.', "png").lowercase()
        val ext = if (rawExt in listOf("png", "jpg", "jpeg", "webp", "gif")) rawExt else "png"
        val fileName = "teilen_${item.id}.$ext"

        Toast.makeText(this, "Copying image to clipboard...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val imagesDir = java.io.File(cacheDir, "shared_images").apply { mkdirs() }
                val targetFile = java.io.File(imagesDir, fileName)

                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                if (connection.responseCode in 200..299) {
                    targetFile.outputStream().use { out ->
                        connection.inputStream.use { input ->
                            input.copyTo(out)
                        }
                    }
                }
                connection.disconnect()

                if (targetFile.exists() && targetFile.length() > 0) {
                    val contentUri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        targetFile
                    )
                    main.post {
                        val cm = getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newUri(contentResolver, "Image", contentUri)
                        cm?.setPrimaryClip(clip)
                        performHaptic(window.decorView, HapticFeedbackConstants.CONFIRM)
                        Toast.makeText(this, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    main.post {
                        Toast.makeText(this, "Failed to copy image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(this, "Error copying image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "teilen-copy-image" }.start()
    }

    private fun extendItem(item: FeedItem) {
        val token = Session.token(this) ?: return
        TeilenApi.extendItem(Server.get(this), token, item.id) { ok, msg ->
            if (ok) {
                Toast.makeText(this, "Extended +1 hour", Toast.LENGTH_SHORT).show()
                refreshFeed()
            } else {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteItem(item: FeedItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete item?")
            .setMessage("This item will be deleted immediately.")
            .setPositiveButton("Delete") { _, _ ->
                val token = Session.token(this) ?: return@setPositiveButton
                TeilenApi.deleteItem(Server.get(this), token, item.id) { ok, msg ->
                    if (ok) {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        feedAdapter.remove(item.id)
                    } else {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------- Inline Feed Adapter

    private inner class InlineFeedAdapter : BaseAdapter() {
        private val rows = ArrayList<FeedItem>()
        private val codeModes = HashMap<String, Boolean>()
        private val expandedModes = HashMap<String, Boolean>()

        fun replace(items: List<FeedItem>) {
            rows.clear()
            rows.addAll(items)
            notifyDataSetChanged()
        }

        fun insert(item: FeedItem, position: Int = 0) {
            val existingIndex = rows.indexOfFirst { it.id == item.id }
            if (existingIndex >= 0) {
                rows[existingIndex] = item
            } else {
                val pos = position.coerceIn(0, rows.size)
                rows.add(pos, item)
            }
            notifyDataSetChanged()
        }

        fun update(item: FeedItem) {
            val idx = rows.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                rows[idx] = item
                notifyDataSetChanged()
            }
        }

        fun remove(id: String) {
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) {
                rows.removeAt(idx)
                notifyDataSetChanged()
            }
        }

        fun at(position: Int): FeedItem = rows[position]

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_feed, parent, false)
            val item = rows[position]

            val kindBadge = view.findViewById<TextView>(R.id.feed_kind)
            val metaText = view.findViewById<TextView>(R.id.feed_meta)
            val ring = view.findViewById<CountdownRingView>(R.id.feed_ring)
            val titleText = view.findViewById<TextView>(R.id.feed_title)
            val thumb = view.findViewById<ImageView>(R.id.feed_thumb)

            val btnCopy = view.findViewById<Button>(R.id.feed_action_copy)
            val btnDownload = view.findViewById<ImageButton>(R.id.feed_action_download)
            val btnExtend = view.findViewById<Button>(R.id.feed_action_extend)
            val btnDelete = view.findViewById<ImageButton>(R.id.feed_action_delete)
            val btnMore = view.findViewById<ImageButton>(R.id.feed_action_more)

            kindBadge.text = item.kind.uppercase()
            metaText.text = item.meta()

            // Calculate countdown ring progress
            val now = System.currentTimeMillis()
            val created = item.createdAt?.toEpochMilli() ?: (now - 3600_000L)
            val expires = item.expiresAt?.toEpochMilli() ?: (created + 3600_000L)
            val totalSpan = (expires - created).coerceAtLeast(1L)
            val remaining = (expires - now).coerceAtLeast(0L)
            val ratio = (remaining.toFloat() / totalSpan.toFloat()).coerceIn(0f, 1f)
            ring.progress = ratio

            // Thumbnail for images
            val url = item.blobUrl(Server.get(this@MainActivity))
            if (item.isImage && url != null) {
                thumb.visibility = View.VISIBLE
                BlobPreview.load(thumb, url)
            } else {
                thumb.tag = null
                thumb.setImageDrawable(null)
                thumb.visibility = View.GONE
            }

            // Body content display (untrimmed, full content)
            titleText.text = item.content

            // Code Mode toggle (<>)
            val isCode = codeModes[item.id] == true
            if (item.type == "TEXT" && isCode) {
                titleText.typeface = Typeface.MONOSPACE
                titleText.setBackgroundResource(R.drawable.bg_input)
                titleText.setPadding(16, 12, 16, 12)
            } else {
                titleText.typeface = Typeface.DEFAULT
                titleText.background = null
                titleText.setPadding(0, 0, 0, 0)
            }

            // Expand / Full View toggle
            val isLong = item.type == "TEXT" && (item.content.length > 200 || item.content.lines().size > 4)
            val isExpanded = expandedModes[item.id] == true
            if (isLong && !isExpanded) {
                titleText.maxLines = 6
                titleText.ellipsize = android.text.TextUtils.TruncateAt.END
            } else {
                titleText.maxLines = Integer.MAX_VALUE
                titleText.ellipsize = null
            }

            // Primary 1: Copy Action (Text, Link, Image) with 1-tap feedback
            if (item.isImage || !item.hasBlob) {
                btnCopy.visibility = View.VISIBLE
                btnCopy.text = "Copy"
                btnCopy.setOnClickListener {
                    performHaptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
                    if (item.isImage) {
                        copyImageToClipboard(item)
                    } else {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Teilen", item.content))
                    }
                    btnCopy.text = "✓ Copied"
                    btnCopy.postDelayed({
                        btnCopy.text = "Copy"
                    }, 1500)
                }
            } else {
                btnCopy.visibility = View.GONE
            }

            // Primary 2: Download Action (Files, Images, PDFs, Audio)
            if (item.hasBlob) {
                btnDownload.visibility = View.VISIBLE
                btnDownload.setOnClickListener {
                    performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
                    downloadItem(item)
                }
            } else {
                btnDownload.visibility = View.GONE
            }

            // Header Action: [ +1h ] Extend
            btnExtend?.setOnClickListener {
                performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
                extendItem(item)
            }

            // Header Action: [ ✕ ] Delete
            btnDelete?.setOnClickListener {
                performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
                deleteItem(item)
            }

            // Quick Actions Overflow Menu (⋮)
            btnMore.setOnClickListener { anchor ->
                performHaptic(anchor, HapticFeedbackConstants.CLOCK_TICK)
                val popup = PopupMenu(this@MainActivity, anchor)
                popup.menu.add(0, 1, 0, "+1 Hour Expiration")
                popup.menu.add(0, 2, 1, "Custom Expiration (TTL)...")

                if (item.hasBlob || item.type == "LINK") {
                    popup.menu.add(0, 3, 2, "Open / Share")
                }

                if (item.type == "TEXT") {
                    popup.menu.add(0, 4, 3, if (isCode) "Normal Font View" else "Code View (<>)")
                    if (isLong) {
                        popup.menu.add(0, 5, 4, if (isExpanded) "Fold View" else "Full View")
                    }
                }

                popup.menu.add(0, 6, 5, "Delete")

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            TeilenApi.extendItem(Server.get(this@MainActivity), Session.token(this@MainActivity), item.id) { ok, msg ->
                                if (ok) {
                                    Toast.makeText(this@MainActivity, "Extended +1 hour", Toast.LENGTH_SHORT).show()
                                    refreshFeed()
                                } else {
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        2 -> showItemCustomTtlDialog(item)
                        3 -> openItem(item)
                        4 -> {
                            codeModes[item.id] = !isCode
                            notifyDataSetChanged()
                        }
                        5 -> {
                            expandedModes[item.id] = !isExpanded
                            notifyDataSetChanged()
                        }
                        6 -> {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Delete item?")
                                .setMessage("This item will be deleted immediately.")
                                .setPositiveButton("Delete") { _, _ ->
                                    TeilenApi.deleteItem(Server.get(this@MainActivity), Session.token(this@MainActivity), item.id) { ok, msg ->
                                        if (ok) {
                                            Toast.makeText(this@MainActivity, "Deleted", Toast.LENGTH_SHORT).show()
                                            refreshFeed()
                                        } else {
                                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }
                    }
                    true
                }
                popup.show()
            }

            return view
        }
    }

    // ------------------------------------------------------------- Multi-Session Spaces

    private fun loadSessions() {
        if (!Session.isPaired(this)) return
        val url = Server.get(this)
        val token = Session.token(this)
        TeilenApi.fetchSessions(url, token) { sessions, _ ->
            if (sessions != null) {
                currentSessions.clear()
                currentSessions.addAll(sessions)
                renderSessionPills()
            }
        }
    }

    private fun showSessionSelectorDialog() {
        if (currentSessions.isEmpty()) {
            loadSessions()
        }
        val options = mutableListOf<String>()
        val sessionIds = mutableListOf<String?>()

        val personal = currentSessions.firstOrNull { it.isPersonal }
        options.add("🔒 Personal Vault")
        sessionIds.add(personal?.id)

        currentSessions.filter { !it.isPersonal }.forEach {
            options.add("👥 ${it.name} (${it.memberCount})")
            sessionIds.add(it.id)
        }

        options.add("➕ Join Room by Code…")
        sessionIds.add("ACTION_JOIN")

        AlertDialog.Builder(this)
            .setTitle("Switch Session / Room")
            .setItems(options.toTypedArray()) { _, which ->
                val chosenId = sessionIds[which]
                if (chosenId == "ACTION_JOIN") {
                    showJoinRoomDialog()
                } else {
                    Session.setActiveSessionId(this, chosenId)
                    renderSessionPills()
                    refreshFeed()
                    connectFeedWebSocket()
                }
            }
            .show()
    }

    private fun showJoinRoomDialog() {
        val input = EditText(this).apply {
            hint = "Invite Code (XXXX-XXXX)"
            isAllCaps = true
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(48, 24, 48, 12)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Join Space / Room")
            .setView(container)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotBlank()) {
                    val url = Server.get(this)
                    val token = Session.token(this)
                    TeilenApi.joinSession(url, token, code) { session, err ->
                        if (session != null) {
                            Session.setActiveSessionId(this, session.id)
                            Toast.makeText(this, "Joined '${session.name}'", Toast.LENGTH_LONG).show()
                            loadSessions()
                            refreshFeed()
                            connectFeedWebSocket()
                        } else {
                            Toast.makeText(this, err ?: "Failed to join room", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderSessionPills() {
        sessionPillContainer.removeAllViews()
        val activeId = Session.activeSessionId(this)

        val activeSession = currentSessions.firstOrNull { it.id == activeId }
            ?: currentSessions.firstOrNull { it.isPersonal }
        findViewById<TextView>(R.id.header_active_session_name)?.text = activeSession?.name ?: "Personal Vault"

        // 1. Personal Vault Pill
        val personalSession = currentSessions.firstOrNull { it.isPersonal }
        val isPersonalActive = activeId == null || (personalSession != null && activeId == personalSession.id)
        val personalPill = createPillView("🔒 Personal", isPersonalActive) {
            Session.setActiveSessionId(this, personalSession?.id)
            renderSessionPills()
            refreshFeed()
        }
        sessionPillContainer.addView(personalPill)

        // 2. Shared Group Sessions
        currentSessions.filter { !it.isPersonal }.forEach { session ->
            val isActive = activeId == session.id
            val label = "👥 ${session.name} (${session.memberCount})"
            val pill = createPillView(label, isActive) {
                Session.setActiveSessionId(this, session.id)
                renderSessionPills()
                refreshFeed()
            }
            sessionPillContainer.addView(pill)
        }

        // 3. "+ Room" Action Pill
        val addPill = createActionPillView("+ Room") {
            showCreateRoomDialog()
        }
        sessionPillContainer.addView(addPill)

        // 4. "Share Room" and "Leave Room" Action Pills (if active session is non-personal)
        val activeGroupSession = currentSessions.firstOrNull { !it.isPersonal && it.id == activeId }
        if (activeGroupSession != null) {
            val sharePill = createActionPillView("🔗 Share") {
                showShareRoomDialog(activeGroupSession)
            }
            sessionPillContainer.addView(sharePill)

            val leavePill = createActionPillView("✕ Leave") {
                showLeaveRoomDialog(activeGroupSession)
            }
            sessionPillContainer.addView(leavePill)
        }
    }

    private fun createPillView(label: String, active: Boolean, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(getColor(if (active) R.color.accent else R.color.pill_unselected_text))
            setBackgroundResource(if (active) R.drawable.bg_segmented_selected else R.drawable.bg_segmented_container)
            setPadding(24, 10, 24, 10)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 16
            }
            layoutParams = params
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHaptic(this, HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }
    }

    private fun createActionPillView(label: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.accent))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(20, 10, 20, 10)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 16
            }
            layoutParams = params
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHaptic(this, HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }
    }

    private fun showCreateRoomDialog() {
        val input = EditText(this).apply {
            hint = "Room Name (e.g. Study Group, Project Team)"
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(48, 24, 48, 12)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Create Space / Room")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val url = Server.get(this)
                    val token = Session.token(this)
                    TeilenApi.createSession(url, token, name) { created, err ->
                        if (created != null) {
                            SecureKeyManager.ensureSessionKey(this, created.id)
                            Session.setActiveSessionId(this, created.id)
                            Toast.makeText(this, "Room '${created.name}' created", Toast.LENGTH_SHORT).show()
                            loadSessions()
                            refreshFeed()
                        } else {
                            Toast.makeText(this, err ?: "Failed to create room", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showShareRoomDialog(session: TeilenApi.SessionInfo) {
        val url = Server.get(this)
        val token = Session.token(this)
        TeilenApi.generateSessionInvite(url, token, session.id) { code, err ->
            if (code != null) {
                val key = SecureKeyManager.ensureSessionKey(this, session.id)
                val keyB64Url = android.util.Base64.encodeToString(
                    key,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
                val inviteUri = "teilen://session/join?code=$code#key=$keyB64Url"

                val qrView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(400, 400).apply {
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                }
                try {
                    val bitmap = BarcodeEncoder().encodeBitmap(inviteUri, BarcodeFormat.QR_CODE, 400, 400)
                    qrView.setImageBitmap(bitmap)
                } catch (_: Exception) {}

                val textCode = TextView(this).apply {
                    text = "Code: $code\n\nScan QR or copy invite link to join '${session.name}'."
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 16, 0, 0)
                }

                val layout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 16)
                    addView(qrView)
                    addView(textCode)
                }

                AlertDialog.Builder(this)
                    .setTitle("Share Room: ${session.name}")
                    .setView(layout)
                    .setPositiveButton("Copy Link") { _, _ ->
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("Teilen Room Invite", inviteUri))
                        Toast.makeText(this, "Invite link copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } else {
                Toast.makeText(this, err ?: "Could not generate invite code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLeaveRoomDialog(session: TeilenApi.SessionInfo) {
        AlertDialog.Builder(this)
            .setTitle("Leave Room '${session.name}'?")
            .setMessage("You will lose access to items in this room unless re-invited.")
            .setPositiveButton("Leave") { _, _ ->
                val url = Server.get(this)
                val token = Session.token(this)
                TeilenApi.deleteSession(url, token, session.id) { _, msg ->
                    Session.setActiveSessionId(this, null)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    loadSessions()
                    refreshFeed()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("teilen", ignoreCase = true) && data.host.equals("session", ignoreCase = true)) {
            handleSessionJoinUri(data)
        }
    }

    private fun handleSessionJoinUri(uri: Uri) {
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Invalid session invite code", Toast.LENGTH_SHORT).show()
            return
        }
        val fragment = uri.fragment
        var keyBytes: ByteArray? = null
        if (!fragment.isNullOrBlank() && fragment.startsWith("key=")) {
            val keyStr = fragment.removePrefix("key=")
            keyBytes = runCatching {
                android.util.Base64.decode(keyStr, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            }.getOrNull()
        }

        AlertDialog.Builder(this)
            .setTitle("Join Space / Room")
            .setMessage("Do you want to join this shared space with code: $code?")
            .setPositiveButton("Join") { _, _ ->
                val url = Server.get(this)
                val token = Session.token(this)
                TeilenApi.joinSession(url, token, code) { session, err ->
                    if (session != null) {
                        if (keyBytes != null) {
                            SecureKeyManager.setSessionKey(this, session.id, keyBytes)
                        }
                        Session.setActiveSessionId(this, session.id)
                        Toast.makeText(this, "Joined '${session.name}'", Toast.LENGTH_LONG).show()
                        loadSessions()
                        refreshFeed()
                    } else {
                        Toast.makeText(this, err ?: "Failed to join room", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showItemCustomTtlDialog(item: FeedItem) {
        val input = EditText(this).apply {
            hint = "Minutes (e.g. 120)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60")
        }
        val container = FrameLayout(this).apply {
            setPadding(48, 24, 48, 12)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Expiration (TTL)")
            .setMessage("Set new expiration time in minutes from now for this item:")
            .setView(container)
            .setPositiveButton("Update") { _, _ ->
                val minutes = input.text.toString().toLongOrNull() ?: 60L
                val ttlSec = (minutes * 60L).coerceIn(60L, 30L * 86400L)
                TeilenApi.updateItemTtl(Server.get(this), Session.token(this), item.id, ttlSec) { ok, msg ->
                    if (ok) {
                        Toast.makeText(this, "Expiration updated", Toast.LENGTH_SHORT).show()
                        refreshFeed()
                    } else {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadItem(item: FeedItem) {
        val url = item.blobUrl(Server.get(this)) ?: return
        val fileName = item.content.ifBlank { "downloaded-file" }
        val mimeType = item.mimeType ?: "*/*"

        Toast.makeText(this, getString(R.string.downloading_file, fileName), Toast.LENGTH_SHORT).show()

        Thread {
            var downloadedUri: Uri? = null
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                if (connection.responseCode in 200..299) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Teilen")
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val resolver = contentResolver
                        val insertedUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (insertedUri != null) {
                            resolver.openOutputStream(insertedUri)?.use { out ->
                                connection.inputStream.use { input ->
                                    input.copyTo(out)
                                }
                            }
                            contentValues.clear()
                            contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(insertedUri, contentValues, null, null)
                            downloadedUri = insertedUri
                        }
                    } else {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val targetDir = java.io.File(downloadsDir, "Teilen").apply { mkdirs() }
                        val targetFile = java.io.File(targetDir, fileName)
                        targetFile.outputStream().use { out ->
                            connection.inputStream.use { input ->
                                input.copyTo(out)
                            }
                        }
                        downloadedUri = Uri.fromFile(targetFile)
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                downloadedUri = null
            }

            main.post {
                if (downloadedUri != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    } else {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    Toast.makeText(this, getString(R.string.saved_to_downloads, fileName), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Download failed for $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "teilen-download" }.start()
    }

    companion object {
        const val EXTRA_TEXT = "teilen.extra.text"
        const val EXTRA_STOP_WATCHING = "teilen.extra.stop_watching"
        private const val REQ_NOTIFICATIONS = 41
        private const val REQ_OPEN_DOCUMENT = 42
        private const val REQ_CAMERA = 43
        private const val FEED_POLL_MS = 5000L
    }
}