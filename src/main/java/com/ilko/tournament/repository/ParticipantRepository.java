package com.ilko.tournament.repository;

import com.ilko.tournament.entity.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, Long> { }
