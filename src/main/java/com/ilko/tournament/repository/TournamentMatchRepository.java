package com.ilko.tournament.repository;

import com.ilko.tournament.entity.TournamentMatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, Long> {
    @EntityGraph(attributePaths = {"participant1", "participant2", "winner"})
    List<TournamentMatch> findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(Long tournamentId);
    boolean existsByTournamentId(Long tournamentId);
}
