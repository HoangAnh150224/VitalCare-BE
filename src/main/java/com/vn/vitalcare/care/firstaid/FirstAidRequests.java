package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.entity.FirstAidCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What the first-aid screens send. */
public final class FirstAidRequests {

    private FirstAidRequests() {
    }

    /** An article's content, for a new draft or the next revision. */
    public record Content(
            @NotBlank(message = "Give the article a title")
            @Size(max = 200, message = "The title must be at most 200 characters")
            String title,

            @NotNull(message = "Pick a category")
            FirstAidCategory category,

            @NotBlank(message = "Write a short summary")
            @Size(max = 500, message = "The summary must be at most 500 characters")
            String summary,

            @NotBlank(message = "Write the guidance")
            @Size(max = 20000, message = "The guidance must be at most 20000 characters")
            String body,

            @NotBlank(message = "Say when to call for emergency help")
            @Size(max = 1000, message = "The emergency warning must be at most 1000 characters")
            String emergencyWarning) {
    }

    /** A reviewer's word on a step; required when sending an article back. */
    public record Decision(
            @Size(max = 1000, message = "The note must be at most 1000 characters")
            String note) {
    }
}
