package com.techinterviewer.common.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Standard pagination envelope: {@code { items, page, size, totalItems, totalPages }}.
 * {@code page} is zero-based, matching the {@code ?page=0&size=20} query parameters.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public PageResponse {
        items = List.copyOf(items);
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
