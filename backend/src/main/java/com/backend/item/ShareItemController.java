package com.backend.item;

import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/items")
public class ShareItemController {

    private final ShareItemService service;

    public ShareItemController(ShareItemService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareItemResponse create(@Valid @RequestBody CreateShareItemRequest request) {
        return ShareItemResponse.from(service.create(request));
    }

    /**
     * One file per request, so a multi-select share becomes several rows and each keeps its own
     * expiry. The file itself is part "file"; ttlSeconds is an optional form field.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ShareItemResponse upload(@RequestPart("file") MultipartFile file,
                                    @RequestParam(value = "ttlSeconds", required = false) Long ttlSeconds) {
        return ShareItemResponse.from(service.upload(file, ttlSeconds));
    }

    @GetMapping
    public List<ShareItemResponse> list() {
        return service.listLive().stream().map(ShareItemResponse::from).toList();
    }

    @GetMapping("/{id}/blob")
    public ResponseEntity<Resource> blob(@PathVariable UUID id) {
        ShareItemService.Blob blob = service.blob(id);
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
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
