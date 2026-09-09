package com.ilko.tournament.entity;

import com.ilko.tournament.enums.ParticipantStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.Locale;


@Entity
@Table(name = "participants", uniqueConstraints = {
    @UniqueConstraint(name = "uk_participant_tournament_name", columnNames = {"tournament_id", "name_lower"})
})
@Getter @Setter @NoArgsConstructor
public class Participant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) 
    private Long id;

    @Setter(AccessLevel.NONE) 
    @Column(nullable = false, length = 100) 
    private String name;

    @Setter(AccessLevel.NONE) 
    @Column(name = "name_lower", nullable = false, length = 100) 
    private String nameLower;

    @Enumerated(EnumType.STRING) @Column(nullable = false) 
    private ParticipantStatus status = ParticipantStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY, optional = false) 
    @JoinColumn(name = "tournament_id", nullable = false) 
    private Tournament tournament;

    // НОВО: Един участник може да е само в ЕДНА група (Many-to-One)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private TournamentGroup group;

    @ManyToOne(fetch = FetchType.LAZY) 
    @JoinColumn(name = "app_user_id") 
    private AppUser appUser;

    public void setName(String name) {
        this.name = name;
        this.nameLower = name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    @PrePersist void onCreate() { registeredAt = LocalDateTime.now(); }
    private LocalDateTime registeredAt;
}