'use strict';
// Local QA wrapper around the unchanged server implementation. Never logs headers or bodies.
const fs = require('node:fs');
const { createApp } = require('../../server/src/app');
const server = createApp({ dbPath: process.env.DB_PATH });
const original = server.listeners('request')[0];
server.removeAllListeners('request');
let fault = null;
server.on('request', (req, res) => {
  if (req.method === 'PUT' && req.url === '/__edge_fault') {
    let body = '';
    req.on('data', chunk => { body += chunk; });
    req.on('end', () => {
      const value = JSON.parse(body).fault;
      fault = [null, 'network', 403, 409].includes(value) ? value : null;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
    });
    return;
  }
  res.once('finish', () => {
    const path = req.url.replace(/\/(families|destinations)\/[^/?]+/g, '/$1/[id]');
    fs.appendFileSync(process.env.HTTP_TRACE, JSON.stringify({ method: req.method, path, status: res.statusCode }) + '\n');
  });
  if (req.method === 'POST' && req.url.endsWith('/events') && fault) {
    if (fault === 'network') { req.destroy(); return; }
    req.resume();
    res.writeHead(fault, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: fault === 403 ? 'consent_required' : 'invalid_state' }));
    return;
  }
  original(req, res);
});
server.listen(Number(process.env.PORT), '127.0.0.1');
