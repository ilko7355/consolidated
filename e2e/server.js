// Serves the real frontend/ files on port 5500 for the Playwright verification scripts.
process.env.PORT = process.env.PORT || '5500';
require('../frontend/server.js');
