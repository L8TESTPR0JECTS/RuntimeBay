import { createServer } from 'node:http';

const port = Number(process.env.PORT || 4310);
const server = createServer((request, response) => {
  console.log(`${request.method} ${request.url}`);
  response.setHeader('Content-Type', 'application/json');
  response.end(JSON.stringify({ application: 'Runtime Bay demo', status: 'healthy', time: new Date().toISOString() }, null, 2));
});
server.listen(port, '127.0.0.1', () => console.log(`Demo API listening at http://127.0.0.1:${port}`));
const heartbeat = setInterval(() => console.log('Heartbeat · ready for requests'), 5000);
process.on('SIGTERM', () => {
  console.log('Shutting down the demo API');
  clearInterval(heartbeat);
  server.close(() => process.exit(0));
});
server.on('error', error => { console.error(error.message); process.exit(1); });
