package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.share.security.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /api/my_conversations} — a customer's own conversations with clinics; never anybody else's. */
@RestController
@RequestMapping("/api/my_conversations")
public class MyConversationController {

    private final SupportChat chat;

    public MyConversationController(SupportChat chat) {
        this.chat = chat;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_CONVERSATIONS_READ + "')")
    public List<ChatResponses.Own> list() {
        return chat.ownList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_CONVERSATIONS_READ + "')")
    public ChatResponses.Thread thread(@PathVariable Long id) {
        return chat.ownThread(id);
    }

    /** Writes to a clinic: into the conversation with it if there is one, else a new one. */
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.MY_CONVERSATIONS_WRITE + "')")
    public ChatResponses.Thread start(@Valid @RequestBody ChatRequests.OwnStart request) {
        return chat.startAsCustomer(request);
    }

    @PostMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('" + Permissions.MY_CONVERSATIONS_WRITE + "')")
    public ChatResponses.Line send(@PathVariable Long id, @Valid @RequestBody ChatRequests.Text request) {
        return chat.sendAsCustomer(id, request.text());
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.MY_CONVERSATIONS_WRITE + "')")
    public ChatResponses.Line attach(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return chat.attachAsCustomer(id, file);
    }

    @GetMapping("/{id}/images/{messageId}")
    @PreAuthorize("hasAuthority('" + Permissions.MY_CONVERSATIONS_READ + "')")
    public ResponseEntity<Resource> image(@PathVariable Long id, @PathVariable Long messageId) {
        return ChatImages.response(chat.ownImage(id, messageId));
    }
}
