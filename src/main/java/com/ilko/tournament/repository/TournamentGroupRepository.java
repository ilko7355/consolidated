package com.ilko.tournament.repository;

import com.ilko.tournament.entity.TournamentGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TournamentGroupRepository extends JpaRepository<TournamentGroup, Long> {
    List<TournamentGroup> findByTournamentIdOrderByNameAsc(Long tournamentId);
    Optional<TournamentGroup> findByTournamentIdAndName(Long tournamentId, String name);
}
