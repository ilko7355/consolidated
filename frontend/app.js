/**
 * Tournament Platform - browser client.
 *
 * A dependency-free single-page application on top of the Spring Boot REST API. Protected calls send
 * HTTP Basic credentials that are kept in memory only, so reloading the page signs the user out.
 * The interface adapts to the signed-in role, but every permission is enforced again by the backend.
 */

const API = (window.__API_URL__ || 'http://localhost:8080/api').replace(/\/$/, '');
const BRACKET_POLL_INTERVAL_MS = 6000;
const NOTIFICATION_POLL_INTERVAL_MS = 30000;

const state = {
    credentials: null,
    username: null,
    role: null,
    page: 'dashboard',
    filter: 'ALL',
    tournaments: [],
    selected: null,
    participants: [],
    matches: [],
    activeDetailTab: null,
    notifications: [],
    notificationPollTimer: null,
    bracketPollTimer: null,
    bracketRequestSeq: 0
};

const ICONS = {
    trophy: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h10v2h3v3a4 4 0 0 1-4 4h-.3A5 5 0 0 1 13 14.9V18h3v3H8v-3h3v-3.1A5 5 0 0 1 8.3 12H8a4 4 0 0 1-4-4V5h3V3zm0 4H6v1a2 2 0 0 0 1 1.7V7zm10 0v2.7A2 2 0 0 0 18 8V7h-1z"/></svg>',
    upcoming: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16v14H4zM4 10h16M9 3v4M15 3v4"/></svg>',
    result: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 13l4 4L19 7"/></svg>',
    final: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 4h8v5a4 4 0 0 1-8 0zM8 6H5v2a3 3 0 0 0 3 3M16 6h3v2a3 3 0 0 1-3 3M12 13v4M9 20h6"/></svg>'
};

// ------------------------------------------------------------------ helpers

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
const base64 = (text) => btoa(String.fromCharCode(...new TextEncoder().encode(text)));
const plural = (count, singular, pluralForm = `${singular}s`) => `${count} ${count === 1 ? singular : pluralForm}`;
const isAdmin = () => state.role === 'ADMINISTRATOR';
const canCreateTournaments = () => state.role === 'ORGANIZER' || isAdmin();
const canManageTournament = () => isAdmin() || (state.role === 'ORGANIZER' && state.selected?.organizer === state.username);

function label(value) {
    const text = String(value ?? '').replaceAll('_', ' ').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
}

function date(value) {
    if (!value) return '-';
    const parsed = new Date(value.length === 10 ? `${value}T00:00:00` : value);
    return parsed.toLocaleDateString('en-GB', {day: 'numeric', month: 'short', year: 'numeric'});
}

