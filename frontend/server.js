// Minimal static file server for the browser client - no dependencies, Node.js 18+.
// Usage: node server.js            (http://localhost:5173)
//        node server.js 5190       or  PORT=5190 node server.js
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = __dirname;
const port = Number(process.argv[2]) || Number(process.env.PORT) || 5173;
const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.ico': 'image/x-icon'
};

http.createServer((request, response) => {
    const urlPath = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const filePath = path.normalize(path.join(root, urlPath === '/' ? 'index.html' : urlPath));
    if (!filePath.startsWith(root + path.sep)) {
        response.writeHead(403, {'Content-Type': 'text/plain'});
        response.end('Forbidden');
        return;
    }
    fs.readFile(filePath, (error, data) => {
        if (error) {
            response.writeHead(404, {'Content-Type': 'text/plain'});
            response.end('Not found');
            return;
        }
        response.writeHead(200, {'Content-Type': types[path.extname(filePath)] || 'application/octet-stream', 'Cache-Control': 'no-cache'});
        response.end(data);
    });
}).listen(port, () => console.log(`Tournament Platform client running at http://localhost:${port}`));
