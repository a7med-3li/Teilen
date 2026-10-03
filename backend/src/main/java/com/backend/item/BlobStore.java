package com.backend.item;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Blobs on local disk, one file per item, named after the item id.
 * <p>
 * The size cap is enforced while copying, never by trusting a header: a client can claim any
 * length it likes, so the bytes are counted as they go and the write is aborted the moment the
 * budget is gone. A rejected upload leaves nothing behind.
 */
@Component
public class BlobStore {

    private final Path root;
    private final long maxBytes;

    public BlobStore(@Value("${teilen.storage-dir:./data/blobs}") String storageDir,
                     @Value("${teilen.max-blob-bytes:31457280}") long maxBytes) throws IOException {
        this.maxBytes = maxBytes;
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public record Stored(String storageRef, long sizeBytes) {
    }

    public Stored store(UUID id, InputStream in) throws IOException {
        Path target = resolve(id.toString());
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try {
            Files.deleteIfExists(target);
            try (OutputStream out = Files.newOutputStream(target)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw tooLarge();
                    }
                    out.write(buffer, 0, read);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return new Stored(target.getFileName().toString(), total);
    }

    public Path resolve(String storageRef) {
        Path resolved = root.resolve(storageRef).normalize();
        if (!resolved.startsWith(root)) {
            // never let a crafted storage_ref escape the blob directory
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad storage reference");
        }
        return resolved;
    }

    public boolean exists(String storageRef) {
        return Files.isRegularFile(resolve(storageRef));
    }

    public void delete(String storageRef) {
        if (storageRef == null) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storageRef));
        } catch (IOException e) {
            // a leftover file is not worth failing a delete over; the reaper will try again
        }
    }

    public long maxBytes() {
        return maxBytes;
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "file is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit");
    }
}
