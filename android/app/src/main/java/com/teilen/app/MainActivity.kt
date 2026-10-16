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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Modern non-scrollable 4-tab interface: Send, Files, Feed, and Account.
 * Obsidian/Slate dark-first palette with tactile haptics, tabular counters,
 * pre-flight size checks, live route badges, and inline feed management.
 */
class MainActivity : Activity() {

    enum class Tab { SEND, FILES, FEED, ACCOUNT }

    // TTL values in seconds
    private val ttls = listOf(600L, 1800L, 3600L, 14400L, 86400L)
    private var selectedTtlSeconds = 3600L

    private val main = Handler(Looper.getMainLooper())
    private var currentTab = Tab.SEND

    // Header & Route Badges
    private lateinit var headerRouteBadge: TextView
    private lateinit var headerSessionBadge: TextView

    // Tab 1: Send
    private lateinit var content: EditText
    private lateinit var btnPasteSend: TextView
    private lateinit var ttlPill10m: TextView
    private lateinit var ttlPill30m: TextView
    private lateinit var ttlPill1h: TextView
    private lateinit var ttlPill4h: TextView
    private lateinit var ttlPill24h: TextView
    private lateinit var spinner: Spinner
    private lateinit var send: Button
    private lateinit var status: TextView

    // Tab 2: Files
    private lateinit var btnChipPhotos: Button
    private lateinit var btnChipDocuments: Button
    private lateinit var btnChipAnyFile: Button
    private lateinit var pickFile: Button
    private lateinit var fileErrorBox: LinearLayout
    private lateinit var fileErrorText: TextView
    private lateinit var fileProgress: ProgressBar
    private lateinit var fileStatus: TextView

    // Tab 3: Feed
    private lateinit var feedList: ListView
    private lateinit var feedEmpty: View
    private lateinit var feedSpinner: ProgressBar
    private lateinit var feedCount: TextView
    private lateinit var feedRefresh: TextView
    private lateinit var feedAdapter: InlineFeedAdapter
    private var feedLoading = false

    // Tab 4: Account
    private lateinit var account: AccountCard
    private lateinit var switchClipboard: Switch
    private lateinit var server: EditText
    private lateinit var saveServer: Button
    private lateinit var btnPrivacyPolicy: Button
    private lateinit var btnAccountDelete: Button

    // Containers & Gating Banners
    private lateinit var pageSend: View
    private lateinit var pageFiles: View
    private lateinit var pageFeed: View
    private lateinit var pageAccount: View

    private lateinit var cardText: View
    private lateinit var cardFiles: View
    private lateinit var cardFeed: View
    private lateinit var cardClipboard: View

    private lateinit var gateBannerText: View
    private lateinit var gateBannerFiles: View
    private lateinit var gateBannerFeed: View

    // Bottom Navigation Bar
    private lateinit var navTabSend: LinearLayout
    private lateinit var navTabFiles: LinearLayout
    private lateinit var navTabFeed: LinearLayout
    private lateinit var navTabAccount: LinearLayout

    private lateinit var navIconSend: ImageView
    private lateinit var navIconFiles: ImageView
    private lateinit var navIconFeed: ImageView
    private lateinit var navIconAccount: ImageView

    private lateinit var navLabelSend: TextView
    private lateinit var navLabelFiles: TextView
    private lateinit var navLabelFeed: TextView
    private lateinit var navLabelAccount: TextView

    // Connectivity monitoring
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { updateRouteBadge() }
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
        setContentView(R.layout.activity_main)

        bindViews()
        setupTtlPills()
        setupNavigation()
        setupSendTab()
        setupFilesTab()
        setupFeedTab()
        setupAccountTab()

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        account = AccountCard(this) { applyGating() }
        account.render()

