package com.vn.vitalcare.share.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Where uploaded files live, outside the database. Callers keep the key and
 * whatever they want to remember about the file (its name, its type); the
 * store only holds bytes under a key it chose.
 */
public interface FileStorage {

    /** Stores the bytes and returns the key to read them back by. Never derived from anything the uploader sent. */
    String store(InputStream content);

    /** The bytes under a key; a key this store did not issue is not found. */
    Resource load(String key);
}
