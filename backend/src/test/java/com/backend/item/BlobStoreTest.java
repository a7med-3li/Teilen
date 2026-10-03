package com.backend.item;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cap is the one rule that must not bend, so it is tested on the bytes rather than on a
 * header: a stream that overshoots is cut off mid-write.
 */
class BlobStoreTest {

    private static InputStream bytes(int count) {
        byte[] data = new byte[count];
        Arrays.fill(data, (byte) 'x');
        return new ByteArrayInputStream(data);
    }

    @Test
    void writesTheBlobAndReportsItsSize(@TempDir Path dir) throws IOException {
        BlobStore store = new BlobStore(dir.toString(), 1024);
        UUID id = UUID.randomUUID();

        BlobStore.Stored stored = store.store(id, bytes(700));

        assertThat(stored.storageRef()).isEqualTo(id.toString());
        assertThat(stored.sizeBytes()).isEqualTo(700);
        assertThat(Files.size(store.resolve(stored.storageRef()))).isEqualTo(700);
    }

    @Test
    void refusesMoreThanTheCapAndLeavesNothingBehind(@TempDir Path dir) throws IOException {
        BlobStore store = new BlobStore(dir.toString(), 4096);

        assertThatThrownBy(() -> store.store(UUID.randomUUID(), bytes(5000)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("limit");

        // a rejected upload must not leave a partial file behind for the feed to serve later
        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aCraftedReferenceCannotEscapeTheBlobDirectory(@TempDir Path dir) throws IOException {
        BlobStore store = new BlobStore(dir.toString(), 1024);

        assertThatThrownBy(() -> store.resolve("../../etc/passwd"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(store.resolve("2b3c4d5e")).isEqualTo(dir.resolve("2b3c4d5e"));
    }
}