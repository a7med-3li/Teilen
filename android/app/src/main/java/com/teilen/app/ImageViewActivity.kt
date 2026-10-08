package com.teilen.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * One image, full screen, fetched by its signed link.
 *
 * This is what a tap on an image row opens, rather than handing the link to the gallery: an
 * external viewer is under its own network rules and will often refuse a plain-http address, while
 * this app is already allowed to talk to the backend the user pointed it at. Everything that is
 * not an image still goes out to a real viewer, because drawing a PDF or a video is not something
 * to reimplement here.
 */
class ImageViewActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())

    /** gives up waiting and says so, rather than leaving a black rectangle with no explanation */
    private val patience = Runnable {
        val image = findViewById<ImageView>(R.id.image_full)
        if (image.drawable == null && !isFinishing) {
            findViewById<TextView>(R.id.image_failure).visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        setContentView(R.layout.activity_image)

        findViewById<TextView>(R.id.image_caption).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val image = findViewById<ImageView>(R.id.image_full)
        // anywhere but the picture closes it, which is the whole gesture
        findViewById<View>(R.id.image_root).setOnClickListener { finish() }
        image.setOnClickListener { finish() }

        BlobPreview.loadLarge(image, url)
        main.postDelayed(patience, PATIENCE_MILLIS)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(patience)
    }

    companion object {
        const val EXTRA_URL = "teilen.extra.image_url"
        const val EXTRA_TITLE = "teilen.extra.image_title"

        /** a photo on a good connection is here long before this fires */
        private const val PATIENCE_MILLIS = 4_000L
    }
}