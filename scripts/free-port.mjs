#!/usr/bin/env node
// Free one or more TCP ports before a dev server binds them.
//
// Vite dev/preview run with strictPort, so a leftover listener (a crashed or
// backgrounded `npm run dev`) makes the next start fail with EADDRINUSE. This
// is wired as the `predev` npm lifecycle hook so `npm run dev` self-heals:
// kill whatever holds the port, WAIT until the socket is actually released,
// then Vite binds cleanly.
//
// Usage: node scripts/free-port.mjs <port> [<port> ...]
// No-op when the port is free. Only touches LISTEN sockets. Never kills self.

import { execFileSync, execSync } from 'node:child_process';

const ports = process.argv.slice(2).filter(Boolean);
const self = process.pid;
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);

function kubectlPortForwardPids(port) {
  try {
    const out = execFileSync('ps', ['-eo', 'pid=,args='], {
      stdio: ['ignore', 'pipe', 'ignore']
    }).toString();
    const hostPortMapping = new RegExp(`(?:^|\\s)${port}:[0-9]+(?:\\s|$)`);

    return out.split('\n').flatMap((line) => {
      const match = line.match(/^\s*(\d+)\s+(.*)$/);
      if (!match) return [];
      const [, pid, args] = match;
      if (!args.includes('kubectl') || !args.includes('port-forward')) return [];
      return hostPortMapping.test(args) ? [pid] : [];
    });
  } catch { /* ps absent or restricted */ }
  return [];
}

function pidsOnPort(port) {
  // lsof: macOS + most Linux. Restrict to LISTEN so we don't kill clients.
  try {
    const out = execSync(`lsof -ti tcp:${port} -sTCP:LISTEN`, {
      stdio: ['ignore', 'pipe', 'ignore']
    }).toString().trim();
    if (out) return out.split(/\s+/);
  } catch { /* lsof absent or no match */ }
  // Fallback: fuser (Linux) — no LISTEN filter, but good enough.
  try {
    const out = execSync(`fuser ${port}/tcp`, {
      stdio: ['ignore', 'pipe', 'ignore']
    }).toString().trim();
    if (out) return out.split(/\s+/);
  } catch { /* fuser absent or no match */ }
  // Some Linux setups show kubectl port-forwards in `ss` but hide them from
  // lsof/fuser. Reclaim only the exact host-port mapping kubectl owns.
  return kubectlPortForwardPids(port);
}

function holders(port) {
  return pidsOnPort(port).map(Number).filter((p) => p && p !== self);
}

for (const port of ports) {
  let pids = holders(port);
  if (pids.length === 0) continue;
  const killed = [...pids];

  for (const pid of pids) { try { process.kill(pid, 'SIGTERM'); } catch {} }

  // Wait for the socket to actually free — SIGTERM is async, and Vite's
  // strictPort bind races the OS releasing the port. Poll ~3s, then SIGKILL.
  let waited = 0;
  while ((pids = holders(port)).length > 0 && waited < 3000) {
    if (waited === 1500) {
      for (const pid of pids) { try { process.kill(pid, 'SIGKILL'); } catch {} }
    }
    sleep(100);
    waited += 100;
  }

  if (holders(port).length > 0) {
    console.error(`[free-port] :${port} STILL held after kill — Vite may fail`);
  } else {
    console.log(`[free-port] freed :${port} (pid ${killed.join(', ')})`);
  }
}
