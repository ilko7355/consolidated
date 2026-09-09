package com.ilko.tournament.service;

import com.ilko.tournament.dto.NotificationResponse;
import java.util.List;

public interface NotificationServiceApi {
    List<NotificationResponse> all(String username, boolean unread);
    NotificationResponse markRead(Long id, String username);
}