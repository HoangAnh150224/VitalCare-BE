package com.vn.vitalcare.care.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** What the chat screens send. */
public final class ChatRequests {

    private ChatRequests() {
    }

    /** A guest's first message: who they are, which clinic, what they want. */
    public record GuestStart(
            @NotNull(message = "Pick a clinic")
            UUID clinicId,

            @NotBlank(message = "Give your name")
            @Size(max = 100, message = "The name must be at most 100 characters")
            String name,

            @NotBlank(message = "Give a phone number the clinic can call back")
            String phone,

            @NotBlank(message = "Write a message")
            @Size(max = 2000, message = "A message must be at most 2000 characters")
            String text) {
    }

    /** A signed-in customer writing to a clinic: continues their conversation with it, or starts one. */
    public record OwnStart(
            @NotNull(message = "Pick a clinic")
            UUID clinicId,

            @NotBlank(message = "Write a message")
            @Size(max = 2000, message = "A message must be at most 2000 characters")
            String text) {
    }

    public record Text(
            @NotBlank(message = "Write a message")
            @Size(max = 2000, message = "A message must be at most 2000 characters")
            String text) {
    }
}
