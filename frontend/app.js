/**
 * TOURNAMENT PLATFORM - FRONTEND LOGIC
 * Сборен файл с вградени защити, Live Tracking и управление на турнири.
 */

const API = (window.__API_URL__ || 'http://localhost:8080/api').replace(/\/$/, '');

const state = { 
    credentials: null, 
    username: null, 
    role: null, 
    tournaments: [], 
    selected: null, 
    notifications: [], 
    notificationPollTimer: null, 
    bracketPollTimer: null, 
    bracketPollTournamentId: null, 
    bracketRequestSeq: 0, 
    activeDetailTab: null 
};

// --- ПОМОЩНИ ФУНКЦИИ ---
const $ = (selector) => document.querySelector(selector);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const date = (value) => value ? new Date(value + (value.length === 10 ? 'T00:00:00' : '')).toLocaleDateString(undefined, {month:'short', day:'numeric', year:'numeric'}) : '-';

function toast(message){ 
    const node=$('#toast'); 
    node.textContent=message; 
    node.classList.add('show'); 
    setTimeout(()=>node.classList.remove('show'),3000); 
}

function friendlyError(status, body){
    const defaults={
        400:'Please check the entered values.',
        401:'Please sign in again.',
        403:'You do not have permission to perform this action.',
        404:'Resource not found.',
        409:'Operation conflicts with existing data.',
        500:'Something went wrong on the server.'
    };
    return status===400||status===409 ? body?.message||defaults[status] : (defaults[status]||`Request failed (${status})`);
}

// --- ВАЛИДАЦИЯ НА ДАТИ ---
function validateDateRange(form){
    const start = form.elements.startDate;
    const end = form.elements.endDate;
    if(!start || !end) return;
    const isInvalid = start.value && end.value && end.value < start.value;
    end.setCustomValidity(isInvalid ? 'End date cannot be before start date.' : '');
}

document.addEventListener('input', event => {
    if(event.target.form?.id === 'create-form' || event.target.form?.id === 'edit-form') {
        validateDateRange(event.target.form);
    }
});

// --- API ЗАЯВКИ ---
async function request(path, options={}) { 
    const headers = {'Content-Type':'application/json', ...(options.headers||{})}; 
    if(state.credentials) headers.Authorization=`Basic ${state.credentials}`; 
    
    let response; 
    try {
        response = await fetch(`${API}${path}`, {...options, headers});
    } catch {
        throw new Error('Unable to reach the server. Please try again.');
    } 

    let body = null; 
    try {
        body = response.status === 204 ? null : await response.json();
    } catch {} 

    if(response.status === 401) {
        stopNotificationPolling();
        stopBracketPolling();
        state.credentials = null;
        state.username = null;
        state.role = null;
        showAuth(); 
    } 

    if(!response.ok) throw new Error(friendlyError(response.status, body)); 
    return body; 
}

// --- АУТЕНТИКАЦИЯ ---
function showAuth(mode='login'){ 
    $('#auth-view').classList.remove('hidden'); 
    $('#app-view').classList.add('hidden'); 
    document.querySelectorAll('[data-auth]').forEach(tab=>tab.classList.toggle('active',tab.dataset.auth===mode)); 
    const register = mode === 'register'; 
    $('#auth-title').textContent = register ? 'Create your account' : 'Welcome back'; 
    $('#auth-subtitle').textContent = register ? 'Set up access to the tournament workspace.' : 'Sign in to manage your tournaments.'; 
    $('#email-field').classList.toggle('hidden', !register); 
    $('#email').required = register; 
    $('#organizer-field').classList.toggle('hidden', !register); 
    $('#auth-submit').textContent = register ? 'Create account' : 'Sign in'; 
    $('#auth-error').textContent = ''; 
    $('#auth-form').dataset.mode = mode; 
}

