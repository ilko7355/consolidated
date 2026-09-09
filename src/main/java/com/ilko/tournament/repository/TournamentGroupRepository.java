package com.ilko.tournament.repository;

import com.ilko.tournament.entity.TournamentGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TournamentGroupRepository extends JpaRepository<TournamentGroup, Long> {
    List<TournamentGroup> findByTournamentIdOrderByNameAsc(Long tournamentId);
    Optional<TournamentGroup> findByTournamentIdAndName(Long tournamentId, String name);
    Optional<TournamentGroup> findByTournamentId(Long tournamentId);

    /**
     * Finds the single group (within this tournament) that a participant is currently assigned to,
     * if any - directly via the group_participants join table, instead of loading every group and
     * scanning each one's participant collection in application code.
     */
    Optional<TournamentGroup> findByTournamentIdAndParticipantsId(Long tournamentId, Long participantId);
}
