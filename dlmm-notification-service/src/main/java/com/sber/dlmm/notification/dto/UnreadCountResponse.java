package com.sber.dlmm.notification.dto;

/**
 * API response carrying the number of unread notifications for a user.
 *
 * <p>Returned by the unread-count endpoint and typically rendered as the badge counter on
 * the notification bell in the user-ui.
 *
 * @param count number of notifications the user has not yet read (never negative)
 */
public record UnreadCountResponse(long count) {}
