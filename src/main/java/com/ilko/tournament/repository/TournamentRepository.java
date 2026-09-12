package com.ilko.tournament.repository;

import com.ilko.tournament.dto.TournamentListProjection;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.enums.TournamentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {
    // Projection query for the list view: selects only the scalar fields TournamentResponse needs
    // and computes the participant count with COUNT(p) at the database level via LEFT JOIN + GROUP BY
    // (LEFT JOIN so a tournament with zero participants still yields a row, with count 0, instead of
    // being dropped). This avoids ever loading the Participant collection into memory just to call
    // .size() on it, which is what the previous "join fetch t.participants" caused for every row.
    @Query("select new com.ilko.tournament.dto.TournamentListProjection(" +
            "t.id, t.name, t.description, t.format, t.status, t.startDate, t.endDate, o.username, count(p), " +
            "t.grandFinalReset) " +
            "from Tournament t join t.organizer o left join t.participants p " +
            "group by t.id, t.name, t.description, t.format, t.status, t.startDate, t.endDate, o.username, " +
            "t.grandFinalReset " +
            "order by t.startDate asc")
    List<TournamentListProjection> findAllForList();

    long countByStatus(TournamentStatus status);
}
