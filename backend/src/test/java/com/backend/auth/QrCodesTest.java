package com.backend.auth;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The QR has to be a real QR: a phone camera scans this, and if it is merely a drawing of one,
 * pairing fails on a real device and nowhere else. So the output is decoded again, not eyeballed.
 */
class QrCodesTest {

    private static final String DEEP_LINK = "teilen://pair?code=K7PM-3XQD";

    private final QrCodes qrCodes = new QrCodes();

    @Test
    void scansBackToTheDeepLink() throws NotFoundException {
        var matrix = qrCodes.encode(DEEP_LINK);

        // feed the modules through a decoder as if they were pixels, the way a camera would
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                pixels[y * width + x] = value;
            }
        }
        var source = new RGBLuminanceSource(width, height, pixels);
        var decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)));

        assertThat(decoded.getText()).isEqualTo(DEEP_LINK);
    }

    @Test
    void drawsAnSvgWithAQuietZone() {
        var matrix = qrCodes.encode(DEEP_LINK);
        int side = matrix.getWidth() + 8; // four modules of white on every side

        String svg = qrCodes.svg(DEEP_LINK);

        assertThat(svg).startsWith("<?xml")
                .contains("<svg")
                .contains("viewBox=\"0 0 " + side + " " + side + "\"")
                .contains("fill=\"#ffffff\"")
                .contains("<path")
                .contains("fill=\"#000000\"");
        // crisp edges and no strokes: the browser scales it to any size without blurring
        assertThat(svg).contains("shape-rendering=\"crispEdges\"").doesNotContain("stroke");
    }

    @Test
    void everyRunOfModulesBecomesOneRectangle() {
        int modules = qrCodes.encode(DEEP_LINK).getWidth() * qrCodes.encode(DEEP_LINK).getWidth();
        String svg = qrCodes.svg(DEEP_LINK);

        // a real QR has well over a hundred rectangles, never one per module
        assertThat(modules).isGreaterThan(400);
        assertThat(svg.split("M").length - 1).isGreaterThan(50).isLessThan(modules);
    }

    @Test
    void longerTextNeedsMoreModules() {
        assertThat(qrCodes.encode("x".repeat(600)).getWidth())
                .isGreaterThan(qrCodes.encode(DEEP_LINK).getWidth());
    }
}
