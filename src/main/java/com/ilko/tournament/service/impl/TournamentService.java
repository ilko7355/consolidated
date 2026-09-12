package com.ilko.tournament.service.impl;

import com.ilko.tournament.dto.*;
import com.ilko.tournament.entity.*;
import com.ilko.tournament.enums.*;
import com.ilko.tournament.exception.*;
import com.ilko.tournament.repository.*;
import com.ilko.tournament.mapper.TournamentMapper;
import com.ilko.tournament.service.TournamentServiceApi;
import com.ilko.tournament.service.BracketGenerator;
import com.ilko.tournament.service.DoubleEliminationGenerator;
import com.ilko.tournament.service.RankingCalculator;
import com.ilko.tournament.service.RoundRobinScheduler;
import com.ilko.tournament.service.TournamentStatisticsCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TournamentService implements TournamentServiceApi {
    private static final Comparator<TournamentMatch> BRACKET_ORDER =
            Comparator.comparingInt(TournamentMatch::getRoundNumber).thenComparingInt(TournamentMatch::getMatchNumber);

    private final BracketGenerator bracketGenerator;
    private final DoubleEliminationGenerator doubleEliminationGenerator;
    private final RoundRobinScheduler roundRobinScheduler;
    private final RankingCalculator rankingCalculator;
    private final TournamentStatisticsCalculator statisticsCalculator;
    private final TournamentRepository tournaments;
    private final AppUserRepository users;
    private final ParticipantRepository participants;
    private final TournamentMatchRepository matches;
    private final TournamentGroupRepository groups;
    private final NotificationRepository notifications;

    @Transactional
    public TournamentResponse create(CreateTournamentRequest request, Authentication authentication) {
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessException("End date cannot be before start date");
        }
        Tournament tournament = new Tournament();
        tournament.setName(request.name().trim());
        tournament.setDescription(request.description());
        tournament.setFormat(request.format());
        // A deciding rematch only exists in double elimination; ignore the flag for other formats.
        tournament.setGrandFinalReset(request.format() == TournamentFormat.DOUBLE_ELIMINATION && request.grandFinalReset());
        tournament.setStartDate(request.startDate());
        tournament.setEndDate(request.endDate());
        tournament.setOrganizer(user(authentication));
        return response(tournaments.save(tournament));
    }

    @Transactional(readOnly = true)
    public List<TournamentResponse> list() {
        return tournaments.findAllForList().stream().map(TournamentMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Tournament get(Long id) {
        return tournaments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + id));
    }

    @Transactional(readOnly = true)
    public TournamentResponse getResponse(Long id) {
        return response(get(id));
    }

    @Transactional
    public TournamentResponse update(Long id, UpdateTournamentRequest request, Authentication authentication) {
        Tournament tournament = get(id);
        requireOwner(tournament, authentication);
        if (request.endDate().isBefore(request.startDate())) {
            throw new BusinessException("End date cannot be before start date");
        }
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("Only registration tournaments can be updated");
        }
        tournament.setName(request.name().trim());
        tournament.setDescription(request.description());
        tournament.setStartDate(request.startDate());
        tournament.setEndDate(request.endDate());
        return response(tournaments.save(tournament));
    }

    @Transactional
    public void delete(Long id, Authentication authentication) {
        Tournament tournament = get(id);
        requireOwner(tournament, authentication);
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("Only registration tournaments can be deleted");
        }
        tournament.getParticipants().clear();
        tournaments.delete(tournament);
    }

    // --- Participants ---

    @Transactional
    public ParticipantResponse registerParticipant(Long id, ParticipantRequest request, Authentication authentication) {
        Tournament tournament = get(id);
        requireOwner(tournament, authentication);
        requireOpenRegistration(tournament);

        AppUser linkedAccount = null;
        if (request.username() != null && !request.username().isBlank()) {
            String username = request.username().trim();
            linkedAccount = users.findByUsername(username)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        }
        return participantResponse(addParticipant(tournament, request.name(), linkedAccount));
    }

    @Transactional
    public ParticipantResponse join(Long id, JoinTournamentRequest request, Authentication authentication) {
        Tournament tournament = get(id);
        requireOpenRegistration(tournament);
        AppUser account = user(authentication);
        boolean nameGiven = request != null && request.name() != null && !request.name().isBlank();
        return participantResponse(addParticipant(tournament, nameGiven ? request.name() : account.getUsername(), account));
    }

    @Transactional
    public void leave(Long id, Authentication authentication) {
        Tournament tournament = get(id);
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("You can only withdraw while registration is open.");
        }
        Participant entry = findEntry(tournament, authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("You are not registered in this tournament."));
        if (entry.getGroup() != null) {
            entry.getGroup().getParticipants().remove(entry);
            entry.setGroup(null);
        }
        tournament.getParticipants().remove(entry); // orphanRemoval deletes the row
    }

    @Transactional(readOnly = true)
    public List<ParticipantResponse> registered(Long id) {
        return get(id).getParticipants().stream().map(this::participantResponse).toList();
    }

    // --- Groups ---

    @Transactional(readOnly = true)
    public List<GroupResponse> groups(Long id) {
        Tournament tournament = get(id);
        if (tournament.getFormat() != TournamentFormat.GROUPS) {
            throw new BusinessException("Groups are only available for GROUPS tournaments");
        }
        return groups.findByTournamentIdOrderByNameAsc(id).stream().map(this::groupResponse).toList();
    }

    @Transactional
    public GroupResponse createGroup(Long id, CreateGroupRequest request, Authentication authentication) {
        Tournament tournament = get(id);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);

        String name = request.name().trim();
        if (groups.findByTournamentIdAndName(id, name).isPresent()) {
            throw new ConflictException("A group with that name already exists");
        }

        TournamentGroup group = new TournamentGroup();
        group.setTournament(tournament);
        group.setName(name);
        TournamentGroup saved = groups.save(group);
        tournament.getGroups().add(saved);
        return groupResponse(saved);
    }

    @Transactional
    public GroupResponse assignParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication) {
        Tournament tournament = get(tournamentId);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);

        TournamentGroup target = groupForTournament(tournamentId, groupId);
        Participant participant = participantForTournament(tournamentId, participantId);

        TournamentGroup previous = participant.getGroup();
        if (previous != null && previous.getId().equals(groupId)) {
            return groupResponse(target); // assigning to the same group is a no-op
        }
        // Keep both sides of the association in step, so the response and later reads in this
        // transaction already see the move.
        if (previous != null) {
            previous.getParticipants().remove(participant);
        }
        participant.setGroup(target);
        target.getParticipants().add(participant);
        participants.save(participant);
        return groupResponse(target);
    }

    @Transactional
    public void removeParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication) {
        Tournament tournament = get(tournamentId);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);

        Participant participant = participantForTournament(tournamentId, participantId);
        TournamentGroup group = participant.getGroup();
        if (group == null || !group.getId().equals(groupId)) {
            throw new BusinessException("Participant is not assigned to this group.");
        }
        group.getParticipants().remove(participant);
        participant.setGroup(null);
        participants.save(participant);
    }

    // --- Matches ---

    @Transactional
    public List<MatchResponse> generateBracket(Long id, Authentication authentication) {
        Tournament tournament = get(id);
        requireOwner(tournament, authentication);
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("Matches can only be generated while the tournament is in registration.");
        }
        if (tournament.getParticipants().size() < 2) {
            throw new BusinessException("At least two participants are required");
        }
        if (matches.existsByTournamentId(id)) {
            throw new BusinessException("Bracket has already been generated");
        }

        // Registration order is the seeding order: the first registered participant is seed 1.
        List<Participant> seeding = tournament.getParticipants().stream()
                .sorted(Comparator.comparing(Participant::getId, Comparator.nullsLast(Comparator.<Long>naturalOrder())))
                .toList();
        List<TournamentMatch> generated = switch (tournament.getFormat()) {
            case GROUPS -> groupMatches(tournament, seeding);
            case DOUBLE_ELIMINATION -> doubleEliminationGenerator.generate(tournament, seeding);
            case ELIMINATION -> bracketGenerator.generate(tournament, seeding);
        };

        // Every match row points at its next-round match, so later rounds are stored first.
        matches.saveAll(generated.stream().sorted(BRACKET_ORDER.reversed()).toList());
        tournament.setStatus(TournamentStatus.IN_PROGRESS);
        tournaments.save(tournament);

        List<TournamentMatch> ordered = generated.stream().sorted(BRACKET_ORDER).toList();
        ordered.stream().filter(m -> m.getStatus() == MatchStatus.READY).forEach(this::notifyUpcomingMatch);
        return ordered.stream().map(this::matchResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<MatchResponse> bracket(Long id) {
        return matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id).stream().map(this::matchResponse).toList();
    }

    /** Completed matches that were actually played; BYE advances have no scores and are left out. */
    @Transactional(readOnly = true)
    public List<MatchResponse> results(Long id) {
        get(id);
        return matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id).stream()
                .filter(m -> m.getStatus() == MatchStatus.COMPLETED && m.getScore1() != null && m.getScore2() != null)
                .map(this::matchResponse)
                .toList();
    }

    @Transactional
    public MatchResponse result(Long matchId, MatchResultRequest request, Authentication authentication) {
        TournamentMatch match = matches.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Match not found: " + matchId));

        Tournament tournament = match.getTournament();
        requireOwner(tournament, authentication);

        if (tournament.getStatus() != TournamentStatus.IN_PROGRESS) {
            throw new BusinessException("Results can only be entered while the tournament is in progress.");
        }
        if (match.getStatus() != MatchStatus.READY) {
            throw new BusinessException("This match is not ready for a result (waiting for previous rounds).");
        }
        if (request.score1() < 0 || request.score2() < 0) {
            throw new BusinessException("Scores cannot be negative.");
        }
        boolean isTie = request.score1().equals(request.score2());
        if (isTie && tournament.getFormat() != TournamentFormat.GROUPS) {
            throw new BusinessException("Ties are not allowed in elimination format! A winner must be decided.");
        }

        match.setScore1(request.score1());
        match.setScore2(request.score2());
        Participant winner = isTie ? null : (request.score1() > request.score2() ? match.getParticipant1() : match.getParticipant2());
        match.setWinner(winner);
        match.setStatus(MatchStatus.COMPLETED);
        matches.save(match);

        if (tournament.getFormat() != TournamentFormat.GROUPS) {
            List<TournamentMatch> bracket = matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(tournament.getId());
            List<TournamentMatch> becameReady = tournament.getFormat() == TournamentFormat.DOUBLE_ELIMINATION
                    ? doubleEliminationGenerator.advance(bracket, match)
                    : bracketGenerator.advance(bracket, match);
            matches.saveAll(bracket);
            becameReady.forEach(this::notifyUpcomingMatch);
        }

        notifyMatchResult(tournament, match, winner);
        publishFinalResultsIfComplete(tournament);
        return matchResponse(match);
    }

    @Transactional(readOnly = true)
    public List<RankingResponse> rankings(Long id) {
        Tournament tournament = get(id);
        return rankingCalculator.calculate(tournament, matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id));
    }

    @Transactional(readOnly = true)
    public TournamentStatisticsResponse statistics(Long id) {
        Tournament tournament = get(id);
        List<TournamentMatch> all = matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id);
        return statisticsCalculator.calculate(tournament, all, rankingCalculator.calculate(tournament, all));
    }

    @Transactional(readOnly = true)
    public List<PlayerMatchResponse> myMatches(Authentication authentication) {
        String username = authentication.getName();
        return matches.findForPlayer(username).stream().map(match -> TournamentMapper.toPlayerResponse(match, username)).toList();
    }

    // --- Helpers ---

    private List<TournamentMatch> groupMatches(Tournament tournament, List<Participant> seeding) {
        List<TournamentGroup> tournamentGroups = groups.findByTournamentIdOrderByNameAsc(tournament.getId());
        if (tournamentGroups.isEmpty()) {
            throw new BusinessException("Create at least one group before generating matches");
        }
        if (seeding.stream().anyMatch(p -> p.getGroup() == null)) {
            throw new BusinessException("All participants must be assigned to a group before generating matches.");
        }

        List<TournamentMatch> generated = new ArrayList<>();
        Map<Integer, Integer> matchesPerRound = new HashMap<>();
        for (TournamentGroup group : tournamentGroups) {
            // Compare ids, not instances: a participant's group may be a lazy proxy of the same row.
            List<Participant> members = seeding.stream().filter(p -> group.getId().equals(p.getGroup().getId())).toList();
            if (members.size() == 1) {
                throw new BusinessException("Group \"%s\" needs at least two participants.".formatted(group.getName()));
            }
            List<List<int[]>> rounds = roundRobinScheduler.schedule(members.size());
            for (int round = 1; round <= rounds.size(); round++) {
                for (int[] pair : rounds.get(round - 1)) {
                    TournamentMatch match = new TournamentMatch();
                    match.setTournament(tournament);
                    match.setGroup(group);
                    match.setRoundNumber(round);
                    match.setMatchNumber(matchesPerRound.merge(round, 1, Integer::sum));
                    match.setParticipant1(members.get(pair[0]));
                    match.setParticipant2(members.get(pair[1]));
                    match.setStatus(MatchStatus.READY);
                    generated.add(match);
                }
            }
        }
        return generated;
    }

    private Participant addParticipant(Tournament tournament, String requestedName, AppUser account) {
        String name = requestedName.trim();
        if (account != null && findEntry(tournament, account.getUsername()).isPresent()) {
            throw new ConflictException("Account '" + account.getUsername() + "' is already registered in this tournament.");
        }
        if (tournament.getParticipants().stream().anyMatch(p -> p.getName().equalsIgnoreCase(name))) {
            throw new ConflictException("Participant with this name is already registered in this tournament.");
        }

        Participant participant = new Participant();
        participant.setName(name);
        participant.setTournament(tournament);
        participant.setAppUser(account);
        Participant saved = participants.save(participant);
        tournament.getParticipants().add(saved);
        return saved;
    }

    private Optional<Participant> findEntry(Tournament tournament, String username) {
        return tournament.getParticipants().stream()
                .filter(p -> p.getAppUser() != null && username.equals(p.getAppUser().getUsername()))
                .findFirst();
    }

    private AppUser user(Authentication a) {
        return users.findByUsername(a.getName()).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void requireOwner(Tournament t, Authentication a) {
        boolean isAdmin = a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMINISTRATOR"));
        if (!isAdmin && !t.getOrganizer().getUsername().equals(a.getName())) {
            throw new UnauthorizedOperationException("You do not own this tournament");
        }
    }

    private TournamentResponse response(Tournament t) { return TournamentMapper.toResponse(t); }
    private ParticipantResponse participantResponse(Participant p) { return TournamentMapper.toResponse(p); }
    private MatchResponse matchResponse(TournamentMatch m) { return TournamentMapper.toResponse(m); }

    private GroupResponse groupResponse(TournamentGroup group) {
        return new GroupResponse(group.getId(), group.getName(),
                group.getParticipants().stream().map(this::participantResponse).toList());
    }

    private TournamentGroup groupForTournament(Long tournamentId, Long groupId) {
        TournamentGroup group = groups.findById(groupId).orElseThrow(() -> new ResourceNotFoundException("Group not found: " + groupId));
        if (group.getTournament() == null || !tournamentId.equals(group.getTournament().getId())) {
            throw new BusinessException("Group does not belong to this tournament");
        }
        return group;
    }

    private Participant participantForTournament(Long tournamentId, Long participantId) {
        Participant participant = participants.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found: " + participantId));
        if (!tournamentId.equals(participant.getTournament().getId())) {
            throw new BusinessException("Participant does not belong to this tournament");
        }
        return participant;
    }

    private void requireGroupTournament(Tournament tournament) {
        if (tournament.getFormat() != TournamentFormat.GROUPS) {
            throw new BusinessException("Groups are only available for GROUPS tournaments");
        }
    }

    private void requireRegistration(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("Tournament is not in registration stage.");
        }
    }

    private void requireOpenRegistration(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("Registration is closed. Tournament is already in progress or completed.");
        }
    }

    // --- Notifications ---

    private static final DateTimeFormatter NOTIFICATION_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private void notifyUpcomingMatch(TournamentMatch match) {
        notifyParticipantUpcoming(match, match.getParticipant1(), match.getParticipant2());
        notifyParticipantUpcoming(match, match.getParticipant2(), match.getParticipant1());

        Tournament tournament = match.getTournament();
        AppUser organizer = tournament.getOrganizer();
        if (notifications.existsByRecipientAndMatchAndType(organizer, match, NotificationType.MATCH_SCHEDULED)) return;

        String message = "Upcoming match in \"%s\": %s, Match %d - %s vs %s%s"
                .formatted(tournament.getName(), stage(match), match.getMatchNumber(),
                displayName(match.getParticipant1()), displayName(match.getParticipant2()), scheduleSuffix(match));
        saveNotification(organizer, message, NotificationType.MATCH_SCHEDULED, tournament, match);
    }

    private void notifyParticipantUpcoming(TournamentMatch match, Participant recipient, Participant opponent) {
        if (recipient == null || recipient.getAppUser() == null) return;
        AppUser user = recipient.getAppUser();
        if (notifications.existsByRecipientAndMatchAndType(user, match, NotificationType.MATCH_SCHEDULED)) return;

        Tournament tournament = match.getTournament();
        String message = "Upcoming match in \"%s\": %s, Match %d vs %s%s"
                .formatted(tournament.getName(), stage(match), match.getMatchNumber(),
                displayName(opponent), scheduleSuffix(match));
        saveNotification(user, message, NotificationType.MATCH_SCHEDULED, tournament, match);
    }

    private void notifyMatchResult(Tournament tournament, TournamentMatch match, Participant winner) {
        AppUser organizer = tournament.getOrganizer();
        if (notifications.existsByRecipientAndMatchAndType(organizer, match, NotificationType.MATCH_RESULT)) return;

        String outcome = winner != null ? "Winner: " + displayName(winner) : "Draw";
        String message = "Result recorded in \"%s\": %s, Match %d - %s %d:%d %s. %s"
                .formatted(tournament.getName(), stage(match), match.getMatchNumber(),
                displayName(match.getParticipant1()), match.getScore1(), match.getScore2(),
                displayName(match.getParticipant2()), outcome);
        saveNotification(organizer, message, NotificationType.MATCH_RESULT, tournament, match);
    }

    private void publishFinalResultsIfComplete(Tournament tournament) {
        if (tournament.getStatus() == TournamentStatus.COMPLETED) return;
        List<TournamentMatch> all = matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(tournament.getId());
        if (all.isEmpty() || all.stream().anyMatch(m -> m.getStatus() != MatchStatus.COMPLETED)) return;

        tournament.setStatus(TournamentStatus.COMPLETED);
        tournaments.save(tournament);

        List<RankingResponse> standings = rankingCalculator.calculate(tournament, all);
        String winnerName = standings.isEmpty() ? "N/A" : standings.get(0).participant();
        String message = "Tournament \"%s\" is complete! Winner: %s.".formatted(tournament.getName(), winnerName);

        notifyFinalResults(tournament, tournament.getOrganizer(), message);
        tournament.getParticipants().stream()
            .filter(p -> p.getAppUser() != null)
            .forEach(p -> notifyFinalResults(tournament, p.getAppUser(), message));
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

    private String stage(TournamentMatch match) {
        if (match.getGroup() != null) {
            return match.getGroup().getName() + ", Round " + match.getRoundNumber();
        }
        if (match.getBracket() == null) {
            return "Round " + match.getRoundNumber();
        }
        // Winners rounds keep their own numbering; losers rounds are numbered after them, so naming the
        // bracket is clearer here than a round number the participant never sees on screen.
        return switch (match.getBracket()) {
            case WINNERS -> "Winners bracket, Round " + match.getRoundNumber();
            case LOSERS -> "Losers bracket";
            case GRAND_FINAL -> "Grand final";
        };
    }

    private String scheduleSuffix(TournamentMatch match) {
        return match.getScheduledTime() != null
                ? " (scheduled " + match.getScheduledTime().format(NOTIFICATION_DATE_FORMAT) + ")"
                : " (date/time to be announced)";
    }
}
