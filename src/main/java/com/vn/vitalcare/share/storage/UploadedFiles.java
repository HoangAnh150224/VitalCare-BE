package com.vn.vitalcare.share.storage;

import com.vn.vitalcare.share.exception.FieldValidationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/**
 * Checks an upload before it is stored: present, not too large, of a type the
 * caller accepts, and starting with that type's magic bytes. The declared type
 * is the client's word; the first bytes are the file's.
 */
public final class UploadedFiles {

    public static final Map<String, byte[]> PDF_PNG_JPEG = Map.of(
            "application/pdf", new byte[] {'%', 'P', 'D', 'F'},
            "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'},
            "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    public static final Map<String, byte[]> PNG_JPEG = Map.of(
            "image/png", PDF_PNG_JPEG.get("image/png"),
            "image/jpeg", PDF_PNG_JPEG.get("image/jpeg"));

    private UploadedFiles() {
    }

    /**
     * The upload's bytes, once it has passed every check; each failure is a
     * 422 on the field {@code file}.
     *
     * @param allowed     accepted content types, each with its magic bytes
     * @param maxBytes    the largest file accepted
     * @param tooLarge    the message for a file over {@code maxBytes}
     * @param wrongType   the message for a type not in {@code allowed}
     */
    public static byte[] readChecked(MultipartFile upload, Map<String, byte[]> allowed, long maxBytes,
                                     String tooLarge, String wrongType) {
        if (upload == null || upload.isEmpty()) {
            throw new FieldValidationException("file", "Choose a file");
        }
        if (upload.getSize() > maxBytes) {
            throw new FieldValidationException("file", tooLarge);
        }
        String type = upload.getContentType();
        byte[] magic = type == null ? null : allowed.get(type);
        if (magic == null) {
            throw new FieldValidationException("file", wrongType);
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read an uploaded file", e);
        }
        if (bytes.length < magic.length || !Arrays.equals(Arrays.copyOf(bytes, magic.length), magic)) {
            throw new FieldValidationException("file", "The file's content does not match its type");
        }
        return bytes;
    }
}
