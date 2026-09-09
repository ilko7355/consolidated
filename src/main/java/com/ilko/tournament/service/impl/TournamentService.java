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
        Tournament tournament = new Tournament(); 
        tournament.setName(request.name()); 
        tournament.setDescription(request.description()); 
        tournament.setFormat(request.format()); 
        tournament.setStartDate(request.startDate()); 
        tournament.setEndDate(request.endDate()); 
        tournament.setOrganizer(user(authentication)); 
        return response(tournaments.save(tournament));
    }

    @Transactional(readOnly = true) public List<TournamentResponse> list() { 
        return tournaments.findAllForList().stream().map(TournamentMapper::toResponse).toList(); 
    }

    @Transactional(readOnly = true) public Tournament get(Long id) { 
        return tournaments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tournament not found: " + id)); 
    }

    @Transactional(readOnly = true) public TournamentResponse getResponse(Long id) { 
        return response(get(id)); 
    }

    @Transactional public TournamentResponse update(Long id, UpdateTournamentRequest request, Authentication authentication) { 
        Tournament tournament = get(id); 
        requireOwner(tournament, authentication); 
        if (request.endDate().isBefore(request.startDate())) throw new BusinessException("End date cannot be before start date"); 
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Only registration tournaments can be updated"); 
        tournament.setName(request.name()); 
        tournament.setDescription(request.description()); 
        tournament.setStartDate(request.startDate()); 
        tournament.setEndDate(request.endDate()); 
        return response(tournaments.save(tournament)); 
    }

    @Transactional public void delete(Long id, Authentication authentication) { 
        Tournament tournament = get(id); 
        requireOwner(tournament, authentication); 
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Only registration tournaments can be deleted"); 
        tournament.getParticipants().clear(); 
        tournaments.delete(tournament); 
    }

    @Transactional public ParticipantResponse registerParticipant(Long id, ParticipantRequest request, Authentication authentication) {
        Tournament tournament = get(id); 
        requireOwner(tournament, authentication); 
        
        // ЗАЩИТА: Не може да се добавят хора, ако турнирът е започнал
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) {
            throw new BusinessException("Registration is closed. Tournament is already in progress or completed.");
        }

        if (tournament.getParticipants().stream().anyMatch(p -> p.getName().equalsIgnoreCase(request.name()))) {
            throw new ConflictException("Participant with this name is already registered in this tournament.");
        }

        Participant participant = new Participant();
        participant.setName(request.name());
        participant.setTournament(tournament);

        // ЗАЩИТА: Проверка дали потребителят съществува в системата
        if (request.username() != null && !request.username().isBlank()) {
            AppUser linkedAccount = users.findByUsername(request.username().trim())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.username()));
            participant.setAppUser(linkedAccount);
        }

        Participant saved = participants.save(participant);
        tournament.getParticipants().add(saved);
        return participantResponse(saved);
    }

    @Transactional(readOnly = true) public List<ParticipantResponse> registered(Long id) { 
        return get(id).getParticipants().stream().map(this::participantResponse).toList(); 
    }

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
        
        if (!tournamentId.equals(participant.getTournament().getId())) {
            throw new BusinessException("Participant does not belong to this tournament");
        }

        // Новата логика: задаваме групата директно на участника (Many-to-One)
        participant.setGroup(target);
        participants.save(participant);
        
        return groupResponse(target);
    }

    @Transactional public void removeParticipant(Long tournamentId, Long groupId, Long participantId, Authentication authentication) {
        Tournament tournament = get(tournamentId);
        requireOwner(tournament, authentication);
        requireGroupTournament(tournament);
        requireRegistration(tournament);
        
        Participant participant = participants.findById(participantId)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found"));

        if (participant.getGroup() == null || !participant.getGroup().getId().equals(groupId)) {
            throw new BusinessException("Participant is not assigned to this group.");
        }

        participant.setGroup(null);
        participants.save(participant);
    }

    @Transactional public List<MatchResponse> generateBracket(Long id, Authentication authentication) {
        Tournament tournament = get(id); 
        requireOwner(tournament, authentication); 
        
        if (tournament.getParticipants().size() < 2) throw new BusinessException("At least two participants are required"); 
        if (matches.existsByTournamentId(id)) throw new BusinessException("Bracket has already been generated");

        List<TournamentMatch> all;
        if (tournament.getFormat() == TournamentFormat.GROUPS) {
            List<TournamentGroup> tournamentGroups = groups.findByTournamentIdOrderByNameAsc(id);
            if (tournamentGroups.isEmpty()) throw new BusinessException("Create at least one group before generating matches");
            
            // ПРОВЕРКА: Всички участници трябва да са разпределени точно в една група
            long assignedCount = tournament.getParticipants().stream().filter(p -> p.getGroup() != null).count();
            if (assignedCount != tournament.getParticipants().size()) {
                throw new BusinessException("All participants must be assigned to a group before generating matches.");
            }

            all = bracketGenerator.generateGroupStage(tournament, tournamentGroups, matches::saveAll);
        } else if (tournament.getFormat() == TournamentFormat.ELIMINATION) {
            all = bracketGenerator.generate(tournament, tournament.getParticipants(), matches::saveAll);
            bracketGenerator.resolveReadyAndByes(all, matches::saveAll);
        } else {
            throw new BusinessException("Unsupported tournament format");
        }

        tournament.setStatus(TournamentStatus.IN_PROGRESS); 
        tournaments.save(tournament);
        
        all.stream().filter(m -> m.getStatus() == MatchStatus.READY).forEach(this::notifyUpcomingMatch);
        return all.stream().map(this::matchResponse).toList();
    }

    @Transactional(readOnly = true) public List<MatchResponse> bracket(Long id) { 
        return matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id).stream().map(this::matchResponse).toList(); 
    }

    @Transactional public MatchResponse result(Long matchId, MatchResultRequest request, Authentication authentication) {
        TournamentMatch match = matches.findById(matchId)
                .orElseThrow(() -> new ResourceNotFoundException("Match not found: " + matchId));
        
        Tournament tournament = match.getTournament();
        requireOwner(tournament, authentication);

        // ЗАЩИТА: Само турнири в ход и само готови мачове
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
        if (isTie && tournament.getFormat() == TournamentFormat.ELIMINATION) {
            throw new BusinessException("Ties are not allowed in elimination format! A winner must be decided.");
        }

        match.setScore1(request.score1());
        match.setScore2(request.score2());
        
        Participant winner = isTie ? null : (request.score1() > request.score2() ? match.getParticipant1() : match.getParticipant2());
        match.setWinner(winner);
        match.setStatus(MatchStatus.COMPLETED);

        if (tournament.getFormat() == TournamentFormat.ELIMINATION) {
            bracketGenerator.advance(match, winner);
            // Проверка за новоотключен мач след напредването
            TournamentMatch next = match.getNextMatch();
            if (next != null && next.getStatus() == MatchStatus.READY) {
                notifyUpcomingMatch(next);
            }
        }

        matches.save(match);
        notifyMatchResult(tournament, match, winner);
        publishFinalResultsIfComplete(tournament);

        return matchResponse(match);
    }

    @Transactional(readOnly = true) public List<RankingResponse> rankings(Long id) { 
        Tournament tournament = get(id); 
        return rankingCalculator.calculate(tournament, matches.findByTournamentIdOrderByRoundNumberAscMatchNumberAsc(id)); 
    }

    // --- Helpers ---

    private AppUser user(Authentication a) { 
        return users.findByUsername(a.getName()).orElseThrow(() -> new ResourceNotFoundException("User not found")); 
    }

    private void requireOwner(Tournament t, Authentication a) { 
        boolean isAdmin = a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMINISTRATOR")); 
        if (!isAdmin && !t.getOrganizer().getUsername().equals(a.getName())) 
            throw new UnauthorizedOperationException("You do not own this tournament"); 
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
        if (group.getTournament() == null || !tournamentId.equals(group.getTournament().getId())) 
            throw new BusinessException("Group does not belong to this tournament");
        return group;
    }

    private void requireGroupTournament(Tournament tournament) { 
        if (tournament.getFormat() != TournamentFormat.GROUPS) throw new BusinessException("Groups are only available for GROUPS tournaments"); 
    }

    private void requireRegistration(Tournament tournament) { 
        if (tournament.getStatus() != TournamentStatus.REGISTRATION) throw new BusinessException("Tournament is not in registration stage."); 
    }

    // --- Notifications ---

    private static final DateTimeFormatter NOTIFICATION_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private void notifyUpcomingMatch(TournamentMatch match) {
        notifyParticipantUpcoming(match, match.getParticipant1(), match.getParticipant2());
        notifyParticipantUpcoming(match, match.getParticipant2(), match.getParticipant1());

        Tournament tournament = match.getTournament();
        AppUser organizer = tournament.getOrganizer();
        if (notifications.existsByRecipientAndMatchAndType(organizer, match, NotificationType.MATCH_SCHEDULED)) return;
        
        String message = "Upcoming match in \"%s\": Round %d, Match %d - %s vs %s%s"
                .formatted(tournament.getName(), match.getRoundNumber(), match.getMatchNumber(),
                displayName(match.getParticipant1()), displayName(match.getParticipant2()), scheduleSuffix(match));
        saveNotification(organizer, message, NotificationType.MATCH_SCHEDULED, tournament, match);
    }

    private void notifyParticipantUpcoming(TournamentMatch match, Participant recipient, Participant opponent) {
        if (recipient == null || recipient.getAppUser() == null) return;
        AppUser user = recipient.getAppUser();
        if (notifications.existsByRecipientAndMatchAndType(user, match, NotificationType.MATCH_SCHEDULED)) return;
        
        Tournament tournament = match.getTournament();
        String message = "Upcoming match in \"%s\": Round %d, Match %d vs %s%s"
                .formatted(tournament.getName(), match.getRoundNumber(), match.getMatchNumber(),
                displayName(opponent), scheduleSuffix(match));
        saveNotification(user, message, NotificationType.MATCH_SCHEDULED, tournament, match);
    }

    private void notifyMatchResult(Tournament tournament, TournamentMatch match, Participant winner) {
        AppUser organizer = tournament.getOrganizer();
        if (notifications.existsByRecipientAndMatchAndType(organizer, match, NotificationType.MATCH_RESULT)) return;
        
        String outcome = winner != null ? "Winner: " + displayName(winner) : "Draw";
        String message = "Result recorded in \"%s\": Round %d, Match %d - %s %d:%d %s. %s"
                .formatted(tournament.getName(), match.getRoundNumber(), match.getMatchNumber(),
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
    
    private String scheduleSuffix(TournamentMatch match) {
        return match.getScheduledTime() != null 
                ? " (scheduled " + match.getScheduledTime().format(NOTIFICATION_DATE_FORMAT) + ")" 
                : " (date/time to be announced)";
    }
}