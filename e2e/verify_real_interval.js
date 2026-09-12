const { chromium } = require('playwright');

let notifications = [
  { id: 1, message: 'Initial notification', type: 'MATCH_SCHEDULED', read: false, createdAt: '2026-09-03T10:00:00', tournamentId: 1, tournamentName: 'Winter Cup', matchId: 10, round: 1, matchNumber: 1, opponent: 'Bob', scheduledTime: null, matchStatus: 'READY' }
];
let pollRequestTimestamps = [];

async function mockRoute(route) {
  const req = route.request();
  const url = new URL(req.url());
  const method = req.method();
  if (method === 'POST' && url.pathname === '/api/auth/login') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ username: 'qa_tester', role: 'ORGANIZER' }) });
  }
  if (method === 'GET' && url.pathname === '/api/tournaments') {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
  }
  if (method === 'GET' && url.pathname === '/api/notifications') {
    pollRequestTimestamps.push(Date.now());
    const unreadOnly = url.searchParams.get('unread') === 'true';
    const list = unreadOnly ? notifications.filter(n => !n.read) : notifications;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(list) });
  }
  return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.route('**/api/**', mockRoute);
  await page.route('**fonts.googleapis.com/**', route => route.fulfill({ status: 200, contentType: 'text/css', body: '' }));

  const start = Date.now();
  await page.goto('http://localhost:5500/');
  await page.fill('#username', 'qa_tester');
  await page.fill('#password', 'irrelevant');
  await page.click('#auth-submit');
  await page.waitForTimeout(500); // this is the ONE call from showApp()'s initial loadNotifications()

  const callsFromInitialLoad = pollRequestTimestamps.length;
  console.log(`Calls immediately after login (expected 1, from the initial loadNotifications() call): ${callsFromInitialLoad}`);

  // Add a new notification on the "backend" now, then genuinely WAIT for the real 30s setInterval
  // to fire on its own - no manual invocation of loadNotifications() this time.
  notifications.push({ id: 2, message: 'Arrived only via the real timer', type: 'MATCH_SCHEDULED', read: false, createdAt: new Date().toISOString(), tournamentId: 1, tournamentName: 'Winter Cup', matchId: 11, round: 1, matchNumber: 2, opponent: 'Carol', scheduledTime: null, matchStatus: 'READY' });

  console.log('Waiting 31 real seconds for the actual setInterval (30000ms) to fire on its own...');
  await page.waitForTimeout(31000);

  const badge = await page.textContent('#notification-count');
  const totalCalls = pollRequestTimestamps.length;
  const elapsed = Date.now() - start;

  console.log(`Total /notifications calls over ${elapsed}ms: ${totalCalls}`);
  console.log(`Badge after real timer fired: "${badge}" (expected "2")`);
  console.log(`[${badge.trim() === '2' && totalCalls >= 2 ? 'PASS' : 'FAIL'}] Real 30s setInterval genuinely fired and picked up the new notification without any manual trigger`);

  await browser.close();
  process.exit(badge.trim() === '2' && totalCalls >= 2 ? 0 : 1);
})();
