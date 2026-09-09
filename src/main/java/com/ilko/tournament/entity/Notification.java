package com.ilko.tournament.entity;

import com.ilko.tournament.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notification_recipient_read_created", columnList = "recipient_id, read_status, created_at"))
@Getter @Setter @NoArgsConstructor
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipient_id", nullable = false) private AppUser recipient;
    @Column(nullable = false, length = 1000) private String message;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private NotificationType type;
    @Column(nullable = false) private boolean readStatus;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;

    /** The real tournament this notification is about (always set). */
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "tournament_id") private Tournament tournament;

    /** The real match this notification is about (set for match-related notifications, null for tournament-wide ones like final results). */
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "match_id") private TournamentMatch match;

    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
