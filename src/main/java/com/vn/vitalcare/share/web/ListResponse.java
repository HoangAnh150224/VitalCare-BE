package com.vn.vitalcare.share.web;

import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * The list half of the {@code simple-rest} contract.
 *
 * <p>The provider reads the rows straight off the response body as a bare JSON
 * array and takes the unpaged total from the {@code X-Total-Count} header. That
 * header is not a simple response header, so it also has to be named in
 * {@code Access-Control-Expose-Headers} — see {@code WebMvcConfig} — or the browser
 * hides it and every list renders as a single page.
 */
public final class ListResponse {

    public static final String TOTAL_COUNT_HEADER = "X-Total-Count";

    private ListResponse() {
    }

    public static <T> ResponseEntity<List<T>> of(List<T> rows, long total) {
        return ResponseEntity.ok()
                .header(TOTAL_COUNT_HEADER, String.valueOf(total))
                .body(rows);
    }
}
