package com.backend.item;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShareItemTypeTest {

    @Test
    void detectsLinks() {
        assertThat(ShareItemType.detect("https://example.com")).isEqualTo(ShareItemType.LINK);
        assertThat(ShareItemType.detect("  http://example.com/a?b=c  ")).isEqualTo(ShareItemType.LINK);
        assertThat(ShareItemType.detect("HTTPS://EXAMPLE.COM")).isEqualTo(ShareItemType.LINK);
    }

    @Test
    void everythingElseIsText() {
        assertThat(ShareItemType.detect("remember the milk")).isEqualTo(ShareItemType.TEXT);
        assertThat(ShareItemType.detect("see https://example.com")).isEqualTo(ShareItemType.TEXT);
        assertThat(ShareItemType.detect("ftp://example.com")).isEqualTo(ShareItemType.TEXT);
    }

    @Test
    void mimeWinsOverTheName() {
        assertThat(ShareItemType.fromMime("image/png", "notes.txt")).isEqualTo(ShareItemType.IMAGE);
        assertThat(ShareItemType.fromMime("application/pdf", "scan")).isEqualTo(ShareItemType.PDF);
        assertThat(ShareItemType.fromMime("video/mp4", "clip.mp4")).isEqualTo(ShareItemType.FILE);
    }

    @Test
    void fallsBackToTheNameWhenTheClientIsVague() {
        assertThat(ShareItemType.fromMime(null, "photo.JPEG")).isEqualTo(ShareItemType.IMAGE);
        assertThat(ShareItemType.fromMime("application/octet-stream", "report.pdf"))
                .isEqualTo(ShareItemType.PDF);
        assertThat(ShareItemType.fromMime(null, null)).isEqualTo(ShareItemType.FILE);
        assertThat(ShareItemType.fromMime("application/octet-stream", "archive.zip"))
                .isEqualTo(ShareItemType.FILE);
    }
}