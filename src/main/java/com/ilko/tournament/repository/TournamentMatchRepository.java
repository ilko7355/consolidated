package com.ilko.tournament.repository;

import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.MatchStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, Long> {
    @EntityGraph(attributePaths = {"participant1", "participant2", "winner", "group"})
    List<TournamentMatch> findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(Long tournamentId);

    boolean existsByTournamentId(Long tournamentId);

    long countByStatusAndScore1IsNotNull(MatchStatus status);

    /** Every match in which one of the two sides is linked to the given account, newest tournament first. */
    @EntityGraph(attributePaths = {"tournament", "participant1", "participant1.appUser", "participant2", "participant2.appUser", "winner", "group"})
    @Query("select m from TournamentMatch m " +
            "left join m.participant1 p1 left join p1.appUser u1 " +
            "left join m.participant2 p2 left join p2.appUser u2 " +
            "where u1.username = :username or u2.username = :username " +
            "order by m.tournament.id desc, m.roundNumber asc, m.matchNumber asc")
    List<TournamentMatch> findForPlayer(@Param("username") String username);
}
