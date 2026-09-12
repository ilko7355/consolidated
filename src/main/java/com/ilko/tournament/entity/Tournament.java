package com.ilko.tournament.entity;

import com.ilko.tournament.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "tournaments", indexes = {
    @Index(name = "idx_tournament_start_date", columnList = "start_date"),
    @Index(name = "idx_tournament_organizer", columnList = "organizer_id")
})
@org.hibernate.annotations.Check(constraints = "end_date >= start_date")
@Getter @Setter @NoArgsConstructor
public class Tournament {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    @Column(nullable = false, length = 150) private String name;
    @Column(length = 2000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TournamentFormat format;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TournamentStatus status = TournamentStatus.REGISTRATION;
    /**
     * Double elimination only: whether the grand final is followed by a deciding rematch when the
     * losers-bracket finalist wins it. Without the rematch a single loss in the grand final ends the
     * tournament for an otherwise unbeaten finalist; with it, both need two losses to be eliminated.
     */
    @Column(nullable = false) private boolean grandFinalReset;
    @Column(nullable = false) private LocalDate startDate;
    @Column(nullable = false) private LocalDate endDate;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "organizer_id", nullable = false) private AppUser organizer;
    /** Ordered by id, i.e. registration order - which is also the seeding order for brackets. */
    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Participant> participants = new ArrayList<>();
    /**
     * Groups belonging to this tournament (GROUPS format only). Deleting the tournament must delete
     * its groups (cascade REMOVE) so no TournamentGroup row is left behind referencing a tournament_id
     * that no longer exists. Only REMOVE is cascaded here - not PERSIST/MERGE - because groups are
     * persisted directly through TournamentGroupRepository, not through this collection.
     */
    @OneToMany(mappedBy = "tournament", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<TournamentGroup> groups = new ArrayList<>();
    /**
     * Matches belonging to this tournament, for object-navigation convenience only
     * (e.g. {@code tournament.getMatches()}) - deliberately NOT cascaded.
     *
     * Unlike participants/groups, a tournament can never actually need its matches cascade-deleted:
     * TournamentService.delete() only permits deletion while status == REGISTRATION (that status
     * check is the authoritative source of truth for the tournament lifecycle), and matches are only
     * ever created by generateBracket(), which moves the tournament out of REGISTRATION into
     * IN_PROGRESS. A tournament that has matches can therefore never be in REGISTRATION, and one in
     * REGISTRATION can never have matches - so "delete a tournament with matches" is not a reachable
     * state, and no cascade/orphanRemoval is needed here. Do not add CascadeType.ALL (or REMOVE) to
     * this relationship without first changing that lifecycle rule - it would be dead code at best,
     * and risks silently deleting live bracket data if the rule is ever relaxed without revisiting this.
     */
    @OneToMany(mappedBy = "tournament")
    private List<TournamentMatch> matches = new ArrayList<>();
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
