package com.ilko.tournament.repository;

import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.entity.Notification;
import com.ilko.tournament.entity.Tournament;
import com.ilko.tournament.entity.TournamentMatch;
import com.ilko.tournament.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientUsernameOrderByCreatedAtDesc(String username);
    List<Notification> findByRecipientUsernameAndReadStatusFalseOrderByCreatedAtDesc(String username);

    /** Prevents sending the same "upcoming match" notification to the same person twice for the same match. */
    boolean existsByRecipientAndMatchAndType(AppUser recipient, TournamentMatch match, NotificationType type);

    /** Prevents publishing "final results" twice to the same person for the same tournament. */
    boolean existsByRecipientAndTournamentAndType(AppUser recipient, Tournament tournament, NotificationType type);
}
