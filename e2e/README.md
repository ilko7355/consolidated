# Notification E2E verification

Real-browser (Playwright/Chromium) verification of the notification frontend
(`frontend/index.html` + `frontend/app.js`, unmodified) against a **mocked**
backend (route interception), since these scripts are meant to run anywhere -
including environments without a live Java/MySQL backend.

For a full end-to-end run against the *real* Spring Boot backend instead of
the mock, start the real app first (`./mvnw spring-boot:run`) and change
`window.__API_URL__` to point at it, or adapt these scripts to skip the
`page.route('**/api/**', ...)` interception entirely.

## Setup

```bash
npm install -g playwright
npx playwright install chromium   # downloads the browser binary (needs network)
```

## Run

```bash
# Functional checks: login, initial load, mark-as-read, persistence, polling
# cleanup, API-failure handling. Uses a manual loadNotifications() call to
# simulate a poll tick instead of waiting the real 30s interval, for speed.
node server.js &            # serves the real frontend files on :5500
node verify.js
kill %1

# Genuinely waits out the real 30-second setInterval (takes ~35s) to prove
# the timer itself fires correctly on its own, with no manual trigger.
node server.js &
node verify_real_interval.js
kill %1
```

## What these scripts do NOT verify

- The real Spring Boot backend's notification-creation logic (covered instead
  by `NotificationFlowTest.java` and friends under `src/test/java`).
- Real MySQL persistence of read/unread state (the mock's "backend" is an
  in-memory JS object for the duration of the script).
- Cross-browser behavior (Chromium only).
