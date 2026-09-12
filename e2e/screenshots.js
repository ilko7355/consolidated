// Captures the application screens used in the documentation.
// Requires the backend (started on a fresh database with demo data) and the frontend to be running:
//   APP_URL=http://localhost:5173 node e2e/screenshots.js
// Images are written to docs/screenshots (override with OUT=...). The script only looks around -
// every form it fills is cancelled, so the demo data stays unchanged.
const {chromium} = require('playwright');
const fs = require('fs');
const path = require('path');

const APP = process.env.APP_URL || 'http://localhost:5173';
const OUT = path.resolve(process.env.OUT || path.join(__dirname, '..', 'docs', 'screenshots'));
const PASSWORD = 'Demo12345';

(async () => {
    fs.mkdirSync(OUT, {recursive: true});
    const browser = await chromium.launch();
    const context = await browser.newContext({viewport: {width: 1366, height: 800}, deviceScaleFactor: 1});
    const page = await context.newPage();
    page.on('pageerror', (error) => console.error('PAGE ERROR:', error.message));

    const shot = async (name, options = {}) => {
        await page.mouse.move(5, 5); // no hover effects on the captured screen
        await page.waitForTimeout(450); // let transitions and the toast settle
        await page.screenshot({path: path.join(OUT, name), ...options});
        console.log('saved', name);
    };
    const signIn = async (username) => {
        await page.goto(APP);
        await page.fill('#username', username);
        await page.fill('#password', PASSWORD);
        await page.click('#auth-submit');
        await page.waitForSelector('#app-view:not(.hidden)');
        await page.waitForSelector('.tournament-card');
        await page.waitForTimeout(3700); // wait for the sign-in toast to disappear
    };
    const openTournament = async (name) => {
        await page.click('[data-page="dashboard"]');
        await page.waitForSelector('.tournament-card');
        await page.locator('.tournament-card', {hasText: name}).locator('[data-detail]').click();
        await page.waitForSelector('#detail-body > *');
    };
    const openTab = async (key, selector) => {
        await page.click(`[data-tab="${key}"]`);
        await page.waitForSelector(selector);
    };

    // Sign in and registration
    await page.goto(APP);
    await page.waitForSelector('#auth-form');
    await shot('01-sign-in.png');
    await page.click('[data-auth="register"]');
    await shot('02-register.png');

    // Organizer: dashboard, creating, live bracket, result entry, completed knockout
    await signIn('organizer');
    await shot('03-dashboard-organizer.png');
    await page.click('#create-toggle');
    await page.waitForSelector('#create-form');
    await page.fill('#create-form input[name="name"]', 'Spring Basketball Cup');
    await page.fill('#create-form textarea[name="description"]', '3x3 basketball knockout for teams of students.');
    await shot('04-create-tournament.png');

    await openTournament('PGKNMA Esports Cup');
    await shot('05-tournament-overview.png');
    await openTab('bracket', '.bracket');
    await shot('06-bracket-live.png', {fullPage: true});
    await page.locator('.bracket-match.status-ready').first().click();
    await page.waitForSelector('#result-form');
    await page.fill('#result-form input[name="score1"]', '3');
    await page.fill('#result-form input[name="score2"]', '1');
    await shot('07-result-entry.png');
    await page.keyboard.press('Escape');

    await openTournament('Stara Zagora Rapid Chess Open');
    await openTab('bracket', '.champion-card');
    await shot('08-bracket-completed.png', {fullPage: true});
    await openTab('standings', '.data-table');
    await shot('09-standings-knockout.png');
    await openTab('results', '.champion-banner');
    await shot('10-results-statistics.png', {fullPage: true});
    await page.click('[data-page="notifications"]');
    await page.waitForSelector('.notification');
    await shot('11-notifications-organizer.png');

    // Second organizer: group stage and an open registration
    await signIn('coach.maria');
    await openTournament('School Table Tennis League');
    await page.waitForSelector('.group-card');
    await shot('12-groups-overview.png', {fullPage: true});
    await openTab('bracket', '.group-block');
    await shot('13-group-matches.png', {fullPage: true});
    await openTab('standings', '.group-block');
    await shot('14-group-standings.png', {fullPage: true});
    await openTournament('Autumn Futsal Cup');
    await shot('15-registration-organizer.png');
    await page.fill('#add-participant-form input[name="name"]', 'fc chaika');
    await page.click('#add-participant-form button');
    await page.waitForSelector('.toast.show.error');
    await shot('16-validation-error.png');

    // Participant
    await signIn('georgi');
    await shot('17-dashboard-participant.png');
    await openTournament('Autumn Futsal Cup');
    await page.waitForSelector('#join-form');
    await shot('18-join-tournament.png');
    await page.click('[data-page="my-matches"]');
    await page.waitForSelector('.my-match');
    await shot('19-my-matches.png', {fullPage: true});
    await page.click('[data-page="notifications"]');
    await page.waitForSelector('.notification');
    await shot('20-notifications-participant.png');

    // Administrator
    await signIn('admin');
    await page.click('[data-page="admin"]');
    await page.waitForSelector('.data-table');
    await shot('21-administration.png', {fullPage: true});
    await page.locator('[data-status-user]').first().click();
    await page.waitForSelector('[data-modal]');
    await shot('22-block-confirmation.png');
    await page.click('[data-modal-cancel]');

    // Double elimination: the winners/losers/grand-final view and the option that creates it.
    // The losers bracket is four rounds wide, so this one screen needs a wider window than the rest.
    await signIn('organizer');
    await page.setViewportSize({width: 1720, height: 800});
    await openTournament('Winter Blitz Chess Cup');
    await openTab('bracket', '.bracket-block');
    await shot('24-double-elimination-bracket.png', {fullPage: true});
    await page.setViewportSize({width: 1366, height: 800});
    await page.click('[data-page="dashboard"]');
    await page.waitForSelector('.tournament-card');
    await page.click('#create-toggle');
    await page.waitForSelector('#create-form');
    await page.fill('#create-form input[name="name"]', 'Autumn Blitz Cup');
    await page.selectOption('#format-select', 'DOUBLE_ELIMINATION');
    await page.waitForSelector('#grand-final-row:not([hidden])');
    await shot('25-create-double-elimination.png');

    // Phone-sized layout
    const mobile = await browser.newContext({viewport: {width: 390, height: 844}, deviceScaleFactor: 1.5, isMobile: true, hasTouch: true});
    const phone = await mobile.newPage();
    await phone.goto(APP);
    await phone.fill('#username', 'georgi');
    await phone.fill('#password', PASSWORD);
    await phone.click('#auth-submit');
    await phone.waitForSelector('.tournament-card');
    await phone.waitForTimeout(3700);
    await phone.screenshot({path: path.join(OUT, '23-mobile-dashboard.png')});
    console.log('saved 23-mobile-dashboard.png');

    await browser.close();
})().catch((error) => {
    console.error(error);
    process.exit(1);
});
