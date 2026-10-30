package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.entity.Conversation;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListParams;
import com.vn.vitalcare.share.web.ListResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /api/conversations} — the clinic's support inbox, within the caller's row-level scope. */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final SupportChat chat;

    public ConversationController(SupportChat chat) {
        this.chat = chat;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_READ + "')")
    public ResponseEntity<List<ChatResponses.Inbox>> list(@RequestParam MultiValueMap<String, String> query) {
        Page<Conversation> page = chat.inbox(new ListParams(query));
        Map<Long, Long> unread = chat.unreadByStaff(page.getContent());
        return ListResponse.of(page.getContent().stream()
                .map(c -> ChatResponses.Inbox.from(c, unread.getOrDefault(c.getId(), 0L), chat.capabilities(c)))
                .toList(), page.getTotalElements());
    }

    /** How many conversations wait on the clinic; the menu's badge. */
    @GetMapping("/waiting")
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_READ + "')")
    public Map<String, Long> waiting() {
        return Map.of("count", chat.waitingCount());
    }

    /** The conversation and its latest messages; opening it marks it read for the clinic. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_READ + "')")
    public ChatResponses.Thread thread(@PathVariable Long id) {
        return chat.staffThread(id);
    }

    @PostMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_WRITE + "')")
    public ChatResponses.Line reply(@PathVariable Long id, @Valid @RequestBody ChatRequests.Text request) {
        return chat.reply(id, request.text());
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_WRITE + "')")
    public ChatResponses.Line attach(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return chat.attachAsStaff(id, file);
    }

    @GetMapping("/{id}/images/{messageId}")
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_READ + "')")
    public ResponseEntity<Resource> image(@PathVariable Long id, @PathVariable Long messageId) {
        return ChatImages.response(chat.staffImage(id, messageId));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_WRITE + "')")
    public ChatResponses.Inbox close(@PathVariable Long id) {
        Conversation conversation = chat.setOpen(id, false);
        return ChatResponses.Inbox.from(conversation, 0, chat.capabilities(conversation));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('" + Permissions.CONVERSATIONS_WRITE + "')")
    public ChatResponses.Inbox reopen(@PathVariable Long id) {
        Conversation conversation = chat.setOpen(id, true);
        return ChatResponses.Inbox.from(conversation, 0, chat.capabilities(conversation));
    }
}
