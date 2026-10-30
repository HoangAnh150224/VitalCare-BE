package com.vn.vitalcare.care.chat;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** How every chat endpoint hands back an image. */
final class ChatImages {

    private ChatImages() {
    }

    /**
     * The bytes with their stored type, never sniffed, and private to the
     * reader: an image somebody uploaded must not be cached by a shared proxy
     * or run as anything else in this origin.
     */
    static ResponseEntity<Resource> response(SupportChat.StoredImage image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.sizeBytes())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(image.content());
    }
}
