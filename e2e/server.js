const http = require('http');
const fs = require('fs');
const path = require('path');
const root = '/home/claude/tournament-final/frontend';
const mime = { '.html':'text/html', '.js':'application/javascript', '.css':'text/css' };
http.createServer((req, res) => {
  let filePath = path.join(root, req.url === '/' ? 'index.html' : req.url);
  fs.readFile(filePath, (err, data) => {
    if (err) { res.writeHead(404); res.end('Not found'); return; }
    res.writeHead(200, { 'Content-Type': mime[path.extname(filePath)] || 'text/plain' });
    res.end(data);
  });
}).listen(5500, () => console.log('Static server on 5500'));
