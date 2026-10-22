'use strict';
// Passive evidence listener on the real server. No forwarding, fault injection or proxy.
const fs = require('node:fs');
const { createApp } = require('../../server/src/app');
const server = createApp({ dbPath: process.env.DB_PATH });
const flags = ['shareDeparture', 'shareArrival', 'shareAlerts', 'shareLocation', 'shareRouteArrival'];
server.prependListener('request', (req, res) => {
  let consent = '';
  if (req.method === 'PUT' && req.url.endsWith('/consent')) {
    req.on('data', chunk => { if (consent.length < 65536) consent += chunk; });
  }
  res.once('finish', () => {
    const entry = {
      at: new Date().toISOString(), method: req.method,
      path: req.url.replace(/\/(families|destinations)\/[^/?]+/g, '/$1/[id]'),
      authorization: req.headers.authorization ? '[masked]' : '[absent]',
      status: res.statusCode,
      response: res.statusCode === 204 ? '[empty]' : '[body omitted]',
    };
    if (consent) {
      try {
        const value = JSON.parse(consent);
        entry.request = Object.fromEntries(flags.map(key => [key, value[key]]));
        entry.request.recipientCount = Array.isArray(value.recipients) ? value.recipients.length : null;
      } catch { entry.request = '[invalid JSON]'; }
    }
    fs.appendFileSync(process.env.HTTP_TRACE, JSON.stringify(entry) + '\n');
  });
});
server.listen(8787, '127.0.0.1', () => console.log('Polish QA server ready on 127.0.0.1:8787'));
