package com.backend.item;

/**
 * What kind of thing was shared.
 * <p>
 * {@code TEXT}/{@code LINK} carry their payload inline. {@code IMAGE}/{@code PDF}/{@code FILE} point at a
 * blob on disk and keep the original file name in {@code content}.
 */
public enum ShareItemType {

    TEXT,
    LINK,
    IMAGE,
    PDF,
    FILE;

    /**
     * A bare http(s) URL is a link, anything else is text.
     */
    public static ShareItemType detect(String content) {
        String trimmed = content.strip();
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            return LINK;
        }
        return TEXT;
    }

    /**
     * Maps an upload to a type from what it claims to be. The caller re-checks this against the
     * bytes before trusting it — a client is free to mislabel a file.
     */
    public static ShareItemType fromMime(String mimeType, String fileName) {
        if (mimeType == null) {
            return fromFileName(fileName);
        }
        String mime = mimeType.toLowerCase();
        if (mime.startsWith("image/")) {
            return IMAGE;
        }
        if (mime.equals("application/pdf")) {
            return PDF;
        }
        return fromFileName(fileName);
    }

    private static ShareItemType fromFileName(String fileName) {
        if (fileName == null) {
            return FILE;
        }
        String name = fileName.toLowerCase();
        if (name.endsWith(".pdf")) {
            return PDF;
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".heic")
                || name.endsWith(".bmp") || name.endsWith(".avif")) {
            return IMAGE;
        }
        return FILE;
    }
}
