package com.ilko.tournament.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "tournament_groups", indexes = {
        @Index(name = "idx_group_tournament", columnList = "tournament_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_group_name_per_tournament", columnNames = {"tournament_id", "name"}))
@Getter @Setter @NoArgsConstructor
public class TournamentGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @Column(nullable = false, length = 50)
    private String name;

    @ManyToMany
    @JoinTable(
            name = "group_participants",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "participant_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "participant_id"})
    )
    private Set<Participant> participants = new LinkedHashSet<>();
}
