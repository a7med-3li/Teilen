package com.backend.item;

/**
 * Frames pushed over {@code /ws}.
 *
 * <pre>
 * {"event":"created","item":{...}}
 * {"event":"deleted","id":"..."}
 * </pre>
 */
public record ShareItemEvent(String event, ShareItemResponse item, String id) {

    public static ShareItemEvent created(ShareItem item) {
        return new ShareItemEvent("created", ShareItemResponse.from(item), null);
    }

    public static ShareItemEvent deleted(java.util.UUID id) {
        return new ShareItemEvent("deleted", null, id.toString());
    }
}
