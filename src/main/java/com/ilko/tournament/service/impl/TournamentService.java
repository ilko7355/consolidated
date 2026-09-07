package com.ilko.tournament.service.impl;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.entity.*;
import com.ilko.tournament.enums.*;
import com.ilko.tournament.exception.*;
import com.ilko.tournament.repository.*;
import com.ilko.tournament.mapper.TournamentMapper;
import com.ilko.tournament.service.TournamentServiceApi;
import com.ilko.tournament.service.BracketGenerator;
import com.ilko.tournament.service.RankingCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class TournamentService implements TournamentServiceApi {
    private final BracketGenerator bracketGenerator;
    private final RankingCalculator rankingCalculator;
    private final TournamentRepository tournaments;
    private final AppUserRepository users;
    private final ParticipantRepository participants;
    private final TournamentMatchRepository matches;
    private final TournamentGroupRepository groups;
    private final NotificationRepository notifications;

    @Transactional public TournamentResponse create(CreateTournamentRequest request, Authentication authentication) {
        if (request.endDate().isBefore(request.startDate())) throw new BusinessException("End date cannot be before start date");
        Tournament tournament = new Tournament(); tournament.setName(request.name()); tournament.setDescription(request.description()); tournament.setFormat(request.format()); tournament.setStartDate(request.startDate()); tournament.setEndDate(request.endDate()); tournament.setOrganizer(user(authentication)); return response(tournaments.save(tournament));
    }
    @Transactional(readOnly = true) public List<TournamentResponse> list() { return tournaments.findAllForList().stream().map(this::response).toList(); }
    @Transactional(readOnly = true) public Tournament get(Long id) { return tournaments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + id)); }
    @Transactional(readOnly = true) public TournamentResponse getResponse(Long id) { return response(get(id)); }
    @Transactional public TournamentResponse update(Long id, UpdateTournamentRequest request, Authentication authentication) { Tournament tournament = get(id); requireOwner(tournament, authentication); if (request.endDate().isBefore(request.startDate())) throw new BusinessException("End date cannot be before start date"); if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Only registration tournaments can be updated"); tournament.setName(request.name()); tournament.setDescription(request.description()); tournament.setStartDate(request.startDate()); tournament.setEndDate(request.endDate()); return response(tournaments.save(tournament)); }
    @Transactional public void delete(Long id, Authentication authentication) { Tournament tournament = get(id); requireOwner(tournament, authentication); if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Only registration tournaments can be deleted"); tournament.getParticipants().clear(); tournaments.delete(tournament); }
    @Transactional public ParticipantResponse registerParticipant(Long id, ParticipantRequest request, Authentication authentication) {
        Tournament tournament = get(id); requireOwner(tournament, authentication); if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Registration is closed");
        if (tournament.getParticipants().stream().anyMatch(p -> p.getName().equalsIgnoreCase(request.name()))) throw new ConflictException("Participant is already registered");
        Participant participant = new Participant();
        participant.setName(request.name());
        participant.setTournament(tournament);
        if (request.username() != null && !request.username().isBlank()) {
            AppUser linkedAccount = users.findByUsername(request.username().trim())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.username()));
            participant.setAppUser(linkedAccount);
        }
        Participant saved = participants.save(participant);
        tournament.getParticipants().add(saved);
        return participantResponse(saved);
    }
    @Transactional(readOnly = true) public List<ParticipantResponse> registered(Long id) { return get(id).getParticipants().stream().map(this::participantResponse).toList(); }
    @Transactional(readOnly = true) public List<GroupResponse> groups(Long id) {
        Tournament tournament = get(id);
        if (tournament.getFormat() != TournamentFormat.GROUPS) throw new BusinessException("Groups are only available for GROUPS tournaments");
        return groups.findByTournamentIdOrderByNameAsc(id).stream().map(this::groupResponse).toList();
    }
    @Transactional public GroupResponse createGroup(Long id, CreateGroupRequest request, Authentication authentication) {
        Tournament tournament = get(id);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);
        String name = request.name().trim();
        if (groups.findByTournamentIdAndName(id, name).isPresent()) throw new ConflictException("A group with that name already exists");
        TournamentGroup group = new TournamentGroup();
        group.setTournament(tournament);
        group.setName(name);
        TournamentGroup saved = groups.save(group);
        tournament.getGroups().add(saved);
        return groupResponse(saved);
    }
    @Transactional public GroupResponse assignParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication) {
        Tournament tournament = get(tournamentId);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);
        TournamentGroup target = groupForTournament(tournamentId, groupId);
        Participant participant = participants.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found: " + participantId));
        if (participant.getTournament() == null || !tournamentId.equals(participant.getTournament().getId())) {
            throw new BusinessException("Participant does not belong to this tournament");
        }

        Optional<TournamentGroup> currentGroup = groups.findByTournamentIdAndParticipantsId(tournamentId, participantId);
        if (currentGroup.isPresent() && currentGroup.get().getId().equals(groupId)) {
            return groupResponse(target); // already in the target group - nothing to move, skip the write
        }
        currentGroup.ifPresent(previous -> {
            previous.getParticipants().remove(participant);
            groups.save(previous);
        });

        target.getParticipants().add(participant); // Set semantics: re-adding an already-present participant is a safe no-op
        return groupResponse(groups.save(target));
    }
    @Transactional public void removeParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication) {
        Tournament tournament = get(tournamentId);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);
        TournamentGroup group = groupForTournament(tournamentId, groupId);
        boolean removed = group.getParticipants().removeIf(participant -> participantId.equals(participant.getId()));
        if (!removed) throw new ResourceNotFoundException("Participant is not assigned to this group: " + participantId);
        groups.save(group);
    }

    @Transactional public List<MatchResponse> generateBracket(Long id, Authentication authentication) {
        Tournament tournament = get(id); requireOwner(tournament, authentication); if (tournament.getFormat() != TournamentFormat.ELIMINATION && tournament.getFormat() != TournamentFormat.GROUPS) throw new BusinessException("Unsupported tournament format"); if (tournament.getParticipants().size() < 2) throw new BusinessException("At least two participants are required"); if (matches.existsByTournamentId(id)) throw new BusinessException("Bracket has already been generated");

        List<TournamentMatch> all;
        if (tournament.getFormat() == TournamentFormat.GROUPS) {
            List<TournamentGroup> tournamentGroups = groups.findByTournamentIdOrderByNameAsc(id);
            if (tournamentGroups.isEmpty()) throw new BusinessException("Create at least one group before generating matches");
            Set<Long> registered = tournament.getParticipants().stream().map(Participant::getId).collect(java.util.stream.Collectors.toSet());
            List<Long> assignedIds = tournamentGroups.stream().flatMap(group -> group.getParticipants().stream()).map(Participant::getId).toList();
            Set<Long> assigned = new HashSet<>(assignedIds);
            if (assignedIds.stream().anyMatch(participantId -> !registered.contains(participantId))) throw new BusinessException("A group contains a participant from another tournament");
            if (assigned.size() != registered.size() || assignedIds.size() != registered.size() || !assigned.containsAll(registered)) throw new BusinessException("Assign every participant to exactly one group before generating matches");
            all = bracketGenerator.generateGroupStage(tournament, tournamentGroups, matches::saveAll);
        } else {
            all = bracketGenerator.generate(tournament, tournament.getParticipants(), matches::saveAll);
            bracketGenerator.resolveReadyAndByes(all, matches::saveAll);
        }

        tournament.setStatus(TournamentStatus.IN_PROGRESS); tournaments.save(tournament);
        all.stream().filter(m -> m.getStatus() == MatchStatus.READY).forEach(this::notifyUpcomingMatch);
        return all.stream().map(this::matchResponse).toList();
    }
    @Transactional(readOnly = true) public List<MatchResponse> bracket(Long id) { return matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id).stream().map(this::matchResponse).toList(); }
    @Transactional public MatchResponse result(Long matchId, MatchResultRequest request, Authentication authentication) {
        TournamentMatch match = matches.findById(matchId).orElseThrow(() -> new ResourceNotFoundException("Match not found: " + matchId));
        Tournament tournament = match.getTournament();
        requireOwner(tournament, authentication);
        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessException("Results can only be entered while the tournament is in progress");
        }
        if (match.getStatus() != MatchStatus.READY) {
            throw new BusinessException("Match is not ready for a result");
        }
        if (match.getParticipant1() == null || match.getParticipant2() == null) {
            throw new BusinessException("Match participants are missing");
        }
        if (request.score1() == null || request.score2() == null) {
            throw new BusinessException("Both scores are required");
        }
        if (request.score1() < 0 || request.score2() < 0) {
            throw new BusinessException("Scores cannot be negative");
        }
        if (request.score1().equals(request.score2())) {
            throw new BusinessException("Ties are not supported - one participant must have a higher score");
        }

        match.setScore1(request.score1());
        match.setScore2(request.score2());
        Participant winner = request.score1() > request.score2() ? match.getParticipant1() : match.getParticipant2();
        match.setWinner(winner);
        match.setStatus(MatchStatus.COMPLETED);

        if (tournament.getFormat() == TournamentFormat.ELIMINATION) {
            bracketGenerator.advance(match, winner);
        }

        matches.save(match);
        notifyMatchResult(tournament, match, winner);

        // If advancing this result made the next bracket match ready to play, that is a brand-new
        // upcoming match that the two new participants (and the organizer) have not been told about yet.
        if (tournament.getFormat() == TournamentFormat.ELIMINATION) {
            TournamentMatch next = match.getNextMatch();
            if (next != null && next.getStatus() == MatchStatus.READY) {
                matches.save(next);
                notifyUpcomingMatch(next);
            }
        }

        // Works for both formats: the tournament is complete exactly when every one of its matches is COMPLETED.
        publishFinalResultsIfComplete(tournament);

        return matchResponse(match);
    }
    @Transactional(readOnly = true) public List<RankingResponse> rankings(Long id) { Tournament tournament = get(id); return rankingCalculator.calculate(tournament, matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id)); }
    private AppUser user(Authentication a) { return users.findByUsername(a.getName()).orElseThrow(() -> new ResourceNotFoundException("User not found")); }
    private void requireOwner(Tournament t, Authentication a) { boolean isAdmin = a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMINISTRATOR") || x.getAuthority().equals("ROLE_ADMIN")); if (!isAdmin && !t.getOrganizer().getUsername().equals(a.getName())) throw new UnauthorizedOperationException("You do not own this tournament"); }
    private TournamentResponse response(Tournament t) { return TournamentMapper.toResponse(t); }
    private ParticipantResponse participantResponse(Participant p) { return TournamentMapper.toResponse(p); }
    private MatchResponse matchResponse(TournamentMatch m) { return TournamentMapper.toResponse(m); }
    private GroupResponse groupResponse(TournamentGroup group) { return new GroupResponse(group.getId(), group.getName(), group.getParticipants().stream().map(this::participantResponse).toList()); }
    private TournamentGroup groupForTournament(Long tournamentId, Long groupId) {
        TournamentGroup group = groups.findById(groupId).orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));
        if (group.getTournament() == null || !tournamentId.equals(group.getTournament().getId())) throw new BusinessException("Group does not belong to this tournament");
        return group;
    }
    private void requireGroupTournament(Tournament tournament) { if (tournament.getFormat() != TournamentFormat.GROUPS) throw new BusinessException("Groups are only available for GROUPS tournaments"); }
    private void requireRegistration(Tournament tournament) { if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Group assignments are closed"); }

    // ===================== NOTIFICATIONS =====================
    // Every notification below is triggered by a real event (bracket generated, result recorded,
    // tournament completed) and carries real data pulled from the match/tournament entities involved.
    // Each creation path is guarded by an existsBy... check so the same event never notifies the same
    // person twice, even if this code path is somehow triggered again for the same match/tournament.

    private static final DateTimeFormatter NOTIFICATION_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /** Notifies both real participants (if they have linked accounts) and the organizer that a specific match is upcoming/ready to be played. */
    private void notifyUpcomingMatch(TournamentMatch match) {
        notifyParticipantUpcoming(match, match.getParticipant1(), match.getParticipant2());
        notifyParticipantUpcoming(match, match.getParticipant2(), match.getParticipant1());

        Tournament tournament = match.getTournament();
        AppUser organizer = tournament.getOrganizer();
        if (notifications.existsByRecipientAndMatchAndType(organizer, match, NotificationType.MATCH_SCHEDULED)) return;
        String message = "Upcoming match in \"%s\": Round %d, Match %d - %s vs %s%s".formatted(
                tournament.getName(), match.getRoundNumber(), match.getMatchNumber(),
                displayName(match.getParticipant1()), displayName(match.getParticipant2()), scheduleSuffix(match));
        saveNotification(organizer, message, NotificationType.MATCH_SCHEDULED, tournament, match);
    }

    private void notifyParticipantUpcoming(TournamentMatch match, Participant recipient, Participant opponent) {
        if (recipient == null || recipient.getAppUser() == null) return;
        AppUser user = recipient.getAppUser();
        if (notifications.existsByRecipientAndMatchAndType(user, match, NotificationType.MATCH_SCHEDULED)) return;
        Tournament tournament = match.getTournament();
        String message = "Upcoming match in \"%s\": Round %d, Match %d vs %s%s. Status: %s".formatted(
                tournament.getName(), match.getRoundNumber(), match.getMatchNumber(),
                displayName(opponent), scheduleSuffix(match), match.getStatus());
        saveNotification(user, message, NotificationType.MATCH_SCHEDULED, tournament, match);
    }

    /** Notifies the organizer (only) that a result was recorded. Participants are not notified of results by design - only upcoming matches and final results, per spec. */
    private void notifyMatchResult(Tournament tournament, TournamentMatch match, Participant winner) {
        AppUser organizer = tournament.getOrganizer();
        if (notifications.existsByRecipientAndMatchAndType(organizer, match, NotificationType.MATCH_RESULT)) return;
        String message = "Result recorded in \"%s\": Round %d, Match %d - %s %d : %d %s. Winner: %s".formatted(
                tournament.getName(), match.getRoundNumber(), match.getMatchNumber(),
                displayName(match.getParticipant1()), match.getScore1(), match.getScore2(),
                displayName(match.getParticipant2()), displayName(winner));
        saveNotification(organizer, message, NotificationType.MATCH_RESULT, tournament, match);
    }

    /** Publishes final results (once) to the organizer and every linked participant, but only once every match in the tournament has actually been completed. */
    private void publishFinalResultsIfComplete(Tournament tournament) {
        if (tournament.getStatus() == TournamentStatus.COMPLETED) return;
        List<TournamentMatch> all = matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(tournament.getId());
        if (all.isEmpty() || all.stream().anyMatch(m -> m.getStatus() != MatchStatus.COMPLETED)) return;

        tournament.setStatus(TournamentStatus.COMPLETED);
        tournaments.save(tournament);

        List<RankingResponse> standings = rankingCalculator.calculate(tournament, all);
        String winnerName = standings.isEmpty() ? "N/A" : standings.get(0).participant();
        String standingsText = standings.stream()
                .limit(3)
                .map(r -> r.placement() + ". " + r.participant() + " (" + r.wins() + "W-" + r.losses() + "L, " + r.points() + " pts)")
                .collect(Collectors.joining(" | "));
        String message = "Tournament \"%s\" is complete! Winner: %s. Final standings: %s".formatted(
                tournament.getName(), winnerName, standingsText.isEmpty() ? "not available" : standingsText);

        notifyFinalResults(tournament, tournament.getOrganizer(), message);
        for (Participant participant : tournament.getParticipants()) {
            if (participant.getAppUser() != null) {
                notifyFinalResults(tournament, participant.getAppUser(), message);
            }
        }
    }

    private void notifyFinalResults(Tournament tournament, AppUser recipient, String message) {
        if (notifications.existsByRecipientAndTournamentAndType(recipient, tournament, NotificationType.TOURNAMENT_COMPLETED)) return;
        saveNotification(recipient, message, NotificationType.TOURNAMENT_COMPLETED, tournament, null);
    }

    private void saveNotification(AppUser recipient, String message, NotificationType type, Tournament tournament, TournamentMatch match) {
        Notification notification = new Notification();
        notification.setRecipient(recipient);
        notification.setMessage(message);
        notification.setType(type);
        notification.setReadStatus(false);
        notification.setTournament(tournament);
        notification.setMatch(match);
        notifications.save(notification);
    }

    private String displayName(Participant p) { return p == null ? "TBD" : p.getName(); }
    private String scheduleSuffix(TournamentMatch match) {
        return match.getScheduledTime() != null ? " (scheduled " + match.getScheduledTime().format(NOTIFICATION_DATE_FORMAT) + ")" : " (date/time to be announced)";
    }
}
