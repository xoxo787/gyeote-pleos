'use strict';
const { createApp } = require('./app');

const port = Number(process.env.PORT) || 8787;
const host = process.env.HOST || '0.0.0.0';
const server = createApp({
  dbPath: process.env.DB_PATH || undefined,
  corsOrigin: process.env.CORS_ORIGIN || '*',
});
server.listen(port, host, () => {
  console.log(`Passer 데모 서버가 켜졌어요: http://localhost:${port}  (자녀용 웹: /child/)`);
});
