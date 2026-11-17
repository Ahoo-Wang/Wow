/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// A session's nodes go before its stores (test/benchmarks/harness/nodes.sh, bench-common.mjs's HANDOVER_SCRIPT): a
// hand-over's restarted node is stopped by bench.sh's down, one that ignores TERM is killed, and a session does not
// start while a node of an earlier one still listens (L4 run 44055712763). The helpers run alone, on processes and a
// port of the test's own; the hand-over script runs over fake process functions and a fake bench.sh, so no node of
// the machine is found or signalled (X-66).
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { HANDOVER_SCRIPT } from './bench-common.mjs';

const HARNESS = join(import.meta.dirname, '../../test/benchmarks/harness');

/** [script] with nodes.sh sourced under bench.sh's `set -euo pipefail`, its log stubbed, node ports from [ports]. */
const nodes = (script, env = {}, ports = { public: 1, internal: 1 }) =>
  spawnSync(
    'bash',
    [
      '-c',
      `set -euo pipefail
log() { echo "$*" >&2; }
PUBLIC_PORT=${ports.public}
INTERNAL_PORT=${ports.internal}
. ${JSON.stringify(join(HARNESS, 'nodes.sh'))}
${script}`,
    ],
    { encoding: 'utf8', env: { ...process.env, ...env } },
  );

const alive = pid => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

/** A process that ignores TERM, as a node whose stop stalls on its stores does. */
const stubborn = () =>
  spawn('bash', ['-c', "trap '' TERM; while :; do sleep 0.1; done"]);

test("a hand-over's restarted node, adopted by its pid file, is stopped before down removes the stores", async () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-nodes-'));
  const node = spawn('sleep', ['30']);
  const file = join(dir, 'node-restarted.pid');
  writeFileSync(file, `${node.pid}\n`);
  const stopped = nodes('stop_nodes $(adopted_pids); echo stopped', {
    WOW_BENCH_ADOPTED_PIDS: file,
  });
  assert.equal(stopped.status, 0, stopped.stderr);
  assert.equal(stopped.stdout, 'stopped\n');
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(alive(node.pid), false);
  // Nothing set up: no file, no pids, nothing to stop.
  const none = nodes('stop_nodes $(adopted_pids); echo none', {
    WOW_BENCH_ADOPTED_PIDS: join(dir, 'missing'),
  });
  assert.equal(none.status, 0, none.stderr);
  assert.equal(none.stdout, 'none\n');
  // bench.sh's down stops every node, the adopted ones too, before compose removes the stores and their volumes.
  const bench = readFileSync(join(HARNESS, 'bench.sh'), 'utf8');
  const down = bench.slice(bench.indexOf('\ndown() {'));
  const body = down.slice(0, down.indexOf('\n}\n'));
  assert.match(body, /adopted=\$\(adopted_pids\)/);
  assert.ok(
    body.indexOf('stop_nodes ') < body.indexOf('compose down -v'),
    'the nodes stop before the stores go',
  );
});

test('a node that ignores TERM is killed after the stop seconds', async () => {
  const node = stubborn();
  const exited = new Promise(resolve =>
    node.on('exit', (code, signal) => resolve(signal)),
  );
  const stopped = nodes(`stop_nodes ${node.pid}; echo stopped`, {
    WOW_BENCH_NODE_STOP_SECONDS: '1',
  });
  assert.equal(stopped.status, 0, stopped.stderr);
  assert.equal(stopped.stdout, 'stopped\n');
  assert.match(
    stopped.stderr,
    new RegExp(`node pid ${node.pid} did not stop in 1s: killing it`),
  );
  assert.equal(await exited, 'SIGKILL');
});

