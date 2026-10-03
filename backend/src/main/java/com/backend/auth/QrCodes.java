package com.backend.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The pairing code as a QR, drawn as SVG so the browser scales it to any screen without blurring.
 *
 * ZXing only produces the module matrix; the few rectangles below are all it takes to turn that
 * into an image, which saves pulling in a whole rendering library.
 */
@Component
public class QrCodes {

    /** black on white, with a quiet zone: the standard four modules all around */
    public String svg(String text) {
        BitMatrix matrix = encode(text);
        int modules = matrix.getWidth();
        int quiet = 4;
        int size = modules + quiet * 2;

        StringBuilder path = new StringBuilder();
        for (int y = 0; y < modules; y++) {
            int runStart = -1;
            for (int x = 0; x <= modules; x++) {
                boolean dark = x < modules && matrix.get(x, y);
                if (dark && runStart < 0) {
                    runStart = x;
                } else if (!dark && runStart >= 0) {
                    // one rect per horizontal run instead of one per module: fewer nodes, same image
                    int width = x - runStart;
                    path.append("M").append(runStart + quiet).append(' ').append(y + quiet)
                            .append('h').append(width)
                            .append("v1h-").append(width).append('z');
                    runStart = -1;
                }
            }
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %1$d %1$d" \
                shape-rendering="crispEdges" role="img" aria-label="pairing code">
                <rect width="%1$d" height="%1$d" fill="#ffffff"/>
                <path d="%2$s" fill="#000000"/>
                </svg>
                """.formatted(size, path);
    }

    /** package-private so the tests can decode the result instead of trusting that it looks right */
    BitMatrix encode(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 0); // the quiet zone is drawn above
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("cannot encode a QR for this value", e);
        }
    }
}
