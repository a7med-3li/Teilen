package com.backend.item;

/**
 * Frames pushed over {@code /ws}, one account's feed at a time.
 *
 * <pre>
 * {"event":"created","item":{...}}
 * {"event":"deleted","id":"..."}
 * </pre>
 */
public record ShareItemEvent(String event, ShareItemResponse item, String id) {

    public static ShareItemEvent created(ShareItem item, String blobUrl) {
        return new ShareItemEvent("created", ShareItemResponse.from(item, blobUrl), null);
    }

    public static ShareItemEvent deleted(java.util.UUID id) {
        return new ShareItemEvent("deleted", null, id.toString());
    }
}
