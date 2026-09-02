package com.hostelops.claim.dto;

import java.util.List;

/**
 * The admin queue, paged.
 *
 * @param total how many pending requests exist in all, not just on this page - so the UI can show
 *              "3 of 47 waiting" rather than implying the page is everything
 */
public record PendingQueueDto(long total, int page, int size, List<PendingQueueRowDto> rows) {
}
