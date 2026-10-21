package com.teilen.app

import android.os.Bundle
import android.view.View
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Custom portrait-locked barcode scanner activity with obsidian framing,
 * prompt text, manual 8-digit code trigger, and close button.
 */
class PortraitCaptureActivity : CaptureActivity() {

    private lateinit var barcodeScannerView: DecoratedBarcodeView

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_qr_scanner)
        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner)
        barcodeScannerView.statusView?.visibility = View.GONE
        return barcodeScannerView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        findViewById<View>(R.id.scanner_btn_close)?.setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.scanner_btn_manual_code)?.setOnClickListener {
            setResult(RESULT_MANUAL_CODE)
            finish()
        }
    }

    companion object {
        const val RESULT_MANUAL_CODE = 4201
    }
}
