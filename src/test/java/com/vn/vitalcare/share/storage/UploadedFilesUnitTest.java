package com.vn.vitalcare.share.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vn.vitalcare.share.exception.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Report-5.1 BE_Infra: UploadedFiles.readChecked, the check every upload passes before it is stored. */
class UploadedFilesUnitTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final String MISMATCH = "The file's content does not match its type";

    private static byte[] read(MockMultipartFile f, long max) {
        return UploadedFiles.readChecked(f, UploadedFiles.PNG_JPEG, max, "At most 1 KB", "Only PNG or JPEG");
    }

    private static void refused(MockMultipartFile f, long max, String message) {
        assertThatThrownBy(() -> read(f, max)).isInstanceOf(FieldValidationException.class)
                .hasMessage(message)
                .satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("file"));
    }

    @Test
    @DisplayName("A PNG whose bytes start with the PNG signature is returned whole")
    void up01_validPng() {
        assertThat(read(new MockMultipartFile("file", "a.png", "image/png", PNG), 1024)).isEqualTo(PNG);
    }

    @Test
    @DisplayName("No file, or an empty one, is refused: Choose a file")
    void up02_missingOrEmpty() {
        assertThatThrownBy(() -> read(null, 1024)).isInstanceOf(FieldValidationException.class).hasMessage("Choose a file");
        refused(new MockMultipartFile("file", "a.png", "image/png", new byte[0]), 1024, "Choose a file");
    }

    @Test
    @DisplayName("Boundary: a file of exactly maxBytes passes; one byte more is refused with the caller's message")
    void up03_sizeLimit() {
        assertThat(read(new MockMultipartFile("file", "a.png", "image/png", PNG.clone()), 8)).hasSize(8);
        refused(new MockMultipartFile("file", "a.png", "image/png", PNG.clone()), 7, "At most 1 KB");
    }

    @Test
    @DisplayName("A type the caller does not accept, or no declared type, is refused with the caller's message")
    void up04_wrongType() {
        refused(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.7".getBytes()), 1024, "Only PNG or JPEG");
        refused(new MockMultipartFile("file", "a", null, PNG), 1024, "Only PNG or JPEG");
    }

    @Test
    @DisplayName("Declared PNG but HTML inside, or a JPEG cut short: the content does not match its type")
    void up05_magicMismatch() {
        refused(new MockMultipartFile("file", "a.png", "image/png", "<html><script>".getBytes()), 1024, MISMATCH);
        refused(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8}), 1024, MISMATCH);
    }

    @Test
    @DisplayName("PDF_PNG_JPEG also takes a PDF told by %PDF")
    void up06_pdfAccepted() {
        byte[] pdf = "%PDF-1.7 ...".getBytes();
        assertThat(UploadedFiles.readChecked(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf),
                UploadedFiles.PDF_PNG_JPEG, 1024, "big", "type")).isEqualTo(pdf);
    }
}
