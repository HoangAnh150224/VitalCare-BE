package com.vn.vitalcare.care.firstaid;

import com.vn.vitalcare.share.exception.FieldValidationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An article's video is a Google Drive file or a YouTube video, kept as the
 * address the page puts in an iframe. Anything else is refused: the page
 * frames whatever is stored here, so it frames only these two.
 */
final class FirstAidVideos {

    private static final Pattern DRIVE = Pattern.compile(
            "^https://drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?(?:export=\\w+&)?id=)([\\w-]{20,})(?:[/?&#].*)?$");
    private static final Pattern YOUTUBE = Pattern.compile(
            "^https://(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?v=|embed/|shorts/)|youtu\\.be/)([\\w-]{11})(?:[?&#/].*)?$");

    private FirstAidVideos() {
    }

    /** The embed address for a pasted link; null for none. */
    static String embed(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String url = link.trim();
        Matcher drive = DRIVE.matcher(url);
        if (drive.matches()) {
            return "https://drive.google.com/file/d/" + drive.group(1) + "/preview";
        }
        Matcher youtube = YOUTUBE.matcher(url);
        if (youtube.matches()) {
            return "https://www.youtube.com/embed/" + youtube.group(1);
        }
        throw new FieldValidationException("videoUrl", "Paste a Google Drive or YouTube link");
    }
}