function localToday() {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

function timeAgo(value) {
    if (!value) return '';
    const minutes = Math.round((Date.now() - new Date(value).getTime()) / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} min ago`;
    if (minutes < 60 * 24) return `${Math.round(minutes / 60)} h ago`;
    return date(value);
}

function toast(message, kind = 'info') {
    const node = $('#toast');
    node.textContent = message;
    node.className = `toast show ${kind}`;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => node.classList.remove('show'), 3500);
}

const loading = (text) => `<div class="loading">${esc(text)}</div>`;
const empty = (text) => `<div class="empty">${esc(text)}</div>`;
const errorBox = (text) => `<div class="error-box" role="alert">${esc(text)}</div>`;
const statusBadge = (status) => `<span class="badge status-${esc(String(status).toLowerCase())}">${esc(label(status))}</span>`;
const FORMAT_LABELS = {GROUPS: 'Group stage', DOUBLE_ELIMINATION: 'Double elimination', ELIMINATION: 'Elimination'};
const formatTag = (format) => `<span class="format-tag">${FORMAT_LABELS[format] || 'Elimination'}</span>`;
const isKnockout = (format) => format !== 'GROUPS';
const isDouble = (format) => format === 'DOUBLE_ELIMINATION';
const outcomeBadge = (outcome) => `<span class="badge outcome-${outcome.toLowerCase()}">${outcome === 'BYE' ? 'BYE' : label(outcome)}</span>`;

function statCard(title, value, hint = '') {
    const text = typeof value === 'string' && !/^[+-]?\d/.test(value); // names get a smaller type size than figures
    return `<div class="stat"><span class="eyebrow">${esc(title)}</span><span class="number ${text ? 'text' : ''}">${esc(value)}</span>${hint ? `<span class="stat-hint">${esc(hint)}</span>` : ''}</div>`;
}

/** Disables the triggering control while an async action runs and reports failures as a toast. */
async function runAction(control, action) {
    if (control) control.disabled = true;
    try {
        await action();
    } catch (error) {
        toast(error.message, 'error');
    } finally {
        if (control?.isConnected) control.disabled = false;
    }
}

function confirmAction({title, message, confirmLabel = 'Confirm', danger = false}) {
    closeModal();
    return new Promise((resolve) => {
        document.body.insertAdjacentHTML('beforeend', `
            <div class="modal-backdrop" data-modal>
                <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
                    <h3 id="modal-title">${esc(title)}</h3>
                    <div class="modal-body"><p>${esc(message)}</p></div>
                    <div class="modal-actions">
                        <button type="button" class="button secondary" data-modal-cancel>Cancel</button>
                        <button type="button" class="button ${danger ? 'danger' : 'primary'}" data-modal-confirm>${esc(confirmLabel)}</button>
                    </div>
                </div>
            </div>`);
        const finish = (answer) => { closeModal(); resolve(answer); };
        $('[data-modal-cancel]').onclick = () => finish(false);
        $('[data-modal-confirm]').onclick = () => finish(true);
        $('[data-modal]').onclick = (event) => { if (event.target.hasAttribute('data-modal')) finish(false); };
        $('[data-modal-confirm]').focus();
    });
}

function closeModal() {
    $('[data-modal]')?.remove();
}

// ------------------------------------------------------------------ API

function friendlyError(status, body) {
    if (status === 401) return body?.message === 'Invalid credentials' ? 'Wrong username or password.' : 'Please sign in again.';
    if (status >= 500) return 'Something went wrong on the server. Please try again.';
    const defaults = {400: 'Please check the entered values.', 403: 'You do not have permission to perform this action.', 404: 'The requested item was not found.', 409: 'This conflicts with existing data.'};
    return body?.message || defaults[status] || `Request failed (${status}).`;
}

async function request(path, options = {}) {
    const headers = {...(options.body ? {'Content-Type': 'application/json'} : {}), ...(options.headers || {})};
    if (state.credentials) headers.Authorization = `Basic ${state.credentials}`;

    let response;
    try {
        response = await fetch(`${API}${path}`, {...options, headers});
    } catch {
        throw new Error(`Unable to reach the server at ${API}. Is the backend running?`);
    }

    let body = null;
    if (response.status !== 204) {
        try { body = await response.json(); } catch { body = null; }
    }
    if (response.status === 401 && state.credentials && !path.startsWith('/auth/')) {
        signOut();
        throw new Error('Your session has ended. Please sign in again.');
    }
    if (!response.ok) throw new Error(friendlyError(response.status, body));
    return body;
}

// ------------------------------------------------------------------ authentication

function showAuth(mode = 'login') {
    $('#auth-view').classList.remove('hidden');
    $('#app-view').classList.add('hidden');
    $$('[data-auth]').forEach(tab => tab.classList.toggle('active', tab.dataset.auth === mode));
    const register = mode === 'register';
    $('#auth-title').textContent = register ? 'Create your account' : 'Welcome back';
    $('#auth-subtitle').textContent = register ? 'Participants join tournaments; organizers create and run them.' : 'Sign in to manage your tournaments.';
    $('#email-field').classList.toggle('hidden', !register);
    $('#email').required = register;
    $('#organizer-field').classList.toggle('hidden', !register);
    $('#password').autocomplete = register ? 'new-password' : 'current-password';
    $('#auth-submit').textContent = register ? 'Create account' : 'Sign in';
    $('#auth-error').textContent = '';
    $('#auth-form').dataset.mode = mode;
}

async function authSubmit(event) {
    event.preventDefault();
    const register = $('#auth-form').dataset.mode === 'register';
    const payload = {username: $('#username').value.trim(), password: $('#password').value};
    if (register) {
        payload.email = $('#email').value.trim();
        payload.organizer = $('#organizer').checked;
    }
    $('#auth-submit').disabled = true;
    $('#auth-error').textContent = '';
    try {
        const response = await request(`/auth/${register ? 'register' : 'login'}`, {method: 'POST', body: JSON.stringify(payload)});
        state.credentials = base64(`${payload.username}:${payload.password}`);
        state.username = response.username;
        state.role = response.role;
        $('#password').value = '';
        showApp();
        toast(register ? 'Account created - welcome!' : `Signed in as ${response.username}`);
    } catch (error) {
        $('#auth-error').textContent = error.message;
    } finally {
        $('#auth-submit').disabled = false;
    }
}

function showApp() {
    $('#auth-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
    $('#user-name').textContent = state.username;
    $('#user-role').textContent = label(state.role);
    $('#user-initial').textContent = state.username?.[0]?.toUpperCase() || 'U';
    $$('[data-role-only]').forEach(item => item.classList.toggle('hidden', item.dataset.roleOnly !== state.role));
    navigate('dashboard');
    loadNotifications();
    startNotificationPolling();
}

function signOut() {
    stopNotificationPolling();
    stopBracketPolling();
    closeDrawer();
    closeModal();
    Object.assign(state, {credentials: null, username: null, role: null, selected: null, participants: [], matches: [], tournaments: [], notifications: []});
    updateBadge(0);
    showAuth();
}

// ------------------------------------------------------------------ navigation

function navigate(page) {
    stopBracketPolling();
    closeDrawer();
    state.page = page;
    $$('[data-page]').forEach(item => item.classList.toggle('active', item.dataset.page === page));
    const pages = {dashboard: loadDashboard, 'my-matches': renderMyMatches, notifications: renderNotifications, admin: renderAdmin};
    (pages[page] || loadDashboard)();
}

function setHeader(title, eyebrow) {
    $('#page-title').textContent = title;
    $('#page-eyebrow').textContent = eyebrow;
    $('#create-toggle').classList.toggle('hidden', !(state.page === 'dashboard' && canCreateTournaments()));
}

// ------------------------------------------------------------------ dashboard

async function loadDashboard() {
    state.page = 'dashboard';
    setHeader('Tournaments', `Signed in as ${label(state.role)}`);
    $('#app-content').innerHTML = loading('Loading tournaments...');
    try {
        state.tournaments = await request('/tournaments');
        renderDashboard();
    } catch (error) {
        $('#app-content').innerHTML = errorBox(error.message);
    }
}

function renderDashboard() {
    const list = state.tournaments;
    const count = (status) => list.filter(t => t.status === status).length;
    const filters = [['ALL', 'All'], ['REGISTRATION', 'Open for registration'], ['IN_PROGRESS', 'In progress'], ['COMPLETED', 'Completed']];
    const visible = state.filter === 'ALL' ? list : list.filter(t => t.status === state.filter);
    const nothing = canCreateTournaments() ? 'No tournaments yet. Create the first one with "New tournament".' : 'No tournaments have been published yet.';

    $('#app-content').innerHTML = `
        <div class="stats">
            ${statCard('Tournaments', list.length)}
            ${statCard('Open for registration', count('REGISTRATION'))}
            ${statCard('In progress', count('IN_PROGRESS'))}
            ${statCard('Completed', count('COMPLETED'))}
        </div>
        <div class="section-head">
            <h2>${state.role === 'PARTICIPANT' ? 'Find a tournament' : 'All tournaments'}</h2>
            <div class="chips">
                ${filters.map(([value, text]) => `<button type="button" class="chip ${state.filter === value ? 'active' : ''}" data-filter="${value}">${text}</button>`).join('')}
            </div>
        </div>
        <div class="tournament-list">
            ${visible.length ? visible.map(tournamentCard).join('') : empty(list.length ? 'No tournaments match this filter.' : nothing)}
        </div>`;
    $$('[data-filter]').forEach(button => button.onclick = () => { state.filter = button.dataset.filter; renderDashboard(); });
    $$('[data-detail]').forEach(button => button.onclick = () => loadDetail(button.dataset.detail));
}

function tournamentCard(t) {
    return `
        <article class="tournament-card">
            <div class="card-top">${statusBadge(t.status)}${formatTag(t.format)}</div>
            <h3>${esc(t.name)}</h3>
            <p>${esc(t.description || 'No description provided.')}</p>
            <div class="card-meta">
                <span>${date(t.startDate)} &ndash; ${date(t.endDate)}</span>
                <span>${plural(t.participantCount, 'entrant')}</span>
            </div>
            <div class="card-foot">
                <span class="muted">by ${esc(t.organizer)}</span>
                <button type="button" class="button secondary small" data-detail="${t.id}">Open</button>
            </div>
        </article>`;
}

// ------------------------------------------------------------------ create & edit

function validateDateRange(form) {
    const start = form.elements.startDate;
    const end = form.elements.endDate;
    if (!start || !end) return;
    end.setCustomValidity(start.value && end.value && end.value < start.value ? 'End date cannot be before start date.' : '');
}

function renderCreate() {
    stopBracketPolling();
    state.page = 'create';
    setHeader('New tournament', 'Organizer');
    const today = localToday();
    $('#app-content').innerHTML = `
        <button class="back" id="cancel-create" type="button">&larr; Back to tournaments</button>
        <div class="form-panel">
            <h2>Create tournament</h2>
            <p class="muted">Registration opens as soon as the tournament is created.</p>
            <form id="create-form" class="form-grid">
                <label class="full">Name<input name="name" required maxlength="150" placeholder="e.g. Stara Zagora Chess Open"></label>
                <label class="full">Format<select name="format" id="format-select">
                    <option value="ELIMINATION">Elimination - seeded single-elimination bracket</option>
                    <option value="DOUBLE_ELIMINATION">Double elimination - a loss drops you into a losers bracket</option>
                    <option value="GROUPS">Group stage - round robin inside each group</option>
                </select></label>
                <div class="full option-row" id="grand-final-row" hidden>
                    <label class="checkbox"><input type="checkbox" name="grandFinalReset" checked>
                        <span>Play a deciding rematch when the losers-bracket finalist wins the grand final</span></label>
                    <p class="muted small">The winners-bracket finalist reaches the grand final unbeaten. With the rematch
                        both of them need two losses to be eliminated; without it, one defeat there ends the tournament.</p>
                </div>
                <label>Start date<input name="startDate" type="date" required min="${today}" value="${today}"></label>
                <label>End date<input name="endDate" type="date" required min="${today}" value="${today}"></label>
                <label class="full">Description<textarea name="description" maxlength="2000" placeholder="Rules, venue, prizes..."></textarea></label>
                <div class="form-actions full">
                    <button type="button" class="button secondary" id="cancel-form">Cancel</button>
                    <button class="button primary">Create tournament</button>
                </div>
            </form>
        </div>`;
    $('#cancel-create').onclick = () => navigate('dashboard');
    $('#cancel-form').onclick = () => navigate('dashboard');
    const formatSelect = $('#format-select');
    // The rematch only exists in double elimination, so only offer it for that format.
    formatSelect.onchange = () => { $('#grand-final-row').hidden = !isDouble(formatSelect.value); };
    $('#create-form').onsubmit = (event) => {
        event.preventDefault();
        const form = new FormData(event.target);
        const payload = Object.fromEntries(form.entries());
        payload.grandFinalReset = isDouble(payload.format) && form.get('grandFinalReset') === 'on';
        runAction(event.submitter, async () => {
            const created = await request('/tournaments', {method: 'POST', body: JSON.stringify(payload)});
            toast('Tournament created - registration is open');
            await loadDetail(created.id);
        });
    };
}

function renderEditForm() {
    stopBracketPolling();
    const t = state.selected;
    $('#app-content').innerHTML = `
        <button class="back" id="cancel-edit" type="button">&larr; Back to tournament</button>
        <div class="form-panel">
            <h2>Edit tournament</h2>
            <p class="muted">Details can be changed while registration is open.</p>
            <form id="edit-form" class="form-grid">
                <label class="full">Name<input name="name" required maxlength="150" value="${esc(t.name)}"></label>
                <label>Start date<input name="startDate" type="date" required value="${t.startDate}"></label>
                <label>End date<input name="endDate" type="date" required value="${t.endDate}"></label>
                <label class="full">Description<textarea name="description" maxlength="2000">${esc(t.description || '')}</textarea></label>
                <div class="form-actions full">
                    <button type="button" class="button secondary" id="cancel-edit-form">Cancel</button>
                    <button class="button primary">Save changes</button>
                </div>
            </form>
        </div>`;
    $('#cancel-edit').onclick = () => renderDetail('overview');
    $('#cancel-edit-form').onclick = () => renderDetail('overview');
    $('#edit-form').onsubmit = (event) => {
        event.preventDefault();
        const payload = Object.fromEntries(new FormData(event.target).entries());
        runAction(event.submitter, async () => {
            await request(`/tournaments/${t.id}`, {method: 'PUT', body: JSON.stringify(payload)});
            toast('Tournament updated');
            await loadDetail(t.id);
        });
    };
}

// ------------------------------------------------------------------ tournament detail

async function loadDetail(id, tab = 'overview') {
    stopBracketPolling();
    closeDrawer();
    state.page = 'detail';
    setHeader('Tournament', 'Tournament details');
    $$('[data-page]').forEach(item => item.classList.toggle('active', item.dataset.page === 'dashboard'));
    $('#app-content').innerHTML = loading('Loading tournament...');
    try {
        const [tournament, participants] = await Promise.all([
            request(`/tournaments/${id}`),
            request(`/tournaments/${id}/participants`)
        ]);
        state.selected = tournament;
        state.participants = participants;
        renderDetail(tab);
    } catch (error) {
        $('#app-content').innerHTML = errorBox(error.message);
    }
}

function renderDetail(tab) {
    const t = state.selected;
    const actions = canManageTournament() && t.status === 'REGISTRATION' ? `
        <button type="button" class="button primary" id="generate-matches">${t.format === 'GROUPS' ? 'Generate group matches' : 'Generate bracket'}</button>
        <button type="button" class="button secondary" id="edit-tournament">Edit</button>
        <button type="button" class="button danger" id="delete-tournament">Delete</button>` : '';
    const tabs = [['overview', 'Overview'], ['bracket', t.format === 'GROUPS' ? 'Matches' : 'Bracket'], ['standings', 'Standings'], ['results', 'Results & stats']];

    setHeader(t.name, 'Tournament details');
    $('#app-content').innerHTML = `
        <button class="back" id="back-dashboard" type="button">&larr; All tournaments</button>
        <section class="detail-head">
            <div>
                <div class="badge-row">${statusBadge(t.status)}${formatTag(t.format)}</div>
                <p class="detail-description">${esc(t.description || 'No description provided.')}</p>
                <div class="detail-meta">
                    <span><strong>Dates</strong>${date(t.startDate)} &ndash; ${date(t.endDate)}</span>
                    <span><strong>Organizer</strong>${esc(t.organizer)}</span>
                    <span><strong>Entrants</strong>${t.participantCount}</span>
                </div>
            </div>
            <div class="detail-actions">${actions}</div>
        </section>
        ${lifecycle(t.status)}
        <nav class="tabs" role="tablist">
            ${tabs.map(([key, text]) => `<button type="button" class="detail-tab" role="tab" data-tab="${key}">${text}</button>`).join('')}
        </nav>
        <div id="detail-body"></div>`;

    $('#back-dashboard').onclick = () => navigate('dashboard');
    if ($('#generate-matches')) $('#generate-matches').onclick = (event) => generateMatches(event.currentTarget);
    if ($('#edit-tournament')) $('#edit-tournament').onclick = renderEditForm;
    if ($('#delete-tournament')) $('#delete-tournament').onclick = (event) => deleteTournament(event.currentTarget);
    $$('[data-tab]').forEach(button => button.onclick = () => selectTab(button.dataset.tab));
    selectTab(tab);
}

function lifecycle(status) {
    const steps = [['REGISTRATION', 'Registration'], ['IN_PROGRESS', 'In progress'], ['COMPLETED', 'Completed']];
    const current = steps.findIndex(([key]) => key === status);
    return `<ol class="lifecycle" aria-label="Tournament stage">${steps.map(([, text], index) =>
        `<li class="${index < current || status === 'COMPLETED' ? 'done' : index === current ? 'current' : ''}">${text}</li>`).join('')}</ol>`;
}

function selectTab(tab) {
    state.activeDetailTab = tab;
    $$('[data-tab]').forEach(button => {
        const active = button.dataset.tab === tab;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', String(active));
    });
    stopBracketPolling();
    const renderers = {overview: renderOverview, bracket: () => loadAndRenderMatches({startPolling: true}), standings: renderStandings, results: renderResults};
    (renderers[tab] || renderOverview)();
}

async function generateMatches(button) {
    const t = state.selected;
    const groups = t.format === 'GROUPS';
    const seedingNote = `Seeds follow registration order and the top seeds receive any BYEs. Registration closes for all ${plural(t.participantCount, 'entrant')}.`;
    const confirmed = await confirmAction({
        title: groups ? 'Generate group matches?' : 'Generate the bracket?',
        message: groups
            ? 'Every participant will play everyone else in their group. Registration closes and groups can no longer change.'
            : isDouble(t.format) ? `${seedingNote} Losing a match drops a participant into the losers bracket instead of eliminating them.` : seedingNote,
        confirmLabel: groups ? 'Generate matches' : 'Generate bracket'
    });
    if (!confirmed) return;
    await runAction(button, async () => {
        await request(`/tournaments/${t.id}/generate-bracket`, {method: 'POST'});
        toast(groups ? 'Group matches generated' : 'Bracket generated');
        await loadDetail(t.id, 'bracket');
    });
}

async function deleteTournament(button) {
    const t = state.selected;
    const confirmed = await confirmAction({title: 'Delete tournament?', message: `"${t.name}" and its registrations will be removed permanently.`, confirmLabel: 'Delete', danger: true});
    if (!confirmed) return;
    await runAction(button, async () => {
        await request(`/tournaments/${t.id}`, {method: 'DELETE'});
        toast('Tournament deleted');
        navigate('dashboard');
    });
}

// ------------------------------------------------------------------ overview tab

function renderOverview() {
    const t = state.selected;
    const side = [
        canManageTournament() && t.status === 'REGISTRATION' ? addParticipantPanel() : '',
        state.role === 'PARTICIPANT' ? joinPanel() : '',
        formatNote()
    ].join('');
    $('#detail-body').innerHTML = `
        <div class="overview-grid">
            <section class="panel">
                <div class="section-head compact"><h3>Participants</h3><span class="muted">${plural(state.participants.length, 'entrant')}</span></div>
                ${participantTable()}
            </section>
            <aside class="side-stack">${side}</aside>
        </div>
        ${t.format === 'GROUPS' ? '<div id="groups-section"></div>' : ''}`;
    bindOverview();
    if (t.format === 'GROUPS') renderGroupAssignment();
}

function participantTable() {
    if (!state.participants.length) return empty('Nobody has registered yet.');
    const seeded = isKnockout(state.selected.format);
    return `<div class="table-wrap"><table class="data-table">
        <thead><tr><th class="num">${seeded ? 'Seed' : '#'}</th><th>Name</th><th>Account</th></tr></thead>
        <tbody>${state.participants.map((p, index) => {
            const mine = Boolean(p.linkedUsername) && p.linkedUsername === state.username;
            return `<tr class="${mine ? 'is-me' : ''}">
                <td class="num">${index + 1}</td>
                <td><strong>${esc(p.name)}</strong>${mine ? '<span class="you">You</span>' : ''}</td>
                <td>${p.linkedUsername ? `@${esc(p.linkedUsername)}` : '<span class="muted">No account</span>'}</td>
            </tr>`;
        }).join('')}</tbody>
    </table></div>`;
}

function addParticipantPanel() {
    return `<section class="panel">
        <h3>Add participant</h3>
        <form id="add-participant-form" class="stack-form">
            <label>Name or team<input name="name" required maxlength="100" placeholder="e.g. Georgi Petrov"></label>
            <label>Linked account <span class="muted">(optional)</span><input name="username" maxlength="50" placeholder="username"></label>
            <p class="hint">A linked account can follow its matches and receives notifications.</p>
            <button class="button primary">Add participant</button>
        </form>
    </section>`;
}

function joinPanel() {
    const t = state.selected;
    const entry = state.participants.find(p => p.linkedUsername && p.linkedUsername === state.username);
    if (t.status !== 'REGISTRATION') {
        return `<section class="panel">
            <h3>Your participation</h3>
            <p class="muted">${entry ? `You are competing as <strong>${esc(entry.name)}</strong>.` : 'Registration for this tournament is closed.'}</p>
            ${entry ? '<button type="button" class="button secondary" data-page-link="my-matches">View my matches</button>' : ''}
        </section>`;
    }
    if (entry) {
        return `<section class="panel">
            <h3>You're registered</h3>
            <p class="muted">You entered as <strong>${esc(entry.name)}</strong>. You can withdraw until the organizer generates the matches.</p>
            <button type="button" class="button danger" id="leave-tournament">Withdraw</button>
        </section>`;
    }
    return `<section class="panel">
        <h3>Join this tournament</h3>
        <form id="join-form" class="stack-form">
            <label>Display name <span class="muted">(optional)</span><input name="name" maxlength="100" placeholder="${esc(state.username)}"></label>
            <p class="hint">Leave it empty to enter under your username, or type a team name.</p>
            <button class="button primary">Join tournament</button>
        </form>
    </section>`;
}

function formatNote() {
    if (state.selected.format === 'GROUPS') {
        return `<div class="info-panel"><h4>How the group stage works</h4><p>Everyone plays everyone else in their group once, scheduled round by round so nobody plays twice in a round. Standings rank wins first, then score difference. Draws are allowed.</p></div>`;
    }
    const seeding = 'Registration order is the seeding order. The bracket is padded to the next power of two and the top seeds receive the BYEs, so every match from round two on has two real opponents. Winners advance automatically.';
    if (isDouble(state.selected.format)) {
        const rematch = state.selected.grandFinalReset
            ? 'If the losers-bracket finalist wins the grand final both have one loss, so a deciding rematch is played.'
            : 'The grand final is a single match: whoever wins it is the champion.';
        return `<div class="info-panel"><h4>How double elimination works</h4><p>${seeding} A beaten participant is not out - they drop into the losers bracket and can still reach the grand final. Only a second loss eliminates. ${rematch}</p></div>`;
    }
    return `<div class="info-panel"><h4>How seeding works</h4><p>${seeding}</p></div>`;
}

function bindOverview() {
    const t = state.selected;
    $('#add-participant-form')?.addEventListener('submit', (event) => {
        event.preventDefault();
        const form = new FormData(event.target);
        const payload = {name: form.get('name').trim()};
        if (form.get('username').trim()) payload.username = form.get('username').trim();
        runAction(event.submitter, async () => {
            await request(`/tournaments/${t.id}/participants`, {method: 'POST', body: JSON.stringify(payload)});
            toast(`${payload.name} added`);
            await loadDetail(t.id);
        });
    });
    $('#join-form')?.addEventListener('submit', (event) => {
        event.preventDefault();
        const name = new FormData(event.target).get('name').trim();
        runAction(event.submitter, async () => {
            await request(`/tournaments/${t.id}/join`, {method: 'POST', body: JSON.stringify(name ? {name} : {})});
            toast('You joined the tournament');
            await loadDetail(t.id);
        });
    });
    $('#leave-tournament')?.addEventListener('click', async (event) => {
        const button = event.currentTarget;
        const confirmed = await confirmAction({title: 'Withdraw from tournament?', message: 'Your registration will be removed. You can join again while registration is open.', confirmLabel: 'Withdraw', danger: true});
        if (!confirmed) return;
        runAction(button, async () => {
            await request(`/tournaments/${t.id}/join`, {method: 'DELETE'});
            toast('You withdrew from the tournament');
            await loadDetail(t.id);
        });
    });
    $$('[data-page-link]').forEach(button => button.onclick = () => navigate(button.dataset.pageLink));
}

async function renderGroupAssignment() {
    const section = $('#groups-section');
    if (!section) return;
    const t = state.selected;
    section.innerHTML = loading('Loading groups...');
    try {
        const groups = await request(`/tournaments/${t.id}/groups`);
        if (!$('#groups-section')) return;
        const assigned = new Set(groups.flatMap(g => g.participants.map(p => p.id)));
        const unassigned = state.participants.filter(p => !assigned.has(p.id));
        const canEdit = canManageTournament() && t.status === 'REGISTRATION';

        section.innerHTML = `
            <div class="section-head">
                <div><h2>Groups</h2><p class="muted">${canEdit ? 'Every participant must be in a group before matches are generated.' : 'Group membership for this tournament.'}</p></div>
                ${canEdit ? '<form id="create-group-form" class="inline-form"><input name="name" required maxlength="50" placeholder="Group name, e.g. Group A" aria-label="Group name"><button class="button secondary">Create group</button></form>' : ''}
            </div>
            <div class="group-grid">
                ${groups.length ? groups.map(g => `
                    <article class="group-card">
                        <header><h3>${esc(g.name)}</h3><span class="muted">${plural(g.participants.length, 'player')}</span></header>
                        ${g.participants.map(p => `<div class="group-member"><span>${esc(p.name)}</span>${canEdit ? `<button type="button" class="text-link" data-remove-group="${g.id}" data-remove-participant="${p.id}">Remove</button>` : ''}</div>`).join('') || '<p class="muted small">No players yet.</p>'}
                    </article>`).join('') : empty('No groups have been created yet.')}
            </div>
            ${canEdit ? `
            <article class="group-card unassigned">
                <header><h3>Unassigned</h3><span class="muted">${unassigned.length}</span></header>
                ${unassigned.map(p => `<div class="group-member"><span>${esc(p.name)}</span>${groups.length ? `<select data-assign-participant="${p.id}" aria-label="Assign ${esc(p.name)} to a group"><option value="">Assign to...</option>${groups.map(g => `<option value="${g.id}">${esc(g.name)}</option>`).join('')}</select>` : ''}</div>`).join('') || '<p class="muted small">Everyone is in a group.</p>'}
            </article>` : ''}`;

        $('#create-group-form')?.addEventListener('submit', (event) => {
            event.preventDefault();
            const name = new FormData(event.target).get('name').trim();
            runAction(event.submitter, async () => {
                await request(`/tournaments/${t.id}/groups`, {method: 'POST', body: JSON.stringify({name})});
                toast(`${name} created`);
                await renderGroupAssignment();
            });
        });
        $$('[data-assign-participant]').forEach(select => select.onchange = () => {
            if (!select.value) return;
            runAction(select, async () => {
                await request(`/tournaments/${t.id}/groups/${select.value}/participants/${select.dataset.assignParticipant}`, {method: 'PUT'});
                await renderGroupAssignment();
            });
        });
        $$('[data-remove-group]').forEach(button => button.onclick = () => runAction(button, async () => {
            await request(`/tournaments/${t.id}/groups/${button.dataset.removeGroup}/participants/${button.dataset.removeParticipant}`, {method: 'DELETE'});
            await renderGroupAssignment();
        }));
    } catch (error) {
        section.innerHTML = errorBox(error.message);
    }
}

// ------------------------------------------------------------------ bracket / matches tab (live)

function isMatchesTabActive(tournamentId) {
    return state.selected?.id === tournamentId && state.activeDetailTab === 'bracket' && Boolean($('#detail-body'));
}

function startBracketPolling(tournamentId) {
    stopBracketPolling();
    state.bracketPollTimer = setInterval(() => {
        if (document.hidden) return;
        if (!state.credentials || !isMatchesTabActive(tournamentId)) {
            stopBracketPolling();
            return;
        }
        loadAndRenderMatches({silent: true});
    }, BRACKET_POLL_INTERVAL_MS);
}

function stopBracketPolling() {
    if (state.bracketPollTimer) clearInterval(state.bracketPollTimer);
    state.bracketPollTimer = null;
}

async function loadAndRenderMatches({silent = false, startPolling = false} = {}) {
    const tournament = state.selected;
    if (!tournament) return;
    const seq = ++state.bracketRequestSeq;
    const body = $('#detail-body');
    if (!silent && body) body.innerHTML = loading('Loading matches...');
    try {
        const matches = await request(`/tournaments/${tournament.id}/bracket`);
        if (seq !== state.bracketRequestSeq || !isMatchesTabActive(tournament.id)) return;
        // A background refresh never replaces the screen while the organizer is typing a result.
        if (silent && ($('.match-drawer') || $('[data-modal]'))) return;
        state.matches = matches;
        if (tournament.format === 'GROUPS') renderGroupMatches(matches);
        else renderBracket(matches);
        if (startPolling && tournament.status === 'IN_PROGRESS') startBracketPolling(tournament.id);
    } catch (error) {
        if (!silent && body) body.innerHTML = errorBox(error.message);
    }
}

function roundName(round, totalRounds) {
    const remaining = 2 ** (totalRounds - round + 1);
    if (round === totalRounds) return 'Final';
    if (remaining === 4) return 'Semi-finals';
    if (remaining === 8) return 'Quarter-finals';
    return `Round of ${remaining}`;
}

function stageName(match) {
    if (match.groupName) return `${match.groupName} · Round ${match.round}`;
    if (!match.bracket) return roundName(match.round, Math.max(...state.matches.map(m => m.round)));

    return bracketRoundName(match);
}

function noMatchesMessage() {
    const t = state.selected;
    const what = t.format === 'GROUPS' ? 'group schedule' : 'bracket';
    if (t.status !== 'REGISTRATION') return 'No matches found.';
    return canManageTournament()
        ? `Matches appear here once you generate the ${what}. Registration closes at that moment.`
        : `The ${what} will be published when the organizer closes registration.`;
}

function liveToolbar() {
    const live = state.selected.status === 'IN_PROGRESS';
    return `<div class="bracket-toolbar">
        <div class="live-state ${live ? 'on' : ''}"><span class="dot"></span>${live ? 'Live - refreshes every 6 seconds' : esc(label(state.selected.status))}<small>Updated ${new Date().toLocaleTimeString('en-GB')}</small></div>
        <button class="button secondary small" id="refresh-bracket" type="button">Refresh now</button>
    </div>`;
}

function renderBracket(matches) {
    const body = $('#detail-body');
    if (!matches.length) { body.innerHTML = empty(noMatchesMessage()); return; }
    if (isDouble(state.selected.format)) { renderDoubleBracket(matches); return; }
    const rounds = [...new Set(matches.map(m => m.round))].sort((a, b) => a - b);
    const totalRounds = rounds.length;
    body.innerHTML = `
        ${liveToolbar()}
        <div class="bracket-scroll">
            <div class="bracket">
                ${rounds.map(round => {
                    const inRound = matches.filter(m => m.round === round);
                    return `<section class="round">
                        <header class="round-header"><span>${roundName(round, totalRounds)}</span><small>${plural(inRound.length, 'match', 'matches')}</small></header>
                        <div class="round-matches">${inRound.map(matchCard).join('')}</div>
                    </section>`;
                }).join('')}
                ${championColumn(matches, totalRounds)}
            </div>
        </div>`;
    bindMatchCards(matches);
}

/** Winners bracket, losers bracket and grand final as three separately scrollable blocks. */
function renderDoubleBracket(matches) {
    const winners = matches.filter(m => m.bracket === 'WINNERS');
    const losers = matches.filter(m => m.bracket === 'LOSERS');
    const finals = matches.filter(m => m.bracket === 'GRAND_FINAL').sort((a, b) => a.round - b.round);
    const winnersRounds = winners.length ? Math.max(...winners.map(m => m.round)) : 0;
    const losersRounds = losers.length ? Math.max(...losers.map(m => m.round)) - winnersRounds : 0;

    $('#detail-body').innerHTML = liveToolbar()
        + bracketBlock('Winners bracket', 'Everybody starts here; a defeat drops you into the losers bracket.',
            winners, match => winnersRoundName(match.round, winnersRounds))
        + (losers.length ? bracketBlock('Losers bracket', 'Second chance - but a second defeat eliminates.',
            losers, match => losersRoundName(match.round - winnersRounds, losersRounds)) : '')
        + grandFinalBlock(finals);
    bindMatchCards(matches);
}

function bracketBlock(title, note, list, nameOf) {
    const rounds = [...new Set(list.map(m => m.round))].sort((a, b) => a - b);
    return `<section class="bracket-block">
        <div class="section-head compact"><h3>${esc(title)}</h3><span class="muted">${esc(note)}</span></div>
        <div class="bracket-scroll"><div class="bracket">
            ${rounds.map(round => {
                const inRound = list.filter(m => m.round === round);
                return `<section class="round">
                    <header class="round-header"><span>${esc(nameOf(inRound[0]))}</span><small>${plural(inRound.length, 'match', 'matches')}</small></header>
                    <div class="round-matches">${inRound.map(matchCard).join('')}</div>
                </section>`;
            }).join('')}
        </div></div>
    </section>`;
}

function grandFinalBlock(finals) {
    const decided = [...finals].reverse().find(m => m.winnerId != null);
    const champion = decided ? (decided.winnerId === decided.participant1Id ? decided.participant1 : decided.participant2) : null;
    return `<section class="bracket-block">
        <div class="section-head compact"><h3>Grand final</h3><span class="muted">The unbeaten finalist against whoever survived the losers bracket.</span></div>
        <div class="bracket-scroll"><div class="bracket">
            ${finals.map((match, index) => `<section class="round">
                <header class="round-header"><span>${index === 0 ? 'Grand final' : 'Deciding rematch'}</span><small>${index === 0 ? '1 match' : 'only if needed'}</small></header>
                <div class="round-matches">${matchCard(match)}</div>
            </section>`).join('')}
            ${champion ? `<section class="champion-column"><div class="champion-card">${ICONS.trophy}<p>Champion</p><strong>${esc(champion)}</strong></div></section>` : ''}
        </div></div>
    </section>`;
}

/** Winners rounds for this tournament: the bracket is padded to the next power of two. */
function knockoutRounds() {
    const entrants = Math.max(2, state.selected.participantCount || 2);
    let rounds = 1;
    while (2 ** rounds < entrants) rounds++;
    return rounds;
}

/** Human name for a match in a double-elimination bracket. */
function bracketRoundName(match) {
    const winners = knockoutRounds();
    if (match.bracket === 'WINNERS') return winnersRoundName(match.round, winners);
    if (match.bracket === 'LOSERS') return losersRoundName(match.round - winners, Math.max(2 * winners - 2, 1));
    return match.round > 3 * winners - 1 ? 'Deciding rematch' : 'Grand final';
}

function winnersRoundName(round, total) {
    if (round === total) return 'Winners final';
    if (total - round === 1) return 'Winners semi-finals';
    return `Winners round ${round}`;
}

function losersRoundName(round, total) {
    return round === total ? 'Losers final' : `Losers round ${round}`;
}

function renderGroupMatches(matches) {
    const body = $('#detail-body');
    if (!matches.length) { body.innerHTML = empty(noMatchesMessage()); return; }
    const groups = [...new Set(matches.map(m => m.groupName))];
    body.innerHTML = liveToolbar() + groups.map(group => {
        const inGroup = matches.filter(m => m.groupName === group);
        const rounds = [...new Set(inGroup.map(m => m.round))].sort((a, b) => a - b);
        const played = inGroup.filter(m => m.status === 'COMPLETED').length;
        return `<section class="group-block">
            <div class="section-head compact"><h3>${esc(group)}</h3><span class="muted">${played} of ${plural(inGroup.length, 'match', 'matches')} played</span></div>
            ${rounds.map(round => `<div class="round-block"><h4>Round ${round}</h4><div class="match-grid">${inGroup.filter(m => m.round === round).map(matchCard).join('')}</div></div>`).join('')}
        </section>`;
    }).join('');
    bindMatchCards(matches);
}

function isBye(match) {
    return match.status === 'COMPLETED' && match.score1 == null && !isSkipped(match);
}

/** A grand-final rematch the tournament never needed: complete, but nobody was ever placed in it. */
function isSkipped(match) {
    return match.status === 'COMPLETED' && match.winnerId == null && match.participant1Id == null && match.participant2Id == null;
}

function matchCard(match) {
    const bye = isBye(match);
    const skipped = isSkipped(match);
    const placeholder = skipped ? 'Not played' : null;
    const cardState = skipped ? 'Not needed' : bye ? 'BYE' : esc(label(match.status));
    return `
        <button type="button" class="bracket-match status-${match.status.toLowerCase()} ${bye ? 'is-bye' : ''} ${skipped ? 'is-skipped' : ''}" data-match-id="${match.id}">
            <div class="match-card-head"><span>Match ${match.matchNumber}</span><span class="match-state">${cardState}</span></div>
            ${playerRow(match.participant1, match.participant1Id, match.score1, match.winnerId, bye, placeholder)}
            ${playerRow(match.participant2, match.participant2Id, match.score2, match.winnerId, bye, placeholder)}
        </button>`;
}

function playerRow(name, id, score, winnerId, bye, placeholder) {
    const winner = id != null && id === winnerId;
    return `<div class="match-player ${winner ? 'is-winner' : ''} ${name ? '' : 'is-empty'}"><span>${esc(name || placeholder || (bye ? 'BYE' : 'TBD'))}</span><strong>${score ?? ''}</strong></div>`;
}

function championColumn(matches, totalRounds) {
    const final = matches.find(m => m.round === totalRounds);
    if (!final || final.status !== 'COMPLETED' || final.winnerId == null) return '';
    const champion = final.winnerId === final.participant1Id ? final.participant1 : final.participant2;
    return `<section class="champion-column"><div class="champion-card">${ICONS.trophy}<p>Champion</p><strong>${esc(champion)}</strong></div></section>`;
}

function bindMatchCards(matches) {
    $$('[data-match-id]').forEach(card => card.onclick = () => openMatchDrawer(matches.find(m => String(m.id) === card.dataset.matchId)));
    if ($('#refresh-bracket')) $('#refresh-bracket').onclick = () => loadAndRenderMatches({silent: false});
}

// ------------------------------------------------------------------ match drawer & result entry

function openMatchDrawer(match) {
    if (!match) return;
    closeDrawer();
    const t = state.selected;
    const bye = isBye(match);
    const canEnterResult = canManageTournament() && t.status === 'IN_PROGRESS' && match.status === 'READY';
    const title = bye ? `${match.participant1 || match.participant2} advances` : `${match.participant1 || 'TBD'} vs ${match.participant2 || 'TBD'}`;

    document.body.insertAdjacentHTML('beforeend', `
        <div class="drawer-backdrop" data-drawer></div>
        <aside class="match-drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title" data-drawer>
            <button class="drawer-close" type="button" aria-label="Close" data-close-drawer>&times;</button>
            <p class="eyebrow">${esc(stageName(match))} · Match ${match.matchNumber}</p>
            <h3 id="drawer-title">${esc(title)}</h3>
            <div class="drawer-status">${bye ? '<span class="badge outcome-bye">BYE</span>' : statusBadge(match.status)}</div>
            <div class="drawer-players">
                ${drawerPlayer(match.participant1, match.score1, match.winnerId != null && match.winnerId === match.participant1Id, bye)}
                ${drawerPlayer(match.participant2, match.score2, match.winnerId != null && match.winnerId === match.participant2Id, bye)}
            </div>
            ${canEnterResult ? resultForm(match) : `<p class="drawer-note">${esc(drawerNote(match, bye))}</p>`}
        </aside>`);

    $$('[data-close-drawer], .drawer-backdrop').forEach(node => node.onclick = closeDrawer);
    const form = $('#result-form');
    if (!form) return;
    form.elements.score1.focus();
    form.onsubmit = (event) => {
        event.preventDefault();
        const data = new FormData(form);
        runAction(event.submitter, async () => {
            await request(`/matches/${match.id}/result`, {method: 'POST', body: JSON.stringify({score1: Number(data.get('score1')), score2: Number(data.get('score2'))})});
            closeDrawer();
            toast('Result saved');
            await loadDetail(t.id, 'bracket');
        });
    };
}

function drawerPlayer(name, score, winner, bye) {
    return `<div class="drawer-player ${winner ? 'is-winner' : ''}"><span>${esc(name || (bye ? 'BYE' : 'To be decided'))}${winner ? ' <small class="badge outcome-win">Winner</small>' : ''}</span><strong>${score ?? '-'}</strong></div>`;
}

function resultForm(match) {
    const knockout = isKnockout(state.selected.format);
    return `<form id="result-form" class="result-form">
        <h4>Enter result</h4>
        <label>${esc(match.participant1)} score<input name="score1" type="number" min="0" step="1" required></label>
        <label>${esc(match.participant2)} score<input name="score2" type="number" min="0" step="1" required></label>
        <p class="hint">${knockout ? 'Knockout matches need a winner, so equal scores are rejected. The winner moves to the next round immediately.' : 'Equal scores are recorded as a draw.'}</p>
        <button class="button primary wide">Save result</button>
    </form>`;
}

function drawerNote(match, bye) {
    if (bye) return 'This participant had no first-round opponent and advanced automatically.';
    if (match.status === 'PENDING') return 'Waiting for an earlier match to decide who plays here.';
    if (match.status === 'READY') return state.selected.status === 'IN_PROGRESS' ? 'Both sides are known. The organizer enters the result after the match.' : 'This match is ready to be played.';
    return match.winnerId == null ? 'The match ended in a draw.' : 'The result has been recorded.';
}

function closeDrawer() {
    $$('[data-drawer]').forEach(node => node.remove());
}

// ------------------------------------------------------------------ standings tab

async function renderStandings() {
    const t = state.selected;
    const body = $('#detail-body');
    body.innerHTML = loading('Calculating standings...');
    try {
        const [rankings, groups] = await Promise.all([
            request(`/tournaments/${t.id}/rankings`),
            t.format === 'GROUPS' ? request(`/tournaments/${t.id}/groups`) : Promise.resolve([])
        ]);
        if (state.activeDetailTab !== 'standings') return;
        if (!rankings.length) { body.innerHTML = empty('Standings appear once participants have registered.'); return; }

        const note = t.format === 'GROUPS'
            ? 'Ordered by wins, then score difference, then registration order. Points: 3 for a win, 1 for a draw.'
            : 'Ordered by how far each participant got in the bracket, then wins, score difference and registration order. BYEs count as progress but not as played matches.';
        if (t.format === 'GROUPS' && groups.length) {
            const groupOf = new Map(groups.flatMap(g => g.participants.map(p => [p.id, g.name])));
            body.innerHTML = groups.map(g => `
                <section class="group-block">
                    <div class="section-head compact"><h3>${esc(g.name)}</h3></div>
                    ${standingsTable(rankings.filter(r => groupOf.get(r.participantId) === g.name))}
                </section>`).join('') + `<p class="footnote">${note}</p>`;
        } else {
            body.innerHTML = `${standingsTable(rankings)}<p class="footnote">${note}</p>`;
        }
    } catch (error) {
        body.innerHTML = errorBox(error.message);
    }
}

function standingsTable(rows) {
    return `<div class="table-wrap"><table class="data-table">
        <thead><tr><th class="num">#</th><th>Participant</th><th class="num">Played</th><th class="num">W</th><th class="num">D</th><th class="num">L</th><th class="num">+/-</th><th class="num">Pts</th></tr></thead>
        <tbody>${rows.map((r, index) => `<tr>
            <td class="num"><strong>${index + 1}</strong></td>
            <td>${esc(r.participant)}</td>
            <td class="num">${r.matchesPlayed}</td>
            <td class="num">${r.wins}</td>
            <td class="num">${r.draws}</td>
            <td class="num">${r.losses}</td>
            <td class="num">${r.scoreDifference > 0 ? '+' : ''}${r.scoreDifference}</td>
            <td class="num"><strong>${r.points}</strong></td>
        </tr>`).join('')}</tbody>
    </table></div>`;
}

// ------------------------------------------------------------------ results & statistics tab

async function renderResults() {
    const t = state.selected;
    const body = $('#detail-body');
    body.innerHTML = loading('Loading results...');
    try {
        const [stats, results, rankings] = await Promise.all([
            request(`/tournaments/${t.id}/statistics`),
            request(`/tournaments/${t.id}/results`),
            request(`/tournaments/${t.id}/rankings`)
        ]);
        if (state.activeDetailTab !== 'results') return;
        body.innerHTML = `
            ${stats.champion ? championBanner(stats, rankings) : progressBanner(stats)}
            <div class="stat-grid">
                ${statCard('Matches played', stats.playedMatches, `${stats.completedMatches} of ${stats.totalMatches} decided`)}
                ${statCard('BYE advances', stats.byes)}
                ${statCard('Draws', stats.draws)}
                ${statCard('Total score', stats.totalScore, `${stats.averageScorePerMatch} per played match`)}
                ${statCard('Top scorer', stats.topScorer || '-', stats.topScorer ? `${stats.topScorerPoints} points scored` : '')}
                ${statCard('Biggest win', stats.biggestWin ? `+${stats.biggestWinMargin}` : '-', stats.biggestWin || '')}
            </div>
            <div class="section-head"><h2>Match results</h2><span class="muted">${plural(results.length, 'played match', 'played matches')}</span></div>
            ${results.length ? resultsTable(results) : empty('No results have been recorded yet.')}`;
    } catch (error) {
        body.innerHTML = errorBox(error.message);
    }
}

function championBanner(stats, rankings) {
    const podium = rankings.slice(0, 3);
    const places = ['first', 'second', 'third'];
    const order = [1, 0, 2].filter(index => podium[index]);
    return `<section class="champion-banner">
        ${ICONS.trophy}
        <div>
            <p>Champion</p>
            <h2>${esc(stats.champion)}</h2>
            ${stats.runnerUp ? `<span>Runner-up: ${esc(stats.runnerUp)}</span>` : ''}
        </div>
        <div class="podium" aria-label="Podium">
            ${order.map(index => `<div class="podium-place ${places[index]}"><b>${index + 1}</b><span title="${esc(podium[index].participant)}">${esc(podium[index].participant)}</span></div>`).join('')}
        </div>
    </section>`;
}

function progressBanner(stats) {
    const waiting = stats.totalMatches === 0;
    return `<section class="progress-banner">
        <header><h3>${waiting ? 'Matches not generated yet' : 'Tournament progress'}</h3><span class="muted">${waiting ? 'Waiting for registration to close' : `${stats.progressPercent}% complete · ${plural(stats.remainingMatches, 'match', 'matches')} to go`}</span></header>
        <div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${stats.progressPercent}"><div class="progress-bar" style="width:${stats.progressPercent}%"></div></div>
    </section>`;
}

function resultsTable(results) {
    const totalRounds = Math.max(...results.map(m => m.round));
    const stage = (m) => m.groupName ? `${m.groupName} · R${m.round}` : m.bracket ? bracketRoundName(m) : `Round ${m.round}`;
    return `<div class="table-wrap"><table class="data-table">
        <thead><tr><th>Stage</th><th>Match</th><th>Participant 1</th><th class="num">Score</th><th>Participant 2</th><th>Winner</th></tr></thead>
        <tbody>${results.map(m => `<tr>
            <td>${esc(stage(m, totalRounds))}</td>
            <td>${m.matchNumber}</td>
            <td>${m.winnerId === m.participant1Id ? `<strong>${esc(m.participant1)}</strong>` : esc(m.participant1)}</td>
            <td class="num"><strong>${m.score1} : ${m.score2}</strong></td>
            <td>${m.winnerId === m.participant2Id ? `<strong>${esc(m.participant2)}</strong>` : esc(m.participant2)}</td>
            <td>${m.winnerId == null ? '<span class="badge outcome-draw">Draw</span>' : esc(m.winnerId === m.participant1Id ? m.participant1 : m.participant2)}</td>
        </tr>`).join('')}</tbody>
    </table></div>`;
}

// ------------------------------------------------------------------ my matches (participants)

async function renderMyMatches() {
    state.page = 'my-matches';
    setHeader('My matches', 'Participant');
    $('#app-content').innerHTML = loading('Loading your matches...');
    try {
        const matches = await request('/matches/mine');
        if (state.page !== 'my-matches') return;
        const upcoming = matches.filter(m => m.status === 'READY');
        const waiting = matches.filter(m => m.status === 'PENDING');
        const finished = matches.filter(m => m.status === 'COMPLETED');
        const count = (outcome) => finished.filter(m => m.outcome === outcome).length;
        const section = (title, list, note) => list.length
            ? `<section class="match-section"><div class="section-head compact"><h3>${title}</h3><span class="muted">${note}</span></div>${list.map(myMatchRow).join('')}</section>`
            : '';

        $('#app-content').innerHTML = matches.length ? `
            <div class="stats">
                ${statCard('Up next', upcoming.length, 'Ready to be played')}
                ${statCard('Wins', count('WIN'))}
                ${statCard('Losses', count('LOSS'))}
                ${statCard('Draws', count('DRAW'), plural(count('BYE'), 'BYE advance'))}
            </div>
            ${section('Up next', upcoming, 'Both opponents are known')}
            ${section('Waiting for an opponent', waiting, 'Decided by an earlier match')}
            ${section('Played', finished, 'Newest tournament first')}`
            : empty('You have no matches yet. Join a tournament from the Tournaments page - your matches appear here once the organizer generates them.');
        $$('[data-open-tournament]').forEach(button => button.onclick = () => loadDetail(button.dataset.openTournament));
    } catch (error) {
        $('#app-content').innerHTML = errorBox(error.message);
    }
}

function myMatchRow(m) {
    const stage = m.groupName ? `${m.groupName} · Round ${m.round}` : `Round ${m.round}`;
    const opponent = m.opponent ? esc(m.opponent) : (m.outcome === 'BYE' ? 'BYE' : 'TBD');
    return `<article class="my-match">
        <div>
            <p class="eyebrow">${esc(m.tournamentName)}</p>
            <h3>${esc(m.participant)} <span class="muted">vs</span> ${opponent}</h3>
            <p class="muted small">${esc(stage)} · Match ${m.matchNumber}</p>
        </div>
        <div class="my-match-side">
            ${m.outcome ? outcomeBadge(m.outcome) : statusBadge(m.status)}
            ${m.score != null ? `<strong class="score">${m.score} : ${m.opponentScore}</strong>` : ''}
            <button type="button" class="button secondary small" data-open-tournament="${m.tournamentId}">Open</button>
        </div>
    </article>`;
}

// ------------------------------------------------------------------ notifications

function startNotificationPolling() {
    stopNotificationPolling();
    state.notificationPollTimer = setInterval(() => { if (!document.hidden) loadNotifications(); }, NOTIFICATION_POLL_INTERVAL_MS);
}

function stopNotificationPolling() {
    if (state.notificationPollTimer) clearInterval(state.notificationPollTimer);
    state.notificationPollTimer = null;
}

function updateBadge(count) {
    $('#notification-count').textContent = count ? String(count) : '';
}

async function loadNotifications() {
    if (!state.credentials) return;
    try {
        const unread = await request('/notifications?unread=true');
        updateBadge(unread.length);
    } catch {
        // Keep the last known badge while the API is unreachable; the next poll retries.
    }
}

async function renderNotifications() {
    state.page = 'notifications';
    setHeader('Notifications', 'Inbox');
    $('#app-content').innerHTML = loading('Loading notifications...');
    try {
        const data = await request('/notifications');
        if (state.page !== 'notifications') return;
        state.notifications = data;
        const unread = data.filter(n => !n.read).length;
        updateBadge(unread);
        $('#app-content').innerHTML = `
            <div class="section-head">
                <h2>${unread ? `${unread} unread` : 'You are all caught up'}</h2>
                ${unread ? '<button type="button" class="button secondary" id="read-all">Mark all as read</button>' : ''}
            </div>
            ${data.length ? `<div class="notification-list">${data.map(notificationItem).join('')}</div>` : empty('No notifications yet. You will hear about upcoming matches and final results here.')}`;

        $$('[data-read]').forEach(button => button.onclick = () => runAction(button, async () => {
            await request(`/notifications/${button.dataset.read}/read`, {method: 'PUT'});
            await renderNotifications();
        }));
        if ($('#read-all')) $('#read-all').onclick = (event) => runAction(event.currentTarget, async () => {
            await request('/notifications/read-all', {method: 'PUT'});
            toast('All notifications marked as read');
            await renderNotifications();
        });
        $$('[data-open-tournament]').forEach(button => button.onclick = () => loadDetail(button.dataset.openTournament));
    } catch (error) {
        $('#app-content').innerHTML = errorBox(error.message);
    }
}

function notificationItem(n) {
    const kinds = {MATCH_SCHEDULED: ['Upcoming match', 'upcoming'], MATCH_RESULT: ['Match result', 'result'], TOURNAMENT_COMPLETED: ['Final results', 'final']};
    const [title, kind] = kinds[n.type] || [label(n.type), 'upcoming'];
    return `<article class="notification ${n.read ? '' : 'unread'} kind-${kind}">
        <div class="notification-icon">${ICONS[kind]}</div>
        <div class="notification-body">
            <div class="notification-title"><strong>${title}</strong>${n.tournamentName ? `<span class="muted">${esc(n.tournamentName)}</span>` : ''}<time datetime="${esc(n.createdAt)}">${esc(timeAgo(n.createdAt))}</time></div>
            <p>${esc(n.message)}</p>
        </div>
        <div class="notification-actions">
            ${n.tournamentId ? `<button type="button" class="button secondary small" data-open-tournament="${n.tournamentId}">Open</button>` : ''}
            ${n.read ? '' : `<button type="button" class="button primary small" data-read="${n.id}">Mark read</button>`}
        </div>
    </article>`;
}

// ------------------------------------------------------------------ administration

async function renderAdmin() {
    state.page = 'admin';
    setHeader('Administration', 'Platform');
    $('#app-content').innerHTML = loading('Loading accounts...');
    try {
        const [overview, users] = await Promise.all([request('/admin/overview'), request('/admin/users')]);
        if (state.page !== 'admin') return;
        $('#app-content').innerHTML = `
            <div class="stats">
                ${statCard('Accounts', overview.users, `${overview.administrators} admin · ${overview.organizers} organizers · ${overview.participants} participants`)}
                ${statCard('Blocked accounts', overview.blockedUsers)}
                ${statCard('Tournaments', overview.tournaments, `${overview.registrationTournaments} open · ${overview.activeTournaments} live · ${overview.completedTournaments} completed`)}
                ${statCard('Matches played', overview.matchesPlayed)}
            </div>
            <div class="section-head"><h2>User accounts</h2><span class="muted">Changes apply on the user's next request.</span></div>
            <div class="table-wrap"><table class="data-table">
                <thead><tr><th>User</th><th>Email</th><th>Role</th><th>Status</th><th>Joined</th><th></th></tr></thead>
                <tbody>${users.map(userRow).join('')}</tbody>
            </table></div>`;

        $$('[data-role-user]').forEach(select => select.onchange = async () => {
            const user = users.find(u => String(u.id) === select.dataset.roleUser);
            const confirmed = await confirmAction({title: 'Change role?', message: `${user.username} will become ${label(select.value)}.`, confirmLabel: 'Change role'});
            if (!confirmed) { select.value = user.role; return; }
            try {
                select.disabled = true;
                await request(`/admin/users/${user.id}/role`, {method: 'PUT', body: JSON.stringify({role: select.value})});
                toast(`${user.username} is now ${label(select.value)}`);
                await renderAdmin();
            } catch (error) {
                select.value = user.role;
                select.disabled = false;
                toast(error.message, 'error');
            }
        });
        $$('[data-status-user]').forEach(button => button.onclick = async () => {
            const user = users.find(u => String(u.id) === button.dataset.statusUser);
            const enable = !user.enabled;
            const confirmed = await confirmAction({
                title: enable ? 'Unblock account?' : 'Block account?',
                message: enable ? `${user.username} will be able to sign in again.` : `${user.username} is signed out on their next request and cannot sign in until unblocked.`,
                confirmLabel: enable ? 'Unblock' : 'Block',
                danger: !enable
            });
            if (!confirmed) return;
            await runAction(button, async () => {
                await request(`/admin/users/${user.id}/status`, {method: 'PUT', body: JSON.stringify({enabled: enable})});
                toast(`${user.username} ${enable ? 'unblocked' : 'blocked'}`);
                await renderAdmin();
            });
        });
    } catch (error) {
        $('#app-content').innerHTML = errorBox(error.message);
    }
}

function userRow(user) {
    const self = user.username === state.username;
    const roles = ['PARTICIPANT', 'ORGANIZER', 'ADMINISTRATOR'];
    return `<tr class="${user.enabled ? '' : 'is-blocked'}">
        <td><div class="user-cell"><span class="avatar small">${esc(user.username[0].toUpperCase())}</span><strong>${esc(user.username)}</strong>${self ? '<span class="you">You</span>' : ''}</div></td>
        <td>${esc(user.email)}</td>
        <td><select data-role-user="${user.id}" ${self ? 'disabled title="You cannot change your own role"' : ''} aria-label="Role of ${esc(user.username)}">
            ${roles.map(role => `<option value="${role}" ${role === user.role ? 'selected' : ''}>${label(role)}</option>`).join('')}
        </select></td>
        <td>${user.enabled ? '<span class="badge">Active</span>' : '<span class="badge status-blocked">Blocked</span>'}</td>
        <td>${user.createdAt ? date(user.createdAt) : '-'}</td>
        <td class="row-actions">${self ? '' : `<button type="button" class="button small ${user.enabled ? 'danger' : 'secondary'}" data-status-user="${user.id}">${user.enabled ? 'Block' : 'Unblock'}</button>`}</td>
    </tr>`;
}

// ------------------------------------------------------------------ start

$('#auth-form').onsubmit = authSubmit;
$$('[data-auth]').forEach(tab => tab.onclick = () => showAuth(tab.dataset.auth));
$('#logout').onclick = signOut;
$('#create-toggle').onclick = renderCreate;
$$('[data-page]').forEach(item => item.onclick = () => navigate(item.dataset.page));

document.addEventListener('input', (event) => {
    if (event.target.form?.id === 'create-form' || event.target.form?.id === 'edit-form') validateDateRange(event.target.form);
});
document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if ($('[data-modal-cancel]')) $('[data-modal-cancel]').click();
    else closeDrawer();
});

showAuth();
