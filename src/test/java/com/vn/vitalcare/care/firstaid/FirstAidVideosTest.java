package com.vn.vitalcare.care.firstaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vn.vitalcare.share.exception.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRS 3.17: what may be framed as an article's video. */
class FirstAidVideosTest {

    @Test
    @DisplayName("Drive and YouTube links become their embed addresses; empty is none")
    void embeds() {
        String id = "152m6ev_k18SLT62YdEDeQssjeWGp38-1";
        assertThat(FirstAidVideos.embed("https://drive.google.com/file/d/" + id + "/view?usp=drivesdk"))
                .isEqualTo("https://drive.google.com/file/d/" + id + "/preview");
        assertThat(FirstAidVideos.embed(" https://drive.google.com/open?id=" + id + " "))
                .isEqualTo("https://drive.google.com/file/d/" + id + "/preview");
        assertThat(FirstAidVideos.embed("https://drive.google.com/file/d/" + id + "/preview"))
                .isEqualTo("https://drive.google.com/file/d/" + id + "/preview");
        assertThat(FirstAidVideos.embed("https://youtu.be/dQw4w9WgXcQ")).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
        assertThat(FirstAidVideos.embed("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"))
                .isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
        assertThat(FirstAidVideos.embed("  ")).isNull();
        assertThat(FirstAidVideos.embed(null)).isNull();
    }

    @Test
    @DisplayName("Anything else is refused: other sites, plain http, a lookalike host, a script")
    void refuses() {
        for (String bad : new String[] {"https://evil.example/video.mp4", "http://drive.google.com/file/d/152m6ev_k18SLT62YdEDeQssjeWGp38-1/view",
                "https://drive.google.com.evil.example/file/d/152m6ev_k18SLT62YdEDeQssjeWGp38-1/view",
                "javascript:alert(1)", "https://drive.google.com/drive/folders/1NaQfH9Kjvmdmz3dNVnJS2zWH4eejwm3G"}) {
            assertThatThrownBy(() -> FirstAidVideos.embed(bad)).as(bad).isInstanceOf(FieldValidationException.class);
        }
    }
}
