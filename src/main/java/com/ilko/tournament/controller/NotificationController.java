package com.ilko.tournament.controller;

import com.ilko.tournament.dto.NotificationResponse;
import com.ilko.tournament.service.NotificationServiceApi;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController @RequestMapping("/api/notifications") @RequiredArgsConstructor
public class NotificationController {
    private final NotificationServiceApi service;
    @GetMapping public List<NotificationResponse> all(@RequestParam(defaultValue = "false") boolean unread, Authentication authentication) { return service.all(authentication.getName(), unread); }
    @PutMapping("/{id}/read") public NotificationResponse read(@PathVariable Long id, Authentication authentication) { return service.markRead(id, authentication.getName()); }
    @PutMapping("/read-all") public Map<String, Integer> readAll(Authentication authentication) { return Map.of("updated", service.markAllRead(authentication.getName())); }
}
