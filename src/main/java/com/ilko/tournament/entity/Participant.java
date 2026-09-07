package com.ilko.tournament.entity;

import com.ilko.tournament.enums.ParticipantStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.Locale;

@Entity
@Table(name = "participants", uniqueConstraints = {
        // Enforces "a participant name must not be duplicated within the same tournament" at the
        // database level, case-insensitively (name_lower), as a second layer behind the existing
        // application-level equalsIgnoreCase check in TournamentService.registerParticipant().
        // Deliberately scoped to (tournament_id, name_lower) only, so the SAME name remains free to
        // use across DIFFERENT tournaments.
        @UniqueConstraint(name = "uk_participant_tournament_name", columnNames = {"tournament_id", "name_lower"})
})
@Getter @Setter @NoArgsConstructor
public class Participant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Setter(AccessLevel.NONE) @Column(nullable = false, length = 100) private String name;

    /** Lowercased mirror of {@code name}, kept in sync automatically - backs the case-insensitive unique constraint above. Not meant to be read/written directly. */
    @Setter(AccessLevel.NONE) @Column(name = "name_lower", nullable = false, length = 100) private String nameLower;

    @Enumerated(EnumType.STRING) @Column(nullable = false) private ParticipantStatus status = ParticipantStatus.ACTIVE;
    @Column(nullable = false, updatable = false) private LocalDateTime registeredAt;

    /** The tournament this participant is registered in. Every participant belongs to exactly one tournament. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tournament_id", nullable = false) private Tournament tournament;

    /**
     * Optional link to a real platform account. When set, this participant
     * is a registered user and will receive real notifications (upcoming
     * matches, final results) for this tournament entry. When null, the
     * participant is just a name entered by the organizer and receives no
     * notifications (there is no account to notify).
     */
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "app_user_id") private AppUser appUser;

    public void setName(String name) {
        this.name = name;
        this.nameLower = name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    @PrePersist void onCreate() { registeredAt = LocalDateTime.now(); }
}
