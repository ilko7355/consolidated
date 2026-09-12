/**
 * Starts the whole application with one command: the Spring Boot backend and the static browser client.
 *
 *   node dev.js              backend + client
 *   node dev.js --demo       also creates the demo accounts and tournaments (only into an empty database)
 *   PORT=5190 node dev.js    serve the client on another port
 *
 * No npm dependencies: the client is served from this same Node process, the backend runs as a child
 * process, and Ctrl+C stops both. The runnable jar is built once with the Maven Wrapper if it is missing.
 * If a backend is already answering on port 8080, this only starts the client and reuses that backend.
 */
const {spawn, spawnSync} = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

const ROOT = __dirname;
const WINDOWS = process.platform === 'win32';
const JAR = path.join(ROOT, 'target', 'tournament-platform-0.0.1-SNAPSHOT.jar');
const API_PORT = 8080;
const CLIENT_PORT = Number(process.env.PORT) || 5173;
const WITH_DEMO = process.argv.includes('--demo') || process.env.DEMO_DATA === 'true';

let backend = null;
let stopping = false;

function log(source, chunk) {
    for (const line of chunk.toString().split(/\r?\n/)) {
        if (line.trim()) console.log(`[${source}] ${line}`);
    }
}

/** JAVA_HOME, then PATH, then the usual install folders - so the app also starts where PATH is not set up. */
function javaExecutable() {
    const binary = WINDOWS ? 'java.exe' : 'java';
    if (process.env.JAVA_HOME) {
        const candidate = path.join(process.env.JAVA_HOME, 'bin', binary);
        if (fs.existsSync(candidate)) return candidate;
    }
    if (!spawnSync(binary, ['-version'], {stdio: 'ignore'}).error) {
        return binary;
    }
    const roots = WINDOWS
        ? ['C:\\Program Files\\Eclipse Adoptium', 'C:\\Program Files\\Java', 'C:\\Program Files\\Microsoft',
           'C:\\Program Files\\Amazon Corretto', 'C:\\Program Files\\Zulu']
        : ['/usr/lib/jvm', '/Library/Java/JavaVirtualMachines'];
    for (const root of roots) {
        if (!fs.existsSync(root)) continue;
        for (const entry of fs.readdirSync(root).sort().reverse()) {
            for (const suffix of [['bin', binary], ['Contents', 'Home', 'bin', binary]]) {
                const candidate = path.join(root, entry, ...suffix);
                if (fs.existsSync(candidate)) {
                    console.log(`[runner ] using ${candidate}`);
                    return candidate;
                }
            }
        }
    }
    return binary; // not found: spawn fails below with a clear message
}

/** Any HTTP answer on the API port means a backend is already running. */
function backendAlreadyRunning() {
    return new Promise((resolve) => {
        const request = http.request(
            {host: 'localhost', port: API_PORT, path: '/api/tournaments', method: 'GET', timeout: 1500},
            () => resolve(true));
        request.on('error', () => resolve(false));
        request.on('timeout', () => { request.destroy(); resolve(false); });
        request.end();
    });
}

function buildJarIfMissing() {
    if (fs.existsSync(JAR)) return;
    console.log('[runner ] the runnable jar is missing - building it once with the Maven Wrapper...');
    const wrapper = WINDOWS ? 'mvnw.cmd' : './mvnw';
    const build = spawnSync(wrapper, ['-q', '-DskipTests', 'package'], {cwd: ROOT, stdio: 'inherit', shell: WINDOWS});
    if (build.status !== 0) {
        console.error('[runner ] build failed - run the Maven Wrapper directly to see the error');
        process.exit(1);
    }
}

function stop(code) {
    if (stopping) return;
    stopping = true;
    if (backend && !backend.killed) backend.kill();
    setTimeout(() => process.exit(code), 300);
}

function startBackend() {
    buildJarIfMissing();

    // Java 17 on Windows creates a socket file in TEMP while starting Tomcat; a very long or non-Latin TEMP
    // path makes that fail ("Unable to establish loopback connection"), so use a short folder in the project.
    const socketDir = path.join(ROOT, 'target', 'uds');
    fs.mkdirSync(socketDir, {recursive: true});

    const args = [`-Djdk.net.unixdomain.tmpdir=${socketDir}`, '-jar', JAR];
    if (WITH_DEMO) args.push('--app.demo-data=true');

    console.log(`[runner ] starting the backend${WITH_DEMO ? ' with demo data' : ''}...`);
    backend = spawn(javaExecutable(), args, {cwd: ROOT, windowsHide: true});

    backend.stdout.on('data', (chunk) => {
        log('backend', chunk);
        if (chunk.toString().includes('Started TournamentPlatformApplication')) {
            console.log(`[runner ] API ready on http://localhost:${API_PORT}/api`);
            console.log(`[runner ] open the application at http://localhost:${CLIENT_PORT}`);
            if (WITH_DEMO) {
                console.log('[runner ] demo accounts: admin, organizer, coach.maria, georgi - password Demo12345');
            }
        }
    });
    backend.stderr.on('data', (chunk) => log('backend', chunk));
    backend.on('error', (error) => {
        console.error(`[runner ] could not start Java (${error.message}) - install JDK 17 or set JAVA_HOME`);
        stop(1);
    });
    backend.on('exit', (code) => {
        if (stopping) return;
        console.error(`[runner ] the backend stopped with code ${code} - check that MySQL is running and that`);
        console.error('[runner ] application-local.properties contains your MySQL user and password');
        stop(code === null ? 1 : code);
    });
}

function startClient() {
    // frontend/server.js reads PORT; the arguments are trimmed so "--demo" is never taken for a port number.
    process.env.PORT = String(CLIENT_PORT);
    process.argv = process.argv.slice(0, 2);
    require('./frontend/server.js');
}

process.on('SIGINT', () => { console.log('\n[runner ] stopping...'); stop(0); });
process.on('SIGTERM', () => stop(0));
process.on('exit', () => { if (backend && !backend.killed) backend.kill(); });

(async () => {
    if (await backendAlreadyRunning()) {
        console.log(`[runner ] a backend is already answering on port ${API_PORT} - reusing it`);
    } else {
        startBackend();
    }
    startClient();
})();
