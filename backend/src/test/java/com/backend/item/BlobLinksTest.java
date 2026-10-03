package com.backend.item;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one endpoint a browser can reach without a token, so the signature is the whole of its
 * security: it has to hold for this item, and stop holding the moment it should.
 */
class BlobLinksTest {

    private static final String SECRET = "a-test-key-that-is-the-same-every-time";

    @Test
    void aFreshLinkWorksForItsOwnItem() {
        BlobLinks links = new BlobLinks(SECRET, 900);
        UUID id = UUID.randomUUID();

        String link = links.linkFor(id, Instant.now().plusSeconds(300));
        String token = link.substring(link.indexOf("t=") + 2);

        assertThat(link).startsWith("/api/items/" + id + "/blob?t=");
        assertThat(links.isValid(id, token)).isTrue();
    }

    @Test
    void aLinkIsWorthlessForAnyOtherItem() {
        BlobLinks links = new BlobLinks(SECRET, 900);
        String link = links.linkFor(UUID.randomUUID(), Instant.now().plusSeconds(300));
        String token = link.substring(link.indexOf("t=") + 2);

        assertThat(links.isValid(UUID.randomUUID(), token)).isFalse();
    }

    @Test
    void tamperingWithTheExpiryOrTheSignatureIsCaught() {
        BlobLinks links = new BlobLinks(SECRET, 900);
        UUID id = UUID.randomUUID();
        String token = links.linkFor(id, Instant.now().plusSeconds(300)).split("t=")[1];
        long expiry = Long.parseLong(token.split("-")[0]);
        String signature = token.split("-")[1];

        // a stretched deadline, re-signed with somebody else's key, and pure noise
        assertThat(links.isValid(id, expiry + Duration.ofDays(365).toMillis() + "-" + signature)).isFalse();
        assertThat(links.isValid(id, expiry + "-" + "0".repeat(64))).isFalse();
        assertThat(links.isValid(id, "not-even-a-token")).isFalse();
        assertThat(links.isValid(id, null)).isFalse();
        assertThat(links.isValid(id, "")).isFalse();
    }

    @Test
    void aLinkStopsWorkingOnceItIsOld() {
        BlobLinks links = new BlobLinks(SECRET, 1);
        UUID id = UUID.randomUUID();

        // a link whose expiry is already behind us, signed with the right key
        String token = links.linkFor(id, Instant.now().minusSeconds(60)).split("t=")[1];

        assertThat(links.isValid(id, token)).isFalse();
    }

    @Test
    void aLinkNeverOutlivesTheItemItPointsAt() {
        BlobLinks links = new BlobLinks(SECRET, 900);
        Instant itemExpiry = Instant.now().plusSeconds(30);
        String link = links.linkFor(UUID.randomUUID(), itemExpiry);
        long expiry = Long.parseLong(link.split("t=")[1].split("-")[0]);

        assertThat(Instant.ofEpochMilli(expiry)).isBeforeOrEqualTo(itemExpiry.plusSeconds(1));
    }

    @Test
    void twoServersWithDifferentKeysDoNotAcceptEachOthersLinks() {
        UUID id = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);
        String token = new BlobLinks("key-one", 900).linkFor(id, expiry).split("t=")[1];

        assertThat(new BlobLinks("key-two", 900).isValid(id, token)).isFalse();
        assertThat(new BlobLinks("key-one", 900).isValid(id, token)).isTrue();
    }

    @Test
    void withoutASecretEveryStartGetsItsOwnKey() {
        UUID id = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);
        String token = new BlobLinks("", 900).linkFor(id, expiry).split("t=")[1];

        assertThat(new BlobLinks("  ", 900).isValid(id, token)).isFalse();
    }
}
