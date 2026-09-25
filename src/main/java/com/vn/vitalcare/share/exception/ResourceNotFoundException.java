package com.vn.vitalcare.share.exception;

/** Raised when a resource is addressed by an id that does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s with id %s was not found".formatted(resource, id));
    }
}
