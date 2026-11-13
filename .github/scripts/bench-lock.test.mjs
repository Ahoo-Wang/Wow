/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The harness's batch lock (test/benchmarks/harness/lock.sh), its functions sourced alone with stubs of bench.sh's
// log and options, against a lock of its own: no harness command runs, so no store or container is touched.
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const LOCK_SH = join(
  import.meta.dirname,
  '../../test/benchmarks/harness/lock.sh',
);

/** A bash prelude: bench.sh's log and options stubbed, the lock at [lock], lock.sh sourced. */
const prelude = lock => `
log() { echo "$*" >&2; }
OPTIONS=""
parse() { local a; for a in "$@"; do OPTIONS="$OPTIONS\${a#--}
"; done; }
opt() { local line value="$2"; while IFS= read -r line; do [ "\${line%%=*}" = "$1" ] && value=\${line#*=}; done <<EOF
$OPTIONS
EOF
echo "$value"; }
WOW_BENCH_LOCK=${JSON.stringify(lock)}
. ${JSON.stringify(LOCK_SH)}
`;

const bash = (lock, script, env = {}) =>
  spawnSync('bash', ['-c', `${prelude(lock)}\n${script}`], {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });

const fresh = () => join(mkdtempSync(join(tmpdir(), 'bench-lock-')), 'lock');
const holder = lock => readFileSync(join(lock, 'holder'), 'utf8');

test('a lock is taken once, holds its owner, pid and start, and refuses another live owner saying who and how to wait', () => {
  const lock = fresh();
  const taken = bash(lock, `lock_take main ${process.pid} ""`);
  assert.equal(taken.status, 0);
  assert.match(
    holder(lock),
    new RegExp(`^owner=main\\npid=${process.pid}\\nsince=\\d+\\n$`),
  );
  assert.equal(
    bash(lock, `lock_take main ${process.pid} ""`).status,
    0,
    'its owner takes it again',
  );
  const refused = bash(lock, `lock_take other ${process.pid} ""`);
  assert.equal(refused.status, 1);
  assert.match(
    refused.stderr,
    new RegExp(
      `held by main \\(pid ${process.pid}, since \\d\\d:\\d\\d:\\d\\d\\); wait for it with --wait`,
    ),
  );
  assert.match(holder(lock), /^owner=main\n/);
});

test('a lock whose pid is gone is taken over; one whose pid lives is not', () => {
  const lock = fresh();
  const gone = spawnSync('bash', ['-c', 'echo $$'], {
    encoding: 'utf8',
  }).stdout.trim();
  assert.equal(bash(lock, `lock_take crashed ${gone} ""`).status, 0);
  const over = bash(lock, `lock_take next ${process.pid} ""`);
  assert.equal(over.status, 0);
  assert.match(
    over.stderr,
    new RegExp(`taking over the lock of crashed \\(pid ${gone}, gone\\)`),
  );
  assert.match(holder(lock), /^owner=next\n/);
  assert.equal(bash(lock, `lock_take third ${process.pid} ""`).status, 1);
});

test("the guard lets the owner's commands and its descendants pass, and refuses everyone else's", () => {
  const lock = fresh();
  // A live holder that is no ancestor of the guarded commands: a process of its own.
  const other = spawn('sleep', ['30']);
  assert.equal(
    bash(lock, 'lock_guard; echo passed').stdout.trim(),
    'passed',
    'no lock: every command passes',
  );
  bash(lock, `lock_take batch ${other.pid} ""`);
  const refused = bash(lock, 'lock_guard; echo passed');
  assert.equal(refused.status, 2);
  assert.equal(refused.stdout, '');
  assert.match(
    refused.stderr,
    /held by batch \(pid \d+, since .*\); this command waits for no one: wait for the batch with bench\.sh lock --wait/,
  );
  assert.equal(
    bash(lock, 'lock_guard; echo passed', {
      WOW_BENCH_OWNER: 'batch',
    }).stdout.trim(),
    'passed',
  );
  // A lock held by this shell's own pid: the guard runs in a process that descends from it.
  const own = bash(
    lock,
    'rm -rf "$LOCK_DIR"; lock_take mine $$ ""; ( lock_guard; echo passed )',
  );
  assert.equal(own.stdout.trim(), 'passed');
  other.kill();
});

test('a waiting taker queues behind the holder, saying who holds it, and takes the lock the moment it is released', async () => {
  const lock = fresh();
  bash(lock, `lock_take first ${process.pid} ""`);
  const waiter = spawn(
    'bash',
    ['-c', `${prelude(lock)}\nlock_take second $$ 20 && echo taken`],
    {
      env: process.env,
    },
  );
  let out = '';
  let err = '';
  waiter.stdout.on('data', chunk => (out += chunk));
  waiter.stderr.on('data', chunk => (err += chunk));
  await new Promise(resolve => setTimeout(resolve, 1500));
  assert.equal(out, '', 'it waits while the holder lives');
  assert.match(err, /waiting for the harness: held by first/);
  assert.equal(bash(lock, 'lock_release first').status, 0);
  const code = await new Promise(resolve => waiter.on('exit', resolve));
  assert.equal(code, 0);
  assert.equal(out.trim(), 'taken');
  assert.match(holder(lock), /^owner=second\n/);
  bash(lock, 'lock_release second');
  assert.equal(existsSync(lock), false);
});

test("a bounded wait gives up after its seconds; unlock leaves another owner's lock alone", () => {
  const lock = fresh();
  bash(lock, `lock_take holder ${process.pid} ""`);
  const timed = bash(lock, `lock_take late ${process.pid} 2`);
  assert.equal(timed.status, 1);
  assert.match(timed.stderr, /held by holder/);
  const other = bash(lock, 'bench_unlock --owner=late');
  assert.equal(other.status, 2);
  assert.match(other.stderr, /held by holder .*, not late: left alone/);
  assert.equal(bash(lock, 'bench_unlock --owner=holder').status, 0);
  assert.equal(existsSync(lock), false);
});
