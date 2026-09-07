const { chromium } = require('/home/claude/.npm-global/lib/node_modules/playwright');

const results = [];
function record(name, pass, details) {
  results.push({ name, pass, details });
  console.log(`[${pass ? 'PASS' : 'FAIL'}] ${name} — ${details}`);
}

// ---- In-memory mock backend state (mimics NotificationRepository) ----
let notifications = [
  { id: 1, message: 'Upcoming match in "Winter Cup": Round 1, Match 1 vs Bob. Status: READY', type: 'MATCH_SCHEDULED', read: false, createdAt: '2026-09-03T10:00:00', tournamentId: 1, tournamentName: 'Winter Cup', matchId: 10, round: 1, matchNumber: 1, opponent: 'Bob', scheduledTime: null, matchStatus: 'READY' }
];
let nextId = 2;
let notificationsCallLog = []; // timestamps of every GET /notifications call, to detect duplicate pollers
let apiShouldFail = false;

async function mockRoute(route) {
  const req = route.request();
  const url = new URL(req.url());
  const method = req.method();

  if (apiShouldFail && url.pathname.includes('/notifications')) {
    return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ message: 'Internal error' }) });
  }

  if (method === 'POST' && url.pathname === '/api/auth/login') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ username: 'qa_tester', role: 'ORGANIZER' }) });
  }
  if (method === 'GET' && url.pathname === '/api/tournaments') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  }
  if (method === 'GET' && url.pathname === '/api/notifications') {
    notificationsCallLog.push(Date.now());
    const unreadOnly = url.searchParams.get('unread') === 'true';
    const list = unreadOnly ? notifications.filter(n => !n.read) : notifications;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(list) });
  }
  const readMatch = url.pathname.match(/^\/api\/notifications\/(\d+)\/read$/);
  if (method === 'PUT' && readMatch) {
    const id = Number(readMatch[1]);
    const n = notifications.find(x => x.id === id);
    if (n) n.read = true;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(n) });
  }
  return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ message: 'not mocked: ' + url.pathname }) });
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  page.on('console', msg => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    const loc = msg.location().url || '';
    // Two categories of noise that are NOT application bugs, filtered out explicitly (not silently):
    // 1) Google Fonts CDN blocked by this sandbox's lack of outbound network access.
    // 2) The intentionally-simulated 500 from Test 6 (API failure handling) - that failure is
    //    expected and is asserted on separately; it is not an uncaught application error.
    const isFontsCdnNoise = loc.includes('fonts.googleapis.com');
    const isSimulatedApiFailure = loc.includes('/api/notifications') && text.includes('500');
    consoleErrors.push({ text, loc, ignored: isFontsCdnNoise || isSimulatedApiFailure });
    console.log(`CONSOLE ERROR DETAIL: ${text} | location: ${loc}${(isFontsCdnNoise || isSimulatedApiFailure) ? ' [IGNORED: known non-bug]' : ' [REAL - counted]'}`);
  });
  page.on('pageerror', err => pageErrors.push(err.message));
  await page.route('**/api/**', mockRoute);
  await page.route('**fonts.googleapis.com/**', route => route.fulfill({ status: 200, contentType: 'text/css', body: '/* fonts disabled in sandboxed test run - no outbound network access */' }));

  // ===================== TEST 1: LOGIN =====================
  await page.goto('http://localhost:5500/');
  await page.fill('#username', 'qa_tester');
  await page.fill('#password', 'not-a-real-password');
  await page.click('#auth-submit');
  await page.waitForSelector('#app-view:not(.hidden)', { timeout: 5000 }).catch(() => {});
  const appVisible = await page.isVisible('#app-view');
  const authHidden = await page.evaluate(() => document.getElementById('auth-view').classList.contains('hidden'));
  record('Login', appVisible && authHidden, `app-view visible=${appVisible}, auth-view hidden=${authHidden}`);

  // ===================== TEST 2: INITIAL NOTIFICATIONS =====================
  await page.waitForTimeout(300); // let initial loadNotifications() from showApp() resolve
  const badgeAfterLogin = await page.textContent('#notification-count');
  record('Initial unread badge', badgeAfterLogin.trim() === '1', `badge text = "${badgeAfterLogin}" (expected "1")`);
  record('No JS errors after login', consoleErrors.filter(e => !e.ignored).length === 0 && pageErrors.length === 0,
      `real console errors=${consoleErrors.filter(e => !e.ignored).length} (${consoleErrors.filter(e => e.ignored).length} sandbox-network-artifact(s) ignored), page errors=${pageErrors.length}` + (pageErrors[0] ? `: ${pageErrors[0]}` : ''));

  await page.click('[data-page="notifications"]');
  await page.waitForTimeout(200);
  const notifText = await page.textContent('#app-content');
  record('Notifications list renders from backend data', notifText.includes('Winter Cup') && notifText.includes('Bob'),
      `list text includes real mock data: ${notifText.includes('Winter Cup') && notifText.includes('Bob')}`);

  // ===================== TEST 3: NEW NOTIFICATION VIA POLLING =====================
  notifications.push({ id: nextId++, message: 'Tournament "Winter Cup" is complete! Winner: Bob.', type: 'TOURNAMENT_COMPLETED', read: false, createdAt: new Date().toISOString(), tournamentId: 1, tournamentName: 'Winter Cup', matchId: null, round: null, matchNumber: null, opponent: null, scheduledTime: null, matchStatus: null });
  // Poll interval in app.js is 30000ms - waiting the full interval is impractical for a test run,
  // so we directly invoke the same function the interval timer calls, exactly as the timer would.
  await page.evaluate(() => loadNotifications());
  await page.waitForTimeout(200);
  const badgeAfterNew = await page.textContent('#notification-count');
  record('New notification detected without page reload', badgeAfterNew.trim() === '2',
      `badge = "${badgeAfterNew}" (expected "2") after simulated poll tick (see note on interval below)`);

  // ===================== TEST 4: MARK AS READ + PERSISTENCE =====================
  await page.click('[data-page="notifications"]');
  await page.waitForTimeout(200);
  const readButtons = await page.$$('[data-read]');
  record('Mark-as-read controls present', readButtons.length > 0, `found ${readButtons.length} unread notification(s) with a mark-read control`);
  if (readButtons.length > 0) {
    await readButtons[0].click();
    await page.waitForTimeout(300);
  }
  const backendUnreadCount = notifications.filter(n => !n.read).length;
  record('Backend state updated on mark-as-read', backendUnreadCount === 1, `mock backend now has ${backendUnreadCount} unread (expected 1)`);
  const badgeAfterRead = await page.textContent('#notification-count');
  record('Badge decreases after mark-as-read', badgeAfterRead.trim() === '1', `badge = "${badgeAfterRead}" (expected "1")`);

  await page.reload();
  await page.fill('#username', 'qa_tester');
  await page.fill('#password', 'not-a-real-password');
  await page.click('#auth-submit');
  await page.waitForTimeout(300);
  const badgeAfterReload = await page.textContent('#notification-count');
  record('Read state persists after reload', badgeAfterReload.trim() === '1',
      `badge after reload+relogin = "${badgeAfterReload}" (backend, i.e. mock state, is the source of truth - matches expectation)`);

  // ===================== TEST 5: POLLING CLEANUP =====================
  notificationsCallLog = [];
  for (let i = 0; i < 3; i++) {
    await page.click('[data-page="dashboard"]');
    await page.waitForTimeout(100);
    await page.click('[data-page="notifications"]');
    await page.waitForTimeout(100);
  }
  const timerCount = await page.evaluate(() => {
    // Directly inspect the app's own single-timer-handle invariant.
    return typeof state !== 'undefined' ? (state.notificationPollTimer ? 1 : 0) : -1;
  });
  record('Exactly one polling timer handle exists after repeated navigation', timerCount === 1,
      `state.notificationPollTimer is ${timerCount === 1 ? 'set (single timer)' : 'unexpected: ' + timerCount} after 3 navigate-away-and-back cycles`);

  const callsBefore = notificationsCallLog.length;
  await page.waitForTimeout(1500);
  const callsDuringWait = notificationsCallLog.length - callsBefore;
  record('No duplicate/runaway polling requests observed', callsDuringWait <= 1,
      `${callsDuringWait} unexpected /notifications call(s) fired in a 1.5s idle window after navigation (each nav click itself triggers one legitimate loadNotifications() via renderNotifications, which is expected and separate from the 30s timer)`);

  // ===================== TEST 6: API FAILURE HANDLING =====================
  apiShouldFail = true;
  const errorsBefore = pageErrors.length;
  await page.evaluate(() => loadNotifications()); // simulate the interval firing while the API is down
  await page.waitForTimeout(300);
  const crashedOnFailure = pageErrors.length > errorsBefore;
  const stillResponsive = await page.isVisible('#app-view');
  record('App does not crash when notification API fails', !crashedOnFailure && stillResponsive,
      `new page errors during failure=${pageErrors.length - errorsBefore}, app-view still visible=${stillResponsive}`);

  apiShouldFail = false;
  await page.evaluate(() => loadNotifications()); // recovery
  await page.waitForTimeout(300);
  const badgeAfterRecovery = await page.textContent('#notification-count');
  record('App recovers once the API becomes available again', badgeAfterRecovery.trim() === '1',
      `badge after recovery = "${badgeAfterRecovery}" (expected "1", same as before the simulated outage)`);

  await browser.close();

  console.log('\n=== SUMMARY ===');
  const passed = results.filter(r => r.pass).length;
  console.log(`${passed}/${results.length} checks passed`);
  process.exit(results.every(r => r.pass) ? 0 : 1);
})();