test("the hand-over script gives bench.sh the restarted node's pid file, and kills a restarted node that lives on", () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-nodes-'));
  const run = join(dir, 'run');
  mkdirSync(run);
  // A restarted node, as the helper records one, that ignores TERM: only KILL ends it (the fake signal's state).
  writeFileSync(join(run, 'node-restarted.pid'), '4343\n');
  const signals = join(dir, 'signals.log');
  const dead = join(dir, 'dead');
  const processes = `
node_pid() { echo 4242; }
node_argv() { ARGV=(true); }
node_cwd() { pwd; }
signal() { echo "signal $1 $2" >> '${signals}'; case "$1" in KILL) touch '${dead}' ;; 0) [ ! -f '${dead}' ] ;; *) true ;; esac; }
`;
  const bench = join(dir, 'bench.sh');
  writeFileSync(
    bench,
    `#!/usr/bin/env bash\necho "$WOW_BENCH_ADOPTED_PIDS" > '${join(dir, 'adopted')}'\nexit 7\n`,
  );
  chmodSync(bench, 0o755);
  const result = spawnSync(
    'bash',
    ['-c', `${processes}\n${HANDOVER_SCRIPT}`, 'handover', bench, 'run'],
    {
      encoding: 'utf8',
      env: {
        ...process.env,
        RUN_DIR: run,
        STOP_AFTER: '0',
        DOWN: '0',
        NODE_PORT: '18081',
        STOP_WAIT: '1',
      },
    },
  );
  assert.equal(result.status, 7, 'the script exits with bench.sh status');
  assert.equal(
    readFileSync(join(dir, 'adopted'), 'utf8').trim(),
    join(run, 'node-restarted.pid'),
  );
  const signalled = readFileSync(signals, 'utf8');
  assert.match(
    signalled,
    /^signal TERM 4343\n(signal 0 4343\n)+signal KILL 4343\nsignal 0 4343\n$/,
  );
  assert.match(
    readFileSync(join(run, 'handover.log'), 'utf8'),
    /the restarted node \(pid 4343\) did not stop: killing it/,
  );
});

/** Listens on every port of [ports], all of them or none: the listening servers, or undefined when one is taken. */
async function listenOn(ports) {
  const servers = [];
  for (const port of ports) {
    const server = createServer();
    const ok = await new Promise(resolve => {
      server.once('error', () => resolve(false));
      server.listen(port, '127.0.0.1', () => resolve(true));
    });
    if (!ok) {
      await Promise.all(servers.map(s => new Promise(r => s.close(r))));
      return undefined;
    }
    servers.push(server);
  }
  return servers;
}

test('a session does not start while anything listens on a node port, and names it', async t => {
  const tools = spawnSync('bash', ['-c', 'command -v lsof || command -v ss'], {
    encoding: 'utf8',
  });
  if (tools.status !== 0) {
    t.skip('neither lsof nor ss: who listens cannot be told');
    return;
  }
  // The span is the test's own: every public and internal port refuse_busy_ports reads (ten of each, the internal ones
  // 1,000 above), below the ephemeral ranges (macOS 49152, Linux 32768) so no other process is handed one meanwhile,
  // and held by the test while it looks.
  let ports;
  let servers;
  for (let tries = 0; !servers && tries < 50; tries++) {
    const base = 20000 + Math.floor(Math.random() * 10000);
    ports = { public: base, internal: base + 1000 };
    const span = Array.from({ length: 10 }, (_, offset) => offset);
    servers = await listenOn([
      ...span.map(offset => ports.public + offset),
      ...span.map(offset => ports.internal + offset),
    ]);
  }
  assert.ok(servers, 'no free span of node ports below the ephemeral range');
  const busy = nodes('refuse_busy_ports', {}, ports);
  assert.equal(busy.status, 1);
  assert.match(
    busy.stderr,
    new RegExp(
      `a node of an earlier session still listens; stop it before a new session starts:\\n  port ${ports.public}: pid ${process.pid} `,
    ),
  );
  assert.match(
    busy.stderr,
    new RegExp(`\\n  port ${ports.internal + 9}: pid ${process.pid} `),
  );
  await Promise.all(servers.map(s => new Promise(resolve => s.close(resolve))));
  const free = nodes('refuse_busy_ports; echo free', {}, ports);
  assert.equal(free.status, 0, free.stderr);
  assert.equal(free.stdout, 'free\n');
});
