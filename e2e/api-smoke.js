// End-to-end API check against a RUNNING backend connected to a real MySQL database.
//
// Start the backend on a fresh, empty database with demo data enabled, then run:
//   API_URL=http://localhost:8080/api node e2e/api-smoke.js
// The script uses the demo accounts (password Demo12345) and creates one extra tournament, "API Smoke Cup",
// so run it against a disposable database rather than the one used for presentations.
const API = (process.env.API_URL || 'http://localhost:8080/api').replace(/\/$/, '');
const PASSWORD = process.env.DEMO_PASSWORD || 'Demo12345';

const results = [];
function check(name, pass, details = '') {
    results.push({name, pass: Boolean(pass)});
    console.log(`[${pass ? 'PASS' : 'FAIL'}] ${name}${details ? ` - ${details}` : ''}`);
}

async function call(method, path, user, body) {
    const headers = {};
    if (user) headers.Authorization = `Basic ${Buffer.from(`${user}:${PASSWORD}`).toString('base64')}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(API + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return {status: response.status, data};
}
const get = (path, user) => call('GET', path, user);
const today = new Date().toLocaleDateString('sv-SE'); // yyyy-mm-dd in local time
const winnerName = (m) => (m.winnerId === m.participant1Id ? m.participant1 : m.participant2);

(async () => {
    // --- authentication
    const organizerLogin = await call('POST', '/auth/login', null, {username: 'organizer', password: PASSWORD});
    check('Organizer signs in and receives the ORGANIZER role', organizerLogin.status === 200 && organizerLogin.data.role === 'ORGANIZER', `status ${organizerLogin.status}`);
    check('Wrong password is rejected with 401', (await call('POST', '/auth/login', null, {username: 'organizer', password: 'wrong-password'})).status === 401);
    check('Protected endpoint without credentials returns 401', (await get('/tournaments')).status === 401);

    // --- demo data
    const list = (await get('/tournaments', 'organizer')).data;
    const byName = (name) => list.find(t => t.name === name);
    const chess = byName('Stara Zagora Rapid Chess Open');
    const esports = byName('PGKNMA Esports Cup');
    const league = byName('School Table Tennis League');
    const futsal = byName('Autumn Futsal Cup');
    check('Demo data has a tournament in every lifecycle stage',
        chess?.status === 'COMPLETED' && esports?.status === 'IN_PROGRESS' && league?.status === 'IN_PROGRESS' && futsal?.status === 'REGISTRATION',
        list.map(t => `${t.name}=${t.status}`).join(', '));

    // --- bracket stored in MySQL: 6 teams -> 8 slots, BYEs for the top two seeds, nothing decided early
    const esportsBracket = (await get(`/tournaments/${esports.id}/bracket`, 'georgi')).data;
    const byes = esportsBracket.filter(m => m.status === 'COMPLETED' && m.score1 == null);
    check('6-team bracket: 7 matches, 2 round-one BYEs', esportsBracket.length === 7 && byes.length === 2 && byes.every(m => m.round === 1));
    check('BYEs go to seeds 1 and 2', byes.map(winnerName).sort().join(',') === ['Nova Esports', 'Thracian Wolves'].sort().join(','), byes.map(winnerName).join(', '));
    const esportsFinal = esportsBracket.find(m => m.round === 3);
    check('Final waits for the unplayed semi-final instead of being awarded early', esportsFinal.status === 'PENDING' && esportsFinal.winnerId == null);

    // --- completed knockout: statistics and rankings agree with the stored final
    const chessBracket = (await get(`/tournaments/${chess.id}/bracket`, 'elena')).data;
    const chessFinal = chessBracket.find(m => m.round === 3);
    const chessStats = (await get(`/tournaments/${chess.id}/statistics`, 'elena')).data;
    const chessRankings = (await get(`/tournaments/${chess.id}/rankings`, 'elena')).data;
    check('8-player knockout: 7 played matches, 0 BYEs', chessStats.playedMatches === 7 && chessStats.byes === 0);
    check('Champion equals the winner of the final', chessStats.champion === winnerName(chessFinal), `champion ${chessStats.champion}`);
    check('Rankings start with the champion', chessRankings[0].participant === winnerName(chessFinal));

    // --- group stage
    const leagueMatches = (await get(`/tournaments/${league.id}/bracket`, 'coach.maria')).data;
    const leagueStats = (await get(`/tournaments/${league.id}/statistics`, 'coach.maria')).data;
    check('League: 12 matches, 7 played including 1 draw', leagueMatches.length === 12 && leagueStats.playedMatches === 7 && leagueStats.draws === 1,
        `played ${leagueStats.playedMatches}, draws ${leagueStats.draws}`);
    const pairs = new Set();
    let scheduleValid = true;
    for (const round of new Set(leagueMatches.map(m => `${m.groupName}|${m.round}`))) {
        const busy = new Set();
        for (const m of leagueMatches.filter(x => `${x.groupName}|${x.round}` === round)) {
            if (busy.has(m.participant1Id) || busy.has(m.participant2Id)) scheduleValid = false;
            busy.add(m.participant1Id).add(m.participant2Id);
            const key = [m.participant1Id, m.participant2Id].sort((a, b) => a - b).join('-');
            if (pairs.has(key)) scheduleValid = false;
            pairs.add(key);
        }
    }
    check('League schedule: every pair meets once, nobody plays twice in a round', scheduleValid && pairs.size === 12);

    // --- participant self-service
    const join = await call('POST', `/tournaments/${futsal.id}/join`, 'georgi', {name: 'Georgi Petrov'});
    check('Participant joins an open tournament (201)', join.status === 201 && join.data.linkedUsername === 'georgi', `status ${join.status}`);
    check('Joining the same tournament twice is a conflict (409)', (await call('POST', `/tournaments/${futsal.id}/join`, 'georgi', {})).status === 409);
    check('Organizer cannot use the participant join endpoint (403)', (await call('POST', `/tournaments/${futsal.id}/join`, 'organizer', {})).status === 403);
    check('Joining a tournament in progress is rejected (400)', (await call('POST', `/tournaments/${esports.id}/join`, 'elena', {})).status === 400);
    check('Participant withdraws while registration is open (204)', (await call('DELETE', `/tournaments/${futsal.id}/join`, 'georgi')).status === 204);
    const myMatches = (await get('/matches/mine', 'georgi')).data;
    check(`"My matches" returns matches from all four of georgi’s tournaments`, new Set(myMatches.map(m => m.tournamentName)).size === 4,
        [...new Set(myMatches.map(m => m.tournamentName))].join(', '));

    // --- authorization
    check('Participant cannot create a tournament (403)', (await call('POST', '/tournaments', 'georgi', {name: 'X', format: 'ELIMINATION', startDate: today, endDate: today})).status === 403);
    const leagueReady = leagueMatches.find(m => m.status === 'READY');
    check("Organizer cannot enter results in another organizer's tournament (403)", (await call('POST', `/matches/${leagueReady.id}/result`, 'organizer', {score1: 1, score2: 0})).status === 403);
    const adminUsers = await get('/admin/users', 'admin');
    check('Administrator lists accounts; no password field is exposed', adminUsers.status === 200 && adminUsers.data.length >= 15 && adminUsers.data.every(u => !('password' in u)));
    check('Organizer cannot open administration (403)', (await get('/admin/users', 'organizer')).status === 403);

    // --- blocking accounts
    const elena = adminUsers.data.find(u => u.username === 'elena');
    const admin = adminUsers.data.find(u => u.username === 'admin');
    check('Administrator blocks an account', (await call('PUT', `/admin/users/${elena.id}/status`, 'admin', {enabled: false})).status === 200);
    const blockedLogin = await call('POST', '/auth/login', null, {username: 'elena', password: PASSWORD});
    check('Blocked account cannot sign in (403)', blockedLogin.status === 403, blockedLogin.data?.message);
    check('Blocked account cannot call the API either (401)', (await get('/tournaments', 'elena')).status === 401);
    check('Administrator unblocks the account', (await call('PUT', `/admin/users/${elena.id}/status`, 'admin', {enabled: true})).status === 200);
    check('Unblocked account signs in again', (await call('POST', '/auth/login', null, {username: 'elena', password: PASSWORD})).status === 200);
    check('Administrator cannot change their own role (400)', (await call('PUT', `/admin/users/${admin.id}/role`, 'admin', {role: 'PARTICIPANT'})).status === 400);

    // --- full lifecycle of a new 5-player knockout, results entered in random order
    const created = await call('POST', '/tournaments', 'organizer', {name: 'API Smoke Cup', description: 'Created by e2e/api-smoke.js', format: 'ELIMINATION', startDate: today, endDate: today});
    check('Organizer creates a tournament (201, REGISTRATION)', created.status === 201 && created.data.status === 'REGISTRATION', `status ${created.status}`);
    const id = created.data.id;
    const names = ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo'];
    let registered = 0;
    for (const name of names) {
        if ((await call('POST', `/tournaments/${id}/participants`, 'organizer', {name})).status === 201) registered++;
    }
    check('Five participants registered (201 each)', registered === 5);
    check('Duplicate name, different case, is rejected (409)', (await call('POST', `/tournaments/${id}/participants`, 'organizer', {name: 'alpha'})).status === 409);
    check('End date before start date is rejected (400)', (await call('POST', '/tournaments', 'organizer', {name: 'Bad dates', format: 'GROUPS', startDate: today, endDate: '2000-01-01'})).status === 400);

    const generated = await call('POST', `/tournaments/${id}/generate-bracket`, 'organizer');
    const generatedByes = (generated.data || []).filter(m => m.status === 'COMPLETED');
    check('5 participants -> 7 matches, BYEs for seeds 1-3', generated.status === 200 && generated.data.length === 7 && generatedByes.length === 3
        && generatedByes.map(winnerName).sort().join(',') === 'Alpha,Bravo,Charlie', generatedByes.map(winnerName).join(', '));
    check('Generating a second time is rejected (400)', (await call('POST', `/tournaments/${id}/generate-bracket`, 'organizer')).status === 400);

    const firstReady = (await get(`/tournaments/${id}/bracket`, 'organizer')).data.find(m => m.status === 'READY');
    check('A draw in a knockout match is rejected (400)', (await call('POST', `/matches/${firstReady.id}/result`, 'organizer', {score1: 2, score2: 2})).status === 400);
    check('Negative score is rejected (400)', (await call('POST', `/matches/${firstReady.id}/result`, 'organizer', {score1: -1, score2: 2})).status === 400);

    let games = 0;
    let lastPlayed = null;
    for (let guard = 0; guard < 20; guard++) {
        const ready = (await get(`/tournaments/${id}/bracket`, 'organizer')).data.filter(m => m.status === 'READY');
        if (!ready.length) break;
        const match = ready[Math.floor(Math.random() * ready.length)];
        const response = await call('POST', `/matches/${match.id}/result`, 'organizer', Math.random() < 0.5 ? {score1: 3, score2: 1} : {score1: 0, score2: 2});
        if (response.status !== 200) { check('Result accepted', false, `status ${response.status}`); break; }
        games++;
        lastPlayed = match;
    }
    const finished = (await get(`/tournaments/${id}`, 'organizer')).data;
    check('Tournament completes after exactly n - 1 = 4 played matches', finished.status === 'COMPLETED' && games === 4, `status ${finished.status}, games ${games}`);
    const smokeBracket = (await get(`/tournaments/${id}/bracket`, 'organizer')).data;
    const smokeStats = (await get(`/tournaments/${id}/statistics`, 'organizer')).data;
    check('Champion is published and equals the winner of the final', smokeStats.champion === winnerName(smokeBracket.find(m => m.round === 3)), `champion ${smokeStats.champion}`);
    const organizerNotifications = (await get('/notifications', 'organizer')).data;
    check('Organizer received the final-results notification', organizerNotifications.some(n => n.type === 'TOURNAMENT_COMPLETED' && n.tournamentId === id));
    check('Result for an already completed match is rejected (400)', (await call('POST', `/matches/${lastPlayed.id}/result`, 'organizer', {score1: 9, score2: 0})).status === 400);
    check('Completed tournament cannot be deleted (400)', (await call('DELETE', `/tournaments/${id}`, 'organizer')).status === 400);

    // --- demo double-elimination tournament: the losers bracket is stored and linked in MySQL
    const blitz = byName('Winter Blitz Chess Cup');
    const blitzBracket = (await get(`/tournaments/${blitz.id}/bracket`, 'georgi')).data;
    check('Demo double-elimination tournament exists and is in progress', blitz?.status === 'IN_PROGRESS' && blitz.format === 'DOUBLE_ELIMINATION',
        `${blitz?.format}=${blitz?.status}`);
    check('6 players -> 14 bracket matches plus a deciding rematch, in three parts',
        blitzBracket.length === 15
        && blitzBracket.filter(m => m.bracket === 'WINNERS').length === 7
        && blitzBracket.filter(m => m.bracket === 'LOSERS').length === 6
        && blitzBracket.filter(m => m.bracket === 'GRAND_FINAL').length === 2,
        blitzBracket.map(m => m.bracket).join(','));
    check('A beaten player is already waiting in the losers bracket',
        blitzBracket.some(m => m.bracket === 'LOSERS' && (m.participant1 || m.participant2)));

    // --- full lifecycle of a double-elimination tournament with the deciding rematch enabled
    const deCreated = await call('POST', '/tournaments', 'organizer', {name: 'API Smoke Double Cup', description: 'Created by e2e/api-smoke.js',
        format: 'DOUBLE_ELIMINATION', startDate: today, endDate: today, grandFinalReset: true});
    check('Organizer creates a double-elimination tournament (201)', deCreated.status === 201 && deCreated.data.grandFinalReset === true,
        `status ${deCreated.status}, reset ${deCreated.data?.grandFinalReset}`);
    const deId = deCreated.data.id;
    for (const name of ['Ana', 'Boris', 'Ceco', 'Dani', 'Emil', 'Filip']) {
        await call('POST', `/tournaments/${deId}/participants`, 'organizer', {name});
    }
    const deGenerated = await call('POST', `/tournaments/${deId}/generate-bracket`, 'organizer');
    check('Generated bracket is persisted with its winner and loser links intact', deGenerated.status === 200 && deGenerated.data.length === 15,
        `${deGenerated.data?.length} matches`);

    let deGames = 0;
    const losses = new Map();
    for (let guard = 0; guard < 40; guard++) {
        const ready = (await get(`/tournaments/${deId}/bracket`, 'organizer')).data.filter(m => m.status === 'READY');
        if (!ready.length) break;
        const match = ready[Math.floor(Math.random() * ready.length)];
        const firstWins = Math.random() < 0.5;
        const response = await call('POST', `/matches/${match.id}/result`, 'organizer', firstWins ? {score1: 3, score2: 1} : {score1: 0, score2: 2});
        if (response.status !== 200) { check('Double-elimination result accepted', false, `status ${response.status}`); break; }
        const beaten = firstWins ? match.participant2 : match.participant1;
        losses.set(beaten, (losses.get(beaten) || 0) + 1);
        deGames++;
    }
    const deFinished = (await get(`/tournaments/${deId}`, 'organizer')).data;
    const deStats = (await get(`/tournaments/${deId}/statistics`, 'organizer')).data;
    const deBracket = (await get(`/tournaments/${deId}/bracket`, 'organizer')).data;
    check('Double-elimination tournament completes', deFinished.status === 'COMPLETED', `status ${deFinished.status}, ${deGames} games`);
    check('Everyone except the champion was eliminated by a second loss, never the first',
        [...losses.entries()].filter(([name]) => name !== deStats.champion).every(([, count]) => count === 2),
        [...losses.entries()].map(([name, count]) => `${name}:${count}`).join(', '));
    check('Champion is published and had at most one loss', Boolean(deStats.champion) && (losses.get(deStats.champion) || 0) <= 1,
        `${deStats.champion} lost ${losses.get(deStats.champion) || 0}`);
    const rematch = deBracket.filter(m => m.bracket === 'GRAND_FINAL').sort((a, b) => a.round - b.round)[1];
    const rematchPlayed = rematch.score1 != null;
    check('The deciding rematch was played only if the losers-bracket finalist won the grand final',
        rematchPlayed !== (rematch.participant1 == null && rematch.participant2 == null && rematch.winnerId == null),
        rematchPlayed ? 'played' : 'not needed');
    check('No match is left unfinished in a completed tournament', deBracket.every(m => m.status === 'COMPLETED'));
    check('Rankings put the champion first', (await get(`/tournaments/${deId}/rankings`, 'organizer')).data[0].participant === deStats.champion);

    const passed = results.filter(r => r.pass).length;
    console.log(`\n=== SUMMARY ===\n${passed}/${results.length} checks passed`);
    process.exit(passed === results.length ? 0 : 1);
})().catch((error) => {
    console.error(error);
    process.exit(1);
});
