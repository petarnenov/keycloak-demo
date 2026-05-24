// Entrypoint for the SPA container. Lets the public port accept either a
// plain-HTTP request (which we 301 to https) or a TLS handshake (which we
// pass through, unaltered, to vite preview running on an internal port).
//
// Trick: TLS connections always begin with content-type 0x16 (handshake).
// HTTP requests begin with an ASCII method like GET/POST. Sniffing the
// first byte tells us which it is. For TLS we pipe raw bytes to vite, so
// the cert is still presented end-to-end (no re-encryption).
//
// Args: argv[2] = public port (defaults to 5185). The vite preview server
// runs on (public + 1000) on localhost.

const net = require('net');
const { spawn } = require('child_process');

const PUBLIC_PORT = parseInt(process.argv[2] || '5185', 10);
const INTERNAL_PORT = PUBLIC_PORT + 1000;

const vite = spawn(
  'npx',
  ['vite', 'preview', '--host', '127.0.0.1', '--port', String(INTERNAL_PORT), '--strictPort'],
  { stdio: 'inherit', env: process.env }
);

vite.on('exit', (code) => {
  console.error(`[entrypoint] vite preview exited with code ${code}`);
  process.exit(code ?? 1);
});

const server = net.createServer((socket) => {
  socket.once('data', (chunk) => {
    socket.pause();
    if (chunk[0] === 0x16) {
      // TLS handshake — pass the bytes through to vite's HTTPS listener.
      const upstream = net.connect(INTERNAL_PORT, '127.0.0.1', () => {
        upstream.write(chunk);
        socket.pipe(upstream);
        upstream.pipe(socket);
        socket.resume();
      });
      upstream.on('error', () => socket.destroy());
      socket.on('error', () => upstream.destroy());
    } else {
      // Plain HTTP — read the request line + Host header and redirect.
      const text = chunk.toString('utf8', 0, Math.min(chunk.length, 2048));
      const firstLine = text.split('\r\n')[0] || '';
      const path = firstLine.split(' ')[1] || '/';
      const hostMatch = text.match(/\r\nHost:\s*([^\r\n]+)/i);
      const host = hostMatch ? hostMatch[1].trim() : `localhost:${PUBLIC_PORT}`;
      socket.end(
        'HTTP/1.1 301 Moved Permanently\r\n' +
        `Location: https://${host}${path}\r\n` +
        'Content-Length: 0\r\n' +
        'Connection: close\r\n\r\n'
      );
    }
  });
  socket.on('error', () => {/* ignore — client closed before sending data */});
});

server.listen(PUBLIC_PORT, '0.0.0.0', () => {
  console.log(`[entrypoint] :${PUBLIC_PORT} accepts HTTP (301→https) and TLS (passthrough → 127.0.0.1:${INTERNAL_PORT})`);
});

const shutdown = (sig) => {
  vite.kill(sig);
  server.close();
};
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT',  () => shutdown('SIGINT'));