async function authSubmit(event){
    event.preventDefault(); 
    console.log("Button clicked, form submitted!"); // <-- Добави това
    
    event.preventDefault(); 
    const register = $('#auth-form').dataset.mode === 'register'; 
    const payload = {username:$('#username').value.trim(), password:$('#password').value}; 
    if(register){
        payload.email = $('#email').value.trim();
        payload.organizer = $('#organizer').checked;
    } 
    $('#auth-submit').disabled = true; 
    $('#auth-error').textContent = ''; 
    try {
        const response = await request(`/auth/${register?'register':'login'}`, {method:'POST', body:JSON.stringify(payload)}); 
        state.credentials = btoa(`${payload.username}:${payload.password}`); 
        state.username = response.username; 
        state.role = response.role;
        showApp();
        toast(register ? 'Account created' : 'Signed in');
    } catch(error) {
        $('#auth-error').textContent = error.message;
    } finally {
        $('#auth-submit').disabled = false;
    }
}

function showApp(){ 
    $('#auth-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
    $('#user-name').textContent = state.username;
    $('#user-role').textContent = state.role;
    $('#user-initial').textContent = state.username?.[0]?.toUpperCase() || 'U';
    loadDashboard();
    loadNotifications();
    startNotificationPolling(); 
}

// --- ДЕШБОРД ---
function statusClass(status){ return status==='COMPLETED' ? 'complete' : status==='IN_PROGRESS' ? 'progress' : ''; }

function renderDashboard(){ 
    const list = state.tournaments; 
    const active = list.filter(item=>item.status==='IN_PROGRESS').length; 
    $('#app-content').innerHTML = `
        <div class="stats">
            <div class="stat"><span class="eyebrow">Total tournaments</span><span class="number">${list.length}</span></div>
            <div class="stat"><span class="eyebrow">In progress</span><span class="number">${active}</span></div>
            <div class="stat"><span class="eyebrow">Your role</span><span class="number" style="font-size:22px">${esc(state.role)}</span></div>
        </div>
        <div class="section-head"><h2>All tournaments</h2><span class="muted">${list.length} records</span></div>
        <div class="tournament-list">
            ${list.length ? list.map(item => `
                <article class="tournament-card">
                    <div class="card-top">
                        <span class="badge ${statusClass(item.status)}">${esc(item.status.replace('_',' '))}</span>
                        <span class="muted">${esc(item.format)}</span>
                    </div>
                    <h3>${esc(item.name)}</h3>
                    <p>${esc(item.description || 'No description provided.')}</p>
                    <div class="card-meta">
                        <span>${date(item.startDate)} - ${date(item.endDate)}</span>
                        <span>${item.participantCount} entrants</span>
                    </div>
                    <button class="button secondary" style="margin-top:16px;width:100%" data-detail="${item.id}">Open tournament</button>
                </article>
            `).join('') : '<div class="empty">No tournaments found.</div>'}
        </div>`; 
    document.querySelectorAll('[data-detail]').forEach(button => button.onclick = () => loadDetail(button.dataset.detail)); 
}

async function loadDashboard(){ 
    stopBracketPolling();
    $('#page-title').textContent = 'Tournaments';
    $('#create-toggle').classList.toggle('hidden', state.role === 'PARTICIPANT');
    $('#app-content').innerHTML = '<div class="loading">Loading tournaments...</div>'; 
    try {
        state.tournaments = await request('/tournaments');
        renderDashboard();
    } catch(error) {
        $('#app-content').innerHTML = `<div class="error-box">${esc(error.message)}</div>`;
    }
}

// --- СЪЗДАВАНЕ И РЕДАКТИРАНЕ ---
function renderCreate(){ 
    $('#page-title').textContent = 'New tournament';
    $('#create-toggle').classList.add('hidden');
    $('#app-content').innerHTML = `
        <button class="back" id="cancel-create">&lt; Back to tournaments</button>
        <div class="form-panel">
            <h2>Create tournament</h2>
            <p class="muted">Define the event before opening registration.</p>
            <form id="create-form" class="form-grid">
                <label>Name<input name="name" required maxlength="150"></label>
                <label>Format<select name="format">
                    <option value="ELIMINATION">Elimination</option>
                    <option value="GROUPS">Groups</option>
                </select></label>
                <label>Start date<input name="startDate" type="date" required></label>
                <label>End date<input name="endDate" type="date" required></label>
                <label class="full">Description<textarea name="description" maxlength="2000"></textarea></label>
                <div class="form-actions full">
                    <button type="button" class="button secondary" id="cancel-form">Cancel</button>
                    <button class="button primary">Create tournament</button>
                </div>
            </form>
        </div>`;
    $('#cancel-create').onclick = loadDashboard;
    $('#cancel-form').onclick = loadDashboard;
    $('#create-form').onsubmit = createTournament;
}

async function createTournament(event){
    event.preventDefault();
    const form = new FormData(event.target);
    const payload = Object.fromEntries(form.entries());
    try {
        await request('/tournaments', {method:'POST', body:JSON.stringify(payload)});
        toast('Tournament created');
        loadDashboard();
    } catch(error) {
        toast(error.message);
    }
}

function renderEditForm() {
    const tournament = state.selected;
    $('#app-content').innerHTML = `
        <button class="back" id="cancel-edit">&lt; Back to tournament</button>
        <div class="form-panel">
            <h2>Edit tournament</h2>
            <form id="edit-form" class="form-grid">
                <label>Name<input name="name" required maxlength="150" value="${esc(tournament.name)}"></label>
                <label>Start date<input name="startDate" type="date" required value="${tournament.startDate}"></label>
                <label>End date<input name="endDate" type="date" required value="${tournament.endDate}"></label>
                <label class="full">Description<textarea name="description" maxlength="2000">${esc(tournament.description || '')}</textarea></label>
                <div class="form-actions full">
                    <button type="button" class="button secondary" id="cancel-edit-form">Cancel</button>
                    <button class="button primary">Save changes</button>
                </div>
            </form>
        </div>`;
    $('#cancel-edit').onclick = () => renderDetail('overview');
    $('#cancel-edit-form').onclick = () => renderDetail('overview');
    $('#edit-form').onsubmit = async (event) => { 
        event.preventDefault(); 
        const payload = Object.fromEntries(new FormData(event.target).entries()); 
        try { 
            await request(`/tournaments/${tournament.id}`, {method:'PUT', body:JSON.stringify(payload)}); 
            toast('Tournament updated'); 
            loadDetail(tournament.id); 
        } catch (error) { 
            toast(error.message); 
        } 
    };
}

// --- ДЕТАЙЛИ НА ТУРНИР ---
async function loadDetail(id){
    stopBracketPolling();
    state.activeDetailTab = null;
    $('#page-title').textContent = 'Tournament details';
    $('#create-toggle').classList.add('hidden');
    $('#app-content').innerHTML = '<div class="loading">Loading tournament...</div>';
    try {
        state.selected = await request(`/tournaments/${id}`);
        renderDetail('overview');
    } catch(error) {
        $('#app-content').innerHTML = `<div class="error-box">${esc(error.message)}</div>`;
    }
}

function renderDetail(tab){
    const t = state.selected;
    $('#app-content').innerHTML = `
        <button class="back" id="back-dashboard">&lt; All tournaments</button>
        <div class="detail-head">
            <div>
                <span class="badge ${statusClass(t.status)}">${esc(t.status.replace('_',' '))}</span>
                <h2>${esc(t.name)}</h2>
                <p>${esc(t.description||'No description provided.')} ${date(t.startDate)} - ${date(t.endDate)}</p>
            </div>
            <div class="detail-actions" style="display:flex; gap:8px;">
                ${canManageTournament() && t.status==='REGISTRATION' ? '<button class="button primary" id="add-participant">+ Add participant</button>' : ''}
            </div>
        </div>
        <div class="tabs">
            <button class="detail-tab" data-tab="overview">Overview</button>
            <button class="detail-tab" data-tab="bracket">Bracket</button>
            <button class="detail-tab" data-tab="rankings">Rankings</button>
        </div>
        <div id="detail-body"></div>`;

    $('#back-dashboard').onclick = loadDashboard;

    // Интегриране на бутони за управление (Edit/Generate)
    if (canManageTournament() && t.status === 'REGISTRATION') {
        const actionsNode = $('.detail-actions');
        
        const genBtn = document.createElement('button');
        genBtn.className = 'button secondary';
        genBtn.textContent = t.format === 'GROUPS' ? 'Generate group matches' : 'Generate bracket';
        genBtn.onclick = async () => { 
            try { 
                await request(`/tournaments/${t.id}/generate-bracket`, {method:'POST'}); 
                toast(t.format === 'GROUPS' ? 'Group matches generated' : 'Bracket generated'); 
                loadDetail(t.id); // Презареждаме, за да се смени статуса на IN_PROGRESS
            } catch (error) { 
                toast(error.message); 
            } 
        };
        actionsNode.append(genBtn);

        const editBtn = document.createElement('button');
        editBtn.className = 'button secondary';
        editBtn.textContent = 'Edit details';
        editBtn.onclick = () => renderEditForm();
        actionsNode.append(editBtn);
    }

    document.querySelectorAll('[data-tab]').forEach(button => {
        button.onclick = () => {
            document.querySelectorAll('[data-tab]').forEach(item => item.classList.remove('active'));
            button.classList.add('active');
            renderDetailBody(button.dataset.tab);
        };
        if(button.dataset.tab === tab) button.click();
    });

    if ($('#add-participant')) $('#add-participant').onclick = addParticipant;
}

// --- LIVE BRACKET POLLING ---
const BRACKET_POLL_INTERVAL_MS = 6000;

function isBracketTabActive(tournamentId) {
    return state.selected?.id === tournamentId && state.activeDetailTab === 'bracket' && Boolean($('#detail-body'));
}

function startBracketPolling(tournamentId){
    stopBracketPolling();
    state.bracketPollTournamentId = tournamentId;
    state.bracketPollTimer = setInterval(() => {
        if (document.hidden) return;
        if (!state.credentials || !isBracketTabActive(tournamentId)) {
            stopBracketPolling();
            return;
        }
        loadAndRenderBracket({ silent: true });
    }, BRACKET_POLL_INTERVAL_MS);
}

function stopBracketPolling(){
    if (state.bracketPollTimer) { clearInterval(state.bracketPollTimer); state.bracketPollTimer = null; }
    state.bracketPollTournamentId = null;
}

async function loadAndRenderBracket({ silent = false, startPolling = false } = {}) {
    const tournament = state.selected;
    if (!tournament) return;
    const tournamentId = tournament.id;
    const seq = ++state.bracketRequestSeq; 
    const body = $('#detail-body');
    
    if (!silent && body) body.innerHTML = '<div class="loading">Loading bracket...</div>';
    
    try {
        const data = await request(`/tournaments/${tournamentId}/bracket`);
        if (seq !== state.bracketRequestSeq || !isBracketTabActive(tournamentId)) return;
        if (silent && document.querySelector('#result-form')) return; // Не прекъсваме организатора
        
        renderBracketBody(tournamentId, data);
        if (startPolling) startBracketPolling(tournamentId);
    } catch (error) {
        if (!silent && body) body.innerHTML = `<div class="error-box">${esc(error.message)}</div>`;
    }
}

function renderBracketBody(tournamentId, data){
    const body = $('#detail-body');
    if (!body || !isBracketTabActive(tournamentId)) return;
    if (!data.length) { body.innerHTML = '<div class="empty">Bracket has not been generated yet.</div>'; return; }

    const rounds = [...new Set(data.map(match => match.round))].sort((a, b) => a - b);
    body.innerHTML = `
        <div class="bracket-toolbar">
            <div><p class="eyebrow">Live bracket</p><h2>${esc(state.selected.name)}</h2></div>
            <button class="button secondary" id="refresh-bracket" type="button">Refresh now</button>
        </div>
        <div class="bracket-scroll">
            <div class="bracket">
                ${rounds.map(round => `
                    <section class="round">
                        <header class="round-header"><span>Round ${round}</span></header>
                        <div class="round-matches">
                            ${data.filter(match => match.round === round).map(renderMatchCard).join('')}
                        </div>
                    </section>
                `).join('')}
            </div>
        </div>`;
    
    document.querySelectorAll('[data-match-id]').forEach(button => {
        button.onclick = () => openMatchDrawer(data.find(m => String(m.id) === button.dataset.matchId));
    });
    $('#refresh-bracket').onclick = () => loadAndRenderBracket({ silent: false });
}

// --- КЛАСИРАНЕ И УЧАСТНИЦИ ---
async function renderDetailBody(tab){
    state.activeDetailTab = tab;
    if (tab === 'bracket') {
        await loadAndRenderBracket({ silent: false, startPolling: true });
        return;
    }
    stopBracketPolling();
    
    if (tab === 'overview' && state.selected?.format === 'GROUPS') {
        return renderGroupAssignment();
    }

    const body = $('#detail-body');
    body.innerHTML = '<div class="loading">Loading data...</div>';
    try {
        if (tab === 'overview') {
            const data = await request(`/tournaments/${state.selected.id}/participants`);
            body.innerHTML = `
                <div class="section-head"><h2>Participants</h2><span class="muted">${data.length} registered</span></div>
                ${data.length ? `
                    <table class="data-table">
                        <thead><tr><th>Name</th><th>Status</th><th>Linked User</th></tr></thead>
                        <tbody>${data.map(p => `<tr><td>${esc(p.name)}</td><td><span class="badge">${esc(p.status)}</span></td><td>${p.linkedUsername ? `@${esc(p.linkedUsername)}` : '-'}</td></tr>`).join('')}</tbody>
                    </table>` : '<div class="empty">No participants registered.</div>'}`;
        } else {
            const data = await request(`/tournaments/${state.selected.id}/rankings`);
            body.innerHTML = data.length ? `
                <table class="data-table">
                    <thead><tr><th>#</th><th>Participant</th><th>P</th><th>W</th><th>D</th><th>L</th><th>+/-</th><th>Pts</th></tr></thead>
                    <tbody>${data.map(p => `<tr><td><strong>${p.placement}</strong></td><td>${esc(p.participant)}</td><td>${p.matchesPlayed}</td><td>${p.wins}</td><td>${p.draws}</td><td>${p.losses}</td><td>${p.scoreDifference}</td><td>${p.points}</td></tr>`).join('')}</tbody>
                </table>` : '<div class="empty">Rankings appear after matches are completed.</div>';
        }
    } catch (error) {
        body.innerHTML = `<div class="error-box">${esc(error.message)}</div>`;
    }
}

// --- ГРУПИ (GROUPS) ---
async function renderGroupAssignment() {
    const body = $('#detail-body');
    body.innerHTML = '<div class="loading">Loading groups...</div>';
    try {
        const [participants, groups] = await Promise.all([
            request(`/tournaments/${state.selected.id}/participants`),
            request(`/tournaments/${state.selected.id}/groups`)
        ]);
        const assigned = new Map(groups.flatMap(g => g.participants.map(p => [p.id, g.id])));
        const canEdit = canManageTournament() && state.selected.status === 'REGISTRATION';
        const unassigned = participants.filter(p => !assigned.has(p.id));

        body.innerHTML = `
            <div class="section-head">
                <div><h2>Group assignments</h2><p class="muted">Every participant must be assigned before generating matches.</p></div>
                ${canEdit ? '<form id="create-group-form" class="inline-form" style="display:flex;gap:8px"><input name="name" required placeholder="Group name"><button class="button secondary">Create group</button></form>' : ''}
            </div>
            <div class="tournament-list">
                ${groups.map(g => `
                    <article class="tournament-card">
                        <div class="card-top"><strong>${esc(g.name)}</strong><span>${g.participants.length}</span></div>
                        ${g.participants.map(p => `<div class="card-meta"><span>${esc(p.name)}</span>${canEdit ? `<button class="text-button" data-rem-g="${g.id}" data-rem-p="${p.id}">Remove</button>` : ''}</div>`).join('')}
                    </article>
                `).join('') || '<div class="empty">No groups created yet.</div>'}
            </div>
            <div class="tournament-card" style="margin-top:20px">
                <div class="card-top"><span class="badge">Unassigned</span></div>
                ${unassigned.map(p => `
                    <div class="card-meta">
                        <span>${esc(p.name)}</span>
                        ${canEdit && groups.length ? `
                            <select data-assign-p="${p.id}">
                                <option value="">Assign to...</option>
                                ${groups.map(g => `<option value="${g.id}">${esc(g.name)}</option>`).join('')}
                            </select>` : ''}
                    </div>`).join('') || '<p class="muted">All participants are assigned.</p>'}
            </div>`;

        // Event Listeners за Групи
        if ($('#create-group-form')) $('#create-group-form').onsubmit = async e => {
            e.preventDefault();
            try {
                await request(`/tournaments/${state.selected.id}/groups`, {method:'POST', body:JSON.stringify({name: new FormData(e.target).get('name')})});
                renderGroupAssignment();
            } catch(err) { toast(err.message); }
        };

        document.querySelectorAll('[data-assign-p]').forEach(sel => sel.onchange = async () => {
            if(!sel.value) return;
            try {
                await request(`/tournaments/${state.selected.id}/groups/${sel.value}/participants/${sel.dataset.assignP}`, {method:'PUT'});
                renderGroupAssignment();
            } catch(err) { toast(err.message); }
        });

        document.querySelectorAll('[data-rem-g]').forEach(btn => btn.onclick = async () => {
            try {
                await request(`/tournaments/${state.selected.id}/groups/${btn.dataset.remG}/participants/${btn.dataset.remP}`, {method:'DELETE'});
                renderGroupAssignment();
            } catch(err) { toast(err.message); }
        });

    } catch (error) { body.innerHTML = `<div class="error-box">${esc(error.message)}</div>`; }
}

// --- ОСТАНАЛИ ФУНКЦИИ (Matches, Notifs, и т.н.) ---
function canManageTournament(){ return state.role==='ADMINISTRATOR' || (state.role==='ORGANIZER' && state.selected?.organizer===state.username); }
function bracketStatusClass(s){ return String(s||'').toLowerCase().replaceAll('_','-'); }
function bracketStatusLabel(s){ return String(s||'UNKNOWN').replaceAll('_',' '); }
function renderMatchCard(m){
    const bye = !m.participant1Id || !m.participant2Id;
    return `
        <button class="bracket-match ${bye?'is-bye':''}" data-match-id="${m.id}">
            <div class="match-card-head"><span>Match ${m.matchNumber}</span><span class="match-state ${bracketStatusClass(m.status)}">${esc(bracketStatusLabel(m.status))}</span></div>
            <div class="match-player ${m.winnerId===m.participant1Id?'is-winner':''}"><span>${esc(m.participant1||'TBD')}</span><strong>${m.score1??'-'}</strong></div>
            <div class="match-player ${m.winnerId===m.participant2Id?'is-winner':''}"><span>${esc(m.participant2||'TBD')}</span><strong>${m.score2??'-'}</strong></div>
            <div class="match-card-foot">${bye?'<span class="bye-label">BYE</span>':'<span>Details</span>'}</div>
        </button>`;
}

async function addParticipant(){
    const name = prompt('Participant name:');
    if(!name?.trim()) return;
    const username = prompt('Account username to link (optional):');
    try {
        const payload = {name: name.trim()};
        if(username?.trim()) payload.username = username.trim();
        await request(`/tournaments/${state.selected.id}/participants`, {method:'POST', body:JSON.stringify(payload)});
        toast('Participant added');
        renderDetailBody('overview');
    } catch(err) { toast(err.message); }
}

// --- NOTIFICATIONS POLLING ---
function startNotificationPolling(){
    stopNotificationPolling();
    state.notificationPollTimer = setInterval(() => { if (!document.hidden) loadNotifications() }, 30000);
}
function stopNotificationPolling(){ if (state.notificationPollTimer) clearInterval(state.notificationPollTimer); }

async function loadNotifications(){
    try {
        const data = await request('/notifications?unread=true');
        $('#notification-count').textContent = data.length || '';
    } catch {}
}

async function renderNotifications(){
    stopBracketPolling();
    $('#page-title').textContent = 'Notifications';
    $('#app-content').innerHTML = '<div class="loading">Loading notifications...</div>';
    try {
        const data = await request('/notifications');
        state.notifications = data;
        $('#app-content').innerHTML = data.length ? data.map(n => `
            <article class="notification ${n.read?'':'unread'}" data-notif="${n.id}" style="cursor:pointer">
                <div><strong>${esc(n.type)}</strong><p>${esc(n.message)}</p></div>
                ${!n.read ? `<button class="button secondary" onclick="markRead(${n.id}, event)">Mark read</button>` : ''}
            </article>
        `).join('') : '<div class="empty">No notifications.</div>';
    } catch(err) { $('#app-content').innerHTML = `<div class="error-box">${esc(err.message)}</div>`; }
}

async function markRead(id, event){
    event.stopPropagation();
    try {
        await request(`/notifications/${id}/read`, {method:'PUT'});
        renderNotifications();
        loadNotifications();
    } catch(err) { toast(err.message); }
}

// --- INITIALIZATION ---
$('#auth-form').onsubmit = authSubmit;
$('#logout').onclick = () => { stopNotificationPolling(); stopBracketPolling(); state.credentials=null; showAuth(); };
$('#create-toggle').onclick = renderCreate;
document.querySelectorAll('[data-page]').forEach(item => {
    item.onclick = () => {
        document.querySelectorAll('[data-page]').forEach(n => n.classList.remove('active'));
        item.classList.add('active');
        item.dataset.page === 'notifications' ? renderNotifications() : loadDashboard();
    };
});

// Глобални функции за Drawer-а (взаимствани от предната версия)
function openMatchDrawer(match){
    if(!match) return;
    const bye = !match.participant1Id || !match.participant2Id;
    const canResult = canManageTournament() && match.status === 'READY' && !bye;
    
    const html = `
        <div class="drawer-backdrop" onclick="closeMatchDrawer()"></div>
        <aside class="match-drawer">
            <button class="drawer-close" onclick="closeMatchDrawer()">&times;</button>
            <h3>Match Details</h3>
            <div class="drawer-players">
                <div class="drawer-player"><span>${esc(match.participant1)}</span><strong>${match.score1??'-'}</strong></div>
                <div class="drawer-player"><span>${esc(match.participant2)}</span><strong>${match.score2??'-'}</strong></div>
            </div>
            ${canResult ? `
                <form id="result-form" class="result-form">
                    <label>${esc(match.participant1)} score <input name="score1" type="number" min="0" required></label>
                    <label>${esc(match.participant2)} score <input name="score2" type="number" min="0" required></label>
                    <button class="button primary wide">Submit Result</button>
                </form>
            ` : ''}
        </aside>`;
    document.body.insertAdjacentHTML('beforeend', html);
    
    if($('#result-form')) {
        $('#result-form').onsubmit = async (e) => {
            e.preventDefault();
            const fd = new FormData(e.target);
            try {
                await request(`/matches/${match.id}/result`, {
                    method:'POST', 
                    body:JSON.stringify({score1: Number(fd.get('score1')), score2: Number(fd.get('score2'))})
                });
                closeMatchDrawer();
                toast('Result saved');
                loadAndRenderBracket({silent: false});
            } catch(err) { toast(err.message); }
        };
    }
}
function closeMatchDrawer(){ 
    $('.drawer-backdrop')?.remove(); 
    $('.match-drawer')?.remove(); 
}

// СТАРТ
showAuth();