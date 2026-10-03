package com.backend.item;

import com.backend.auth.AuthenticatedUser;
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

/**
 * Every item is read and written in one account's name. The identity comes from the token, so there
 * is no way to address somebody else's feed through this controller.
 */
@RestController
@RequestMapping("/api/items")
public class ShareItemController {

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

    /**
     * One file per request, so a multi-select share becomes several rows and each keeps its own
     * expiry. The file itself is part "file"; ttlSeconds is an optional form field.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ShareItemResponse upload(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @RequestPart("file") MultipartFile file,
                                    @RequestParam(value = "ttlSeconds", required = false) Long ttlSeconds) {
        return service.upload(caller.userId(), file, ttlSeconds);
    }

    @GetMapping
    public List<ShareItemResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return service.listLive(caller.userId());
    }

    /**
     * The only endpoint reachable without a bearer token, because an inline preview cannot carry
     * one. It takes the signed link the API handed out with the item, and refuses everything else.
     */
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
                // inline, so a PDF or image opens in the browser rather than downloading
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
