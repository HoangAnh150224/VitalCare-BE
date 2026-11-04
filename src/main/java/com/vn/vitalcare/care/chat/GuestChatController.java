package com.vn.vitalcare.care.chat;

import com.vn.vitalcare.care.registration.service.OtpIpLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Chat for somebody who has not signed in. Open to everybody (see
 * {@code SecurityConfig}); a conversation is reached only with the token
 * handed back when it was started, sent as {@code X-Chat-Token}.
 */
@RestController
@RequestMapping("/api/public/chat")
public class GuestChatController {

    static final String TOKEN = "X-Chat-Token";

    private final SupportChat chat;

    public GuestChatController(SupportChat chat) {
        this.chat = chat;
    }

    @PostMapping
    public ResponseEntity<ChatResponses.GuestStarted> start(@Valid @RequestBody ChatRequests.GuestStart request,
                                                            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chat.startAsGuest(request, OtpIpLimiter.clientIp(http)));
    }

    @GetMapping("/{id}")
    public ChatResponses.Thread thread(@PathVariable Long id, @RequestHeader(value = TOKEN, required = false) String token) {
        return chat.guestThread(id, token);
    }

    @PostMapping("/{id}/messages")
    public ChatResponses.Line send(@PathVariable Long id, @RequestHeader(value = TOKEN, required = false) String token,
                                   @Valid @RequestBody ChatRequests.Text request, HttpServletRequest http) {
        return chat.sendAsGuest(id, token, request.text(), OtpIpLimiter.clientIp(http));
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatResponses.Line attach(@PathVariable Long id, @RequestHeader(value = TOKEN, required = false) String token,
                                     @RequestPart("file") MultipartFile file, HttpServletRequest http) {
        return chat.attachAsGuest(id, token, file, OtpIpLimiter.clientIp(http));
    }

    @GetMapping("/{id}/images/{messageId}")
    public ResponseEntity<Resource> image(@PathVariable Long id, @PathVariable Long messageId,
                                          @RequestHeader(value = TOKEN, required = false) String token) {
        return ChatImages.response(chat.guestImage(id, token, messageId));
    }
}
