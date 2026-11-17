/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The machine's quiet window (test/benchmarks/harness/lock.sh and quiet.sh): a session's heavy work waits while a
// batch holds the lock, and the batch waits, a bounded time, for the heavy work already running. Each test has a lock
// of its own, its own marker for heavy work (WOW_BENCH_HEAVY) and its own docker (WOW_BENCH_DOCKER), so no real
// process or container of the machine counts and no harness command runs.
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const HARNESS = join(import.meta.dirname, '../../test/benchmarks/harness');
const QUIET_SH = join(HARNESS, 'quiet.sh');

/** A fresh directory, its lock path, a heavy-work marker of its own and a docker that lists [containers]. */
function machine(containers = []) {
  const dir = mkdtempSync(join(tmpdir(), 'bench-quiet-'));
  const docker = join(dir, 'docker');
  writeFileSync(
    docker,
    `#!/usr/bin/env bash\n${containers.map(name => `echo ${name}`).join('\n')}\n`,
  );
  chmodSync(docker, 0o755);
  const marker = `wow-quiet-fake-build-${process.pid}-${Math.random().toString(36).slice(2)}`;
  return {
    dir,
    lock: join(dir, 'lock'),
    marker,
    env: {
      ...process.env,
      WOW_BENCH_LOCK: join(dir, 'lock'),
      WOW_BENCH_HEAVY: marker,
      WOW_BENCH_DOCKER: docker,
      WOW_BENCH_OWNER: '',
    },
  };
}

/** [script] with lock.sh sourced under bench.sh's `set -euo pipefail`, its log and options stubbed. */
const bash = (m, script, env = {}) =>
  spawnSync(
    'bash',
    [
      '-c',
      `set -euo pipefail
log() { echo "$*" >&2; }
OPTIONS=""
parse() { local a; for a in "$@"; do OPTIONS="$OPTIONS\${a#--}
"; done; }
opt() { local line value="$2"; while IFS= read -r line; do [ "\${line%%=*}" = "$1" ] && value=\${line#*=}; done <<EOF
$OPTIONS
EOF
echo "$value"; }
. ${JSON.stringify(join(HARNESS, 'lock.sh'))}
${script}`,
    ],
    { encoding: 'utf8', env: { ...m.env, ...env } },
  );

const quiet = (m, args, env = {}) =>
  spawnSync(QUIET_SH, args, { encoding: 'utf8', env: { ...m.env, ...env } });

/** A live process whose command line carries [m]'s heavy-work marker, as a build's would carry its name. */
const fakeWork = m => spawn('bash', ['-c', `exec -a ${m.marker} sleep 30`]);

test('with nothing set up, quiet.sh runs its command at once, and a batch finds the machine quiet at once', () => {
  const m = machine();
  const ran = quiet(m, ['echo', 'ran']);
  assert.equal(ran.status, 0, ran.stderr);
  assert.equal(ran.stdout, 'ran\n');
  assert.equal(ran.stderr, '');
  for (const docker of [m.env.WOW_BENCH_DOCKER, join(m.dir, 'no-docker')]) {
    const batch = bash(m, 'lock_await_quiet $$ 0; echo quiet', {
      WOW_BENCH_DOCKER: docker,
    });
    assert.equal(batch.status, 0, batch.stderr);
    assert.equal(batch.stdout, 'quiet\n');
  }
  assert.equal(quiet(m, []).status, 2);
});

test('a held lock makes quiet.sh wait, saying who holds it and since when, and run its command once released', async () => {
  const m = machine();
  const holder = spawn('sleep', ['30']);
  assert.equal(bash(m, `lock_take batch ${holder.pid} ""`).status, 0);
  const waiter = spawn(QUIET_SH, ['echo', 'ran'], { env: m.env });
  let out = '';
  let err = '';
  waiter.stdout.on('data', chunk => (out += chunk));
  waiter.stderr.on('data', chunk => (err += chunk));
  await new Promise(resolve => setTimeout(resolve, 1500));
  assert.equal(out, '', 'it waits while the holder lives');
  assert.match(
    err,
    new RegExp(
      `the machine is held by batch \\(pid ${holder.pid}, since \\d\\d:\\d\\d:\\d\\d\\) for a benchmark batch; \\[echo ran\\] runs once it is released`,
    ),
  );
  assert.equal(bash(m, 'lock_release batch').status, 0);
  const code = await new Promise(resolve => waiter.on('exit', resolve));
  assert.equal(code, 0);
  assert.equal(out, 'ran\n');
  holder.kill();
});

test("a dead holder's lock blocks no one, and the holder's own commands run at once", () => {
  const m = machine();
  const gone = spawnSync('bash', ['-c', 'echo $$'], {
    encoding: 'utf8',
  }).stdout.trim();
  bash(m, `lock_take crashed ${gone} ""`);
  assert.equal(quiet(m, ['echo', 'ran']).stdout, 'ran\n');
  const holder = spawn('sleep', ['30']);
  bash(m, 'rm -rf "$LOCK_DIR"');
  bash(m, `lock_take batch ${holder.pid} ""`);
  assert.equal(
    quiet(m, ['echo', 'ran'], { WOW_BENCH_OWNER: 'batch' }).stdout,
    'ran\n',
  );
  holder.kill();
});

test("a batch's bounded wait fails naming the work still running and the containers not the benchmark's", async () => {
  const m = machine(['someone-postgres', 'wow-e2e-bench-mongo']);
  const work = fakeWork(m);
  await new Promise(resolve => setTimeout(resolve, 200));
  const batch = bash(m, 'lock_take batch $$ ""; lock_await_quiet $$ 2');
  assert.equal(batch.status, 1);
  assert.match(batch.stderr, /still running after 2s, not this batch's/);
  assert.match(batch.stderr, new RegExp(`\\n  ${work.pid} ${m.marker} 30\\n`));
  assert.match(batch.stderr, /\n {2}container someone-postgres\n/);
  assert.doesNotMatch(batch.stderr, /wow-e2e-bench-mongo/);
  work.kill();
  await new Promise(resolve => work.on('exit', resolve));
  const benchOnly = machine(['wow-e2e-bench-postgres']);
  const quietNow = bash(benchOnly, 'lock_await_quiet $$ 2; echo quiet');
  assert.equal(quietNow.status, 0, quietNow.stderr);
  assert.equal(quietNow.stdout, 'quiet\n');
});

test("a batch's own work does not hold it back, and bench.sh lock releases the lock when the machine stays busy", async () => {
  const m = machine();
  // The holder's own build, started by the batch's script before it waits.
  const own = bash(
    m,
    `bash -c 'exec -a ${m.marker} sleep 30' & child=$!
    lock_take batch $$ ""; lock_await_quiet $$ 2; echo quiet; kill $child`,
  );
  assert.equal(own.status, 0, own.stderr);
  assert.equal(own.stdout, 'quiet\n');
  const work = fakeWork(m);
  await new Promise(resolve => setTimeout(resolve, 200));
  const locked = bash(m, 'bench_lock --owner=batch --pid=$$ --quiet-wait=2');
  assert.equal(locked.status, 2);
  assert.match(locked.stderr, new RegExp(`  ${work.pid} ${m.marker}`));
  assert.match(
    locked.stderr,
    /released by batch: the machine did not go quiet/,
  );
  assert.equal(existsSync(m.lock), false);
  work.kill();
  const free = bash(m, 'bench_lock --owner=batch --pid=$$ --quiet-wait=2');
  assert.equal(free.status, 0, free.stderr);
  assert.equal(existsSync(m.lock), true);
});