        takeHandedOverText(intent)
    }

    override fun onResume() {
        super.onResume()
        updateRouteBadge()
        registerNetworkCallback()
        if (currentTab == Tab.FEED && Session.isPaired(this)) {
            refreshFeed(silent = true)
        }
        main.post(feedTicker)
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
        main.removeCallbacks(feedTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(feedTicker)
    }

    private fun bindViews() {
        headerRouteBadge = findViewById(R.id.header_route_badge)
        headerSessionBadge = findViewById(R.id.header_session_badge)

        content = findViewById(R.id.content)
        btnPasteSend = findViewById(R.id.btn_paste_send)
        ttlPill10m = findViewById(R.id.ttl_pill_10m)
        ttlPill30m = findViewById(R.id.ttl_pill_30m)
        ttlPill1h = findViewById(R.id.ttl_pill_1h)
        ttlPill4h = findViewById(R.id.ttl_pill_4h)
        ttlPill24h = findViewById(R.id.ttl_pill_24h)
        spinner = findViewById(R.id.ttl)
        send = findViewById(R.id.send)
        status = findViewById(R.id.status)

        btnChipPhotos = findViewById(R.id.btn_chip_photos)
        btnChipDocuments = findViewById(R.id.btn_chip_documents)
        btnChipAnyFile = findViewById(R.id.btn_chip_any_file)
        pickFile = findViewById(R.id.pick_file)
        fileErrorBox = findViewById(R.id.file_error_box)
        fileErrorText = findViewById(R.id.file_error_text)
        fileProgress = findViewById(R.id.file_progress)
        fileStatus = findViewById(R.id.file_status)

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

        pageSend = findViewById(R.id.page_send)
        pageFiles = findViewById(R.id.page_files)
        pageFeed = findViewById(R.id.page_feed)
        pageAccount = findViewById(R.id.page_account)

        cardText = findViewById(R.id.card_text)
        cardFiles = findViewById(R.id.card_files)
        cardFeed = findViewById(R.id.card_feed)
        cardClipboard = findViewById(R.id.card_clipboard)

        gateBannerText = findViewById(R.id.gate_banner_text)
        gateBannerFiles = findViewById(R.id.gate_banner_files)
        gateBannerFeed = findViewById(R.id.gate_banner_feed)

        navTabSend = findViewById(R.id.nav_tab_send)
        navTabFiles = findViewById(R.id.nav_tab_files)
        navTabFeed = findViewById(R.id.nav_tab_feed)
        navTabAccount = findViewById(R.id.nav_tab_account)

        navIconSend = findViewById(R.id.nav_icon_send)
        navIconFiles = findViewById(R.id.nav_icon_files)
        navIconFeed = findViewById(R.id.nav_icon_feed)
        navIconAccount = findViewById(R.id.nav_icon_account)

        navLabelSend = findViewById(R.id.nav_label_send)
        navLabelFiles = findViewById(R.id.nav_label_files)
        navLabelFeed = findViewById(R.id.nav_label_feed)
        navLabelAccount = findViewById(R.id.nav_label_account)
    }

    // ------------------------------------------------------------- Navigation

    private fun setupNavigation() {
        navTabSend.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.SEND)
        }
        navTabFiles.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.FILES)
        }
        navTabFeed.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.FEED)
        }
        navTabAccount.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
            selectTab(Tab.ACCOUNT)
        }

        findViewById<Button>(R.id.go_to_account_text).setOnClickListener { selectTab(Tab.ACCOUNT) }
        findViewById<Button>(R.id.go_to_account_files).setOnClickListener { selectTab(Tab.ACCOUNT) }
        findViewById<Button>(R.id.go_to_account_feed).setOnClickListener { selectTab(Tab.ACCOUNT) }

        // Hidden legacy button to launch FeedActivity if requested
        findViewById<Button>(R.id.open_feed).setOnClickListener {
            startActivity(Intent(this, FeedActivity::class.java))
        }

        selectTab(Tab.SEND)
    }

    fun selectTab(tab: Tab) {
        currentTab = tab
        pageSend.visibility = if (tab == Tab.SEND) View.VISIBLE else View.GONE
        pageFiles.visibility = if (tab == Tab.FILES) View.VISIBLE else View.GONE
        pageFeed.visibility = if (tab == Tab.FEED) View.VISIBLE else View.GONE
        pageAccount.visibility = if (tab == Tab.ACCOUNT) View.VISIBLE else View.GONE

        updateTabItem(navTabSend, navIconSend, navLabelSend, tab == Tab.SEND)
        updateTabItem(navTabFiles, navIconFiles, navLabelFiles, tab == Tab.FILES)
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
        // Fallback adapter for hidden legacy spinner
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
    }

    // ------------------------------------------------------------- Tab 1: Send

    private fun setupSendTab() {
        btnPasteSend.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm.primaryClip
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

        send.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            submit()
        }
    }

    private fun submit() {
        val text = content.text.toString().trim()
        if (text.isEmpty()) {
            status.text = getString(R.string.nothing_to_send)
            return
        }

        val url = Server.normalize(server.text.toString())
        Server.set(this, url)

        setBusy(true)
        TeilenApi.send(url, Session.token(this), text, selectedTtlSeconds) { ok, message ->
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
    }

    // ------------------------------------------------------------- Tab 2: Files

    private fun setupFilesTab() {
        btnChipPhotos.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            launchPicker("image/*")
        }
        btnChipDocuments.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            launchPicker("application/pdf")
        }
        btnChipAnyFile.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            launchPicker("*/*")
        }
        pickFile.setOnClickListener {
            performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
            launchPicker("*/*")
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
        if (requestCode != REQ_OPEN_DOCUMENT || resultCode != RESULT_OK || data == null) {
            return
        }
        val picked = pickedUris(data)
        if (picked.isEmpty()) {
            return
        }

        // Pre-flight Size Check (> 30 MB)
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
            fileErrorText.text = getString(
                R.string.file_too_large_error,
                "$name (${TeilenApi.humanSize(size)})"
            )
            performErrorHaptic(fileErrorBox)
            return
        } else {
            fileErrorBox.visibility = View.GONE
        }

        picked.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        uploadFiles(picked)
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

    private fun uploadFiles(uris: List<Uri>) {
        val url = Server.normalize(server.text.toString())
        Server.set(this, url)
        setBusy(true)
        showFileStatus(getString(R.string.uploading, TeilenApi.displayName(this, uris.first())), false)
        fileProgress.visibility = View.VISIBLE
        fileProgress.progress = 0

        TeilenApi.uploadAll(
            context = this,
            baseUrl = url,
            token = Session.token(this),
            uris = uris,
            ttlSeconds = selectedTtlSeconds,
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
                        getString(R.string.file_sent, uris.first().let { TeilenApi.displayName(this, it) })
                    } else {
                        getString(R.string.files_sent, outcome.sent)
                    }
                    showFileStatus(message, false)
                    Toast.makeText(this, R.string.sent_toast, Toast.LENGTH_SHORT).show()
                    if (currentTab == Tab.FEED) refreshFeed()
                } else {
                    showFileStatus(getString(R.string.file_failed, outcome.lastError ?: ""), true)
                    performErrorHaptic(fileStatus)
                }
            }
        )
    }

    private fun showFileStatus(text: String, isError: Boolean) {
        fileStatus.visibility = View.VISIBLE
        fileStatus.text = text
        fileStatus.setTextColor(
            getColor(if (isError) R.color.danger else R.color.text_secondary)
        )
    }

    // ------------------------------------------------------------- Tab 3: Feed

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

    private fun refreshFeed(silent: Boolean = false) {
        if (feedLoading || !Session.isPaired(this)) return
        feedLoading = true
        if (!silent && feedAdapter.count == 0) {
            feedSpinner.visibility = View.VISIBLE
        }
        TeilenApi.fetchItems(Server.get(this), Session.token(this)) { items, error ->
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

    // ------------------------------------------------------------- Tab 4: Account & Compliance

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
            setWatching(checked)
            if (checked) {
                askForNotificationPermission()
                ClipboardWatchService.start(this)
                Toast.makeText(this, R.string.watching_on, Toast.LENGTH_SHORT).show()
            } else {
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
    }

    private fun setWatching(watching: Boolean) {
        ClipboardWatchService.setWatching(this, watching)
        switchClipboard.isChecked = watching
        if (!watching) {
            ClipboardWatchService.stop(this)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            setWatching(false)
            Toast.makeText(this, R.string.notifications_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STOP_WATCHING, false)) {
            setWatching(false)
        }
        takeHandedOverText(intent)
    }

    private fun takeHandedOverText(intent: Intent?) {
        val handedOver = intent?.getStringExtra(EXTRA_TEXT) ?: return
        intent.removeExtra(EXTRA_TEXT)
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
        btnChipPhotos.isEnabled = !busy
        btnChipDocuments.isEnabled = !busy
        btnChipAnyFile.isEnabled = !busy
    }

    private fun applyGating() {
        val paired = Session.isPaired(this)

        cardText.visibility = if (paired) View.VISIBLE else View.GONE
        gateBannerText.visibility = if (paired) View.GONE else View.VISIBLE

        cardFiles.visibility = if (paired) View.VISIBLE else View.GONE
        gateBannerFiles.visibility = if (paired) View.GONE else View.VISIBLE

        cardFeed.visibility = if (paired) View.VISIBLE else View.GONE
        gateBannerFeed.visibility = if (paired) View.GONE else View.VISIBLE

        cardClipboard.visibility = if (paired) View.VISIBLE else View.GONE

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

    // ------------------------------------------------------------- Inline Feed Adapter

    private inner class InlineFeedAdapter : BaseAdapter() {
        private val rows = ArrayList<FeedItem>()

        fun replace(items: List<FeedItem>) {
            rows.clear()
            rows.addAll(items)
            notifyDataSetChanged()
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
            val btnOpen = view.findViewById<Button>(R.id.feed_action_open)
            val btnExtend = view.findViewById<Button>(R.id.feed_action_extend)
            val btnDelete = view.findViewById<Button>(R.id.feed_action_delete)

            kindBadge.text = item.kind.uppercase()
            titleText.text = item.title
            metaText.text = item.meta()

            // Calculate countdown ring progress
            val now = System.currentTimeMillis()
            val created = item.createdAt?.toEpochMilli() ?: (now - 3600_000L)
            val expires = item.expiresAt?.toEpochMilli() ?: (created + 3600_000L)
            val totalSpan = (expires - created).coerceAtLeast(1L)
            val remaining = (expires - now).coerceAtLeast(0L)
            val ratio = (remaining.toFloat() / totalSpan.toFloat()).coerceIn(0f, 1f)
            ring.progress = ratio

            // Image thumbnail
            val url = item.blobUrl(Server.get(this@MainActivity))
            if (item.isImage && url != null) {
                thumb.visibility = View.VISIBLE
                BlobPreview.load(thumb, url)
            } else {
                thumb.tag = null
                thumb.setImageDrawable(null)
                thumb.visibility = View.GONE
            }

            // Copy Action
            btnCopy.setOnClickListener {
                performHaptic(it, HapticFeedbackConstants.KEYBOARD_TAP)
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Teilen", item.content))
                Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }

            // Open Action
            if (item.hasBlob || item.type == "LINK") {
                btnOpen.visibility = View.VISIBLE
                btnOpen.setOnClickListener {
                    performHaptic(it, HapticFeedbackConstants.VIRTUAL_KEY)
                    openItem(item)
                }
            } else {
                btnOpen.visibility = View.GONE
            }

            // Extend Action (+1h)
            btnExtend.setOnClickListener {
                performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
                btnExtend.isEnabled = false
                TeilenApi.extendItem(Server.get(this@MainActivity), Session.token(this@MainActivity), item.id) { ok, msg ->
                    btnExtend.isEnabled = true
                    if (ok) {
                        Toast.makeText(this@MainActivity, "Extended +1 hour", Toast.LENGTH_SHORT).show()
                        refreshFeed()
                    } else {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Delete Action
            btnDelete.setOnClickListener {
                performHaptic(it, HapticFeedbackConstants.CLOCK_TICK)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete item?")
                    .setMessage("This item will be deleted immediately.")
                    .setPositiveButton("Delete") { _, _ ->
                        btnDelete.isEnabled = false
                        TeilenApi.deleteItem(Server.get(this@MainActivity), Session.token(this@MainActivity), item.id) { ok, msg ->
                            btnDelete.isEnabled = true
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

            return view
        }
    }

    companion object {
        const val EXTRA_TEXT = "teilen.extra.text"
        const val EXTRA_STOP_WATCHING = "teilen.extra.stop_watching"
        private const val REQ_NOTIFICATIONS = 41
        private const val REQ_OPEN_DOCUMENT = 42
        private const val FEED_POLL_MS = 5000L
    }
}