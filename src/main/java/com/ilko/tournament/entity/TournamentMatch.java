package com.ilko.tournament.entity;

import com.ilko.tournament.enums.MatchStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tournament_matches", uniqueConstraints = @UniqueConstraint(name = "uk_match_round_number", columnNames = {"tournament_id", "round_number", "match_number"}), indexes = {
    @Index(name = "idx_match_participant1", columnList = "participant1_id"),
    @Index(name = "idx_match_participant2", columnList = "participant2_id")
})
@org.hibernate.annotations.Check(constraints = "(score1 is null or score1 >= 0) and (score2 is null or score2 >= 0)")
@Getter @Setter @NoArgsConstructor
public class TournamentMatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tournament_id", nullable = false) private Tournament tournament;
    @Column(nullable = false) private int roundNumber;
    @Column(nullable = false) private int matchNumber;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "participant1_id") private Participant participant1;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "participant2_id") private Participant participant2;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "winner_id") private Participant winner;
    private Integer score1;
    private Integer score2;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MatchStatus status = MatchStatus.PENDING;
    
    private LocalDateTime scheduledTime;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "next_match_id") private TournamentMatch nextMatch;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "group_id") private TournamentGroup group;
}
