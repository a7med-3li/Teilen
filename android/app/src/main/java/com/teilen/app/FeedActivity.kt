package com.teilen.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Everything this account has shared, from every device, newest first.
 *
 * The server already scopes the list to the token's account, so this screen holds no state beyond
 * what it last read: whatever was sent from the web, or from another phone, simply turns up here.
 *
 * It re-reads the feed on a timer rather than holding a socket open. A phone screen that is being
 * looked at can afford one small request every few seconds, and polling needs no client-side
 * WebSocket implementation — which this app, having no dependencies, does not have.
 *
 * Tapping a row opens the thing itself: a file is handed to whatever app can view it, by way of
 * the signed blob link, so no token and no download step are involved; text and links are shown
 * here, with links offered to the browser.
 */
class FeedActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var subtitle: TextView
    private lateinit var adapter: Rows

    private val main = Handler(Looper.getMainLooper())
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // nothing here is addressable without a token, so there is no anonymous version of this
        // screen to fall back to
        if (!Session.isPaired(this)) {
            Toast.makeText(this, R.string.feed_need_pair, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContentView(R.layout.activity_feed)
        list = findViewById(R.id.feed_list)
        empty = findViewById(R.id.feed_empty)
        spinner = findViewById(R.id.feed_spinner)
        subtitle = findViewById(R.id.feed_subtitle)

        adapter = Rows()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(adapter.at(position)) }
        findViewById<TextView>(R.id.feed_refresh).setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            // a stale list is worse than a slow one, so the first read happens on the way in
            main.post(ticker)
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, POLL_MILLIS)
        }
    }

    private fun refresh() {
        if (loading) {
            return
        }
        loading = true
        if (adapter.count == 0) {
            spinner.visibility = View.VISIBLE
        }
        TeilenApi.fetchItems(Server.get(this), Session.token(this)) { items, error ->
            loading = false
            spinner.visibility = View.GONE
            if (error != null) {
                if (error == TeilenApi.UNPAIRED_MESSAGE) {
                    // revoked from another device: forget the token instead of retrying forever
                    Session.clear(this)
                    Toast.makeText(this, R.string.feed_need_pair, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                return@fetchItems
            }
            val rows = items ?: return@fetchItems
            adapter.replace(rows)
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            subtitle.text = getString(R.string.feed_count, rows.size)
        }
    }

    private fun open(item: FeedItem) {
        val url = item.blobUrl(Server.get(this))
        when {
            // an image is shown here, because an outside viewer is under its own network rules
            // and will often refuse a plain-http link this app is allowed to fetch
            url != null && item.isImage -> startActivity(
                Intent(this, ImageViewActivity::class.java)
                    .putExtra(ImageViewActivity.EXTRA_URL, url)
                    .putExtra(ImageViewActivity.EXTRA_TITLE, item.content)
            )

            url != null -> handToViewer(url, item.mimeType ?: "*/*", item.content)

            else -> showText(item)
        }
    }

    /**
     * The signed link is the whole point: it is the one URL on this server that works without a
     * bearer token, which is exactly what a gallery app or a PDF reader needs.
     */
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

    /** the rows, newest first; a plain [BaseAdapter] because the app has no support library */
    private inner class Rows : BaseAdapter() {

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
            val view = convertView ?: LayoutInflater.from(this@FeedActivity)
                .inflate(R.layout.item_feed, parent, false)
            val item = rows[position]
            view.findViewById<TextView>(R.id.feed_kind).text = item.kind
            view.findViewById<TextView>(R.id.feed_title).text = item.title
            view.findViewById<TextView>(R.id.feed_meta).text = item.meta()

            val thumb = view.findViewById<ImageView>(R.id.feed_thumb)
            val url = item.blobUrl(Server.get(this@FeedActivity))
            if (item.isImage && url != null) {
                thumb.visibility = View.VISIBLE
                BlobPreview.load(thumb, url)
            } else {
                // the view is about to be reused, so drop the stamp with the bitmap
                thumb.tag = null
                thumb.setImageDrawable(null)
                thumb.visibility = View.GONE
            }
            return view
        }
    }

    private companion object {
        const val POLL_MILLIS = 5_000L
    }
}