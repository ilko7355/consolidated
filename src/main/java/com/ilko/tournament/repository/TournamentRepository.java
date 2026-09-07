package com.ilko.tournament.repository;

import com.ilko.tournament.entity.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {
    @Query("select distinct t from Tournament t join fetch t.organizer left join fetch t.participants order by t.startDate asc")
    List<Tournament> findAllForList();
}
