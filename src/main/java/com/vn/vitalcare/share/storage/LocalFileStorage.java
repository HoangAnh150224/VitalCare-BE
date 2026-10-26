package com.vn.vitalcare.share.storage;

import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Files on the local disk, under {@code app.storage.dir}, one file per key,
 * the key a random UUID: nothing a user sends ever becomes part of a path.
 *
 * <p>ponytail: a container's disk is wiped on redeploy (Railway), so uploads
 * do not survive one there; mount a volume at {@code app.storage.dir}, or put
 * an object-store implementation of {@link FileStorage} behind this interface,
 * before relying on it in production.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.dir:./data/uploads}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String store(InputStream content) {
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root);
            Files.copy(content, root.resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store an uploaded file", e);
        }
        return key;
    }

    @Override
    public Resource load(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ResourceNotFoundException("File", key);
        }
        Path file = root.resolve(key);
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("File", key);
        }
        return new PathResource(file);
    }
}
