package com.backend.item;

import com.backend.auth.domain.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/items")
public class ShareItemController {

    public record UpdateTtlBody(Long ttlSeconds) {
    }

    private final ShareItemService service;

    public ShareItemController(ShareItemService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareItemResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @Valid @RequestBody CreateShareItemRequest request) {
        return service.create(caller.userId(), request);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ShareItemResponse upload(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @RequestPart("file") MultipartFile file,
                                    @RequestParam(value = "ttlSeconds", required = false) Long ttlSeconds,
                                    @RequestParam(value = "sessionId", required = false) UUID sessionId) {
        return service.upload(caller.userId(), file, ttlSeconds, sessionId);
    }

    @GetMapping
    public List<ShareItemResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @RequestParam(value = "sessionId", required = false) UUID sessionId) {
        return service.listLive(caller.userId(), sessionId);
    }

    @PutMapping("/{id}/ttl")
    public ShareItemResponse updateTtl(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable UUID id,
                                       @RequestBody UpdateTtlBody body) {
        return service.extend(caller.userId(), id, body != null ? body.ttlSeconds() : null);
    }

    @GetMapping("/{id}/blob")
    public ResponseEntity<Resource> blob(@PathVariable UUID id,
                                         @RequestParam(name = "t", required = false) String linkToken,
                                         @AuthenticationPrincipal AuthenticatedUser caller) {
        ShareItemService.Blob blob = service.blob(id, linkToken, caller);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(blob.mimeType());
        } catch (InvalidMediaTypeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(blob.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(blob.resource());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID id) {
        service.delete(caller.userId(), id);
    }
}
