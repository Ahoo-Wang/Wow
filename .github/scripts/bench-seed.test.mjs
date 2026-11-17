/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// Seeding once (bench-seed.mjs, test/benchmarks/harness/seed.sh): a copy is made once an arm's servers, properties and
// data set, every later session restores it, and a copy that is not the session's is refused. The harness's helpers run
// over a docker, a load generator and a setup of the test's own, so no container, store or node of the machine is
// touched.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { restoreSeeded } from './bench-seed.mjs';

const HARNESS = join(import.meta.dirname, '../../test/benchmarks/harness');

test('a copy is seeded once an arm, every later session restores it, and an unprimed or fresh arm seeds itself', () => {
  const out = mkdtempSync(join(tmpdir(), 'bench-seed-'));
  const calls = [];
  const run = (benchPath, args, props) => {
    calls.push([args, props]);
    return 0;
  };
  const options = { benchPath: 'bench.sh', out, accounts: 100000, run };
  const head = { server: ['--backend=postgres'], props: { s: true } };
  const base = {
    server: ['--backend=postgres', '--install=/b'],
    props: { s: true },
  };
  const seed = ['--seed-concurrency=256', '--load-cpus=2-3'];
  assert.deepEqual(restoreSeeded({ ...options, ...head, primed: true, seed }), [
    `--restore=${join(out, 'seed-1')}`,
  ]);
  assert.deepEqual(calls, [
    [
      [
        'seed-copy',
        '--backend=postgres',
        '--accounts=100000',
        ...seed,
        '--label=seed',
        `--out=${join(out, 'seed-1')}`,
      ],
      { s: true },
    ],
  ]);
  // The same arm again: its copy, seeded no more.
  assert.deepEqual(restoreSeeded({ ...options, ...head, primed: true }), [
    `--restore=${join(out, 'seed-1')}`,
  ]);
  assert.equal(calls.length, 1);
  // Another build, or the same build under other properties: a copy of its own.
  assert.deepEqual(restoreSeeded({ ...options, ...base, primed: true }), [
    `--restore=${join(out, 'seed-2')}`,
  ]);
  assert.deepEqual(
    restoreSeeded({
      ...options,
      ...head,
      props: { s: true, x: 1 },
      primed: true,
    }),
    [`--restore=${join(out, 'seed-3')}`],
  );
  assert.equal(calls.length, 3);
  // A small data set, and the arm that seeds afresh by its property: nothing to restore, nothing seeded.
  assert.deepEqual(restoreSeeded({ ...options, ...head, primed: false }), []);
  assert.deepEqual(
    restoreSeeded({
      ...options,
      ...head,
      props: { s: true, 'bench.seed': 'fresh' },
      primed: true,
    }),
    [],
  );
  assert.equal(calls.length, 3);
});

test('a copy that could not be seeded is tried once, and its sessions seed afresh', () => {
  const out = mkdtempSync(join(tmpdir(), 'bench-seed-'));
  let tries = 0;
  const options = {
    benchPath: 'bench.sh',
    out,
    server: ['--backend=mongo'],
    accounts: 100000,
    primed: true,
    run: () => {
      tries++;
      return 2;
    },
  };
  assert.deepEqual(restoreSeeded(options), []);
  assert.deepEqual(restoreSeeded(options), []);
  assert.equal(tries, 1);
});

/** A tree with a fake server install stamped [stamp], and a docker that records its calls and answers inspect. */
function machine(
  stamp = 'commit=aaaaaaaa0123456789abcdef0123456789abcdef\ndirty=no\n',
) {
  const dir = mkdtempSync(join(tmpdir(), 'bench-seed-'));
  const install = join(dir, 'install');
  mkdirSync(install);
  writeFileSync(join(install, 'wow-build-stamp'), stamp);
  return { dir, install, calls: join(dir, 'calls.log') };
}

/** [script] with stamp.sh and seed.sh sourced under bench.sh's `set -euo pipefail`, after stubs of what they call. */
const harness = (m, args, script) =>
  spawnSync(
    'bash',
    [
      '-c',
      `set -euo pipefail
log() { echo "$*" >&2; }
REPO=${JSON.stringify(m.dir)}
OPTIONS=""
parse() { local a; for a in "$@"; do OPTIONS="$OPTIONS\${a#--}
"; done; }
opt() { local line value="$2"; while IFS= read -r line; do [ "\${line%%=*}" = "$1" ] && value=\${line#*=}; done <<EOF
$OPTIONS
EOF
echo "$value"; }
docker() {
  echo "docker $*" >> ${JSON.stringify(m.calls)}
  case "$1 \${3:-}" in
    "inspect {{.Image}}") echo sha256:image ;;
    "inspect {{.Config.Image}}") echo postgres:17-alpine ;;
  esac
}
load() { echo "load $1" >> ${JSON.stringify(m.calls)}; }
postgres_sql() { echo "sql $1" >> ${JSON.stringify(m.calls)}; }
stop_nodes() { echo "stop_nodes $*" >> ${JSON.stringify(m.calls)}; }
BACKEND=postgres
ACCOUNTS=100000
TARGET_LIST=http://127.0.0.1:18080
. ${JSON.stringify(join(HARNESS, 'stamp.sh'))}
. ${JSON.stringify(join(HARNESS, 'seed.sh'))}
parse ${args.map(arg => JSON.stringify(arg)).join(' ')}
${script}`,
    ],
    {
      encoding: 'utf8',
      env: { ...process.env, SPRING_APPLICATION_JSON: '{"s":true}' },
    },
  );

const callsOf = m => {
  try {
    return readFileSync(m.calls, 'utf8');
  } catch {
    return '';
  }
};

test('with nothing set up, a session seeds afresh: no copy, no docker, and a CHECKPOINT on PostgreSQL only', () => {
  const m = machine();
  const out = join(m.dir, 'out');
  mkdirSync(out);
  const fresh = harness(
    m,
    [],
    `OUT=${JSON.stringify(out)}; seed_restore postgres -f compose.yml; seed_accounts postgres; seed_mode`,
  );
  assert.equal(fresh.status, 0, fresh.stderr);
  assert.equal(fresh.stdout, 'fresh\n');
  assert.equal(callsOf(m), 'load seed\nsql checkpoint\n');
  const mongo = machine();
  const onMongo = harness(
    mongo,
    [],
    `OUT=${JSON.stringify(out)}; BACKEND=mongo; seed_restore mongo -f compose.yml; seed_accounts mongo`,
  );
  assert.equal(onMongo.status, 0, onMongo.stderr);
  assert.equal(callsOf(mongo), 'load seed\n');
});

test("seed-copy keeps the store's data after the nodes and the store stop, and says what the copy is", () => {
  const m = machine();
  const out = join(m.dir, 'copy');
  const setup = `setup() { OUT=${JSON.stringify(out)}; mkdir -p "$OUT"; SERVER_PIDS=(4242); echo "setup SEED_COPY=$SEED_COPY" >> ${JSON.stringify(m.calls)}; }`;
  const copied = harness(
    m,
    [`--install=${m.install}`, `--out=${out}`],
    `${setup}; seed_copy`,
  );
  assert.equal(copied.status, 0, copied.stderr);
  const seedTxt = readFileSync(join(out, 'seed.txt'), 'utf8');
  const volume = /^volume=(wow-e2e-bench-seed-\d+)$/m.exec(seedTxt)[1];
  assert.equal(
    seedTxt,
    `volume=${volume}
server=commit=aaaaaaaa0123456789abcdef0123456789abcdef dirty=no
install=${m.install}
args=
props={"s":true}
bus=false
backend=postgres
accounts=100000
image=sha256:image
`,
  );
  // The nodes stop before the store, and the store before its data is copied.
  assert.equal(
    callsOf(m),
    `setup SEED_COPY=true
docker inspect --format {{.Image}} wow-e2e-bench-postgres
stop_nodes 4242
docker stop -t 120 wow-e2e-bench-postgres
docker volume rm -f ${volume}
docker volume create --label wow-e2e-bench-seed=${out} ${volume}
docker inspect --format {{.Config.Image}} wow-e2e-bench-postgres
docker run --rm --volumes-from wow-e2e-bench-postgres -v ${volume}:/seed --entrypoint cp postgres:17-alpine -a /var/lib/postgresql/data/. /seed/
`,
  );
  // A build with changes is rebuilt by every run, so no copy of it is kept; nor is one without --out.
  const dirty = machine(
    'commit=aaaaaaaa0123456789abcdef0123456789abcdef\ndirty=yes\n',
  );
  const refused = harness(
    dirty,
    [`--install=${dirty.install}`, `--out=${out}`],
    `${setup}; seed_copy`,
  );
  assert.equal(refused.status, 2);
  assert.match(
    refused.stderr,
    /seed-copy: the server's build is aaaaaaaa01 with changes/,
  );
  assert.equal(
    harness(m, [`--install=${m.install}`], `${setup}; seed_copy`).status,
    2,
  );
});

test("a restore fills the store's data directory from a copy of the session's build, and refuses any other", () => {
  const m = machine();
  const copy = join(m.dir, 'copy');
  mkdirSync(copy);
  const identity = `server=commit=aaaaaaaa0123456789abcdef0123456789abcdef dirty=no
install=${m.install}
args=
props={"s":true}
bus=false
backend=postgres
accounts=100000
image=sha256:image`;
  writeFileSync(
    join(copy, 'seed.txt'),
    `volume=wow-e2e-bench-seed-7\n${identity}\n`,
  );
  const restored = harness(
    m,
    [`--install=${m.install}`, `--restore=${copy}`],
    'seed_restore postgres -f compose.yml; seed_accounts postgres; seed_mode',
  );
  assert.equal(restored.status, 0, restored.stderr);
  assert.equal(restored.stdout, 'restored\n');
  assert.match(
    restored.stderr,
    new RegExp(`restored 100000 accounts from the copy in ${copy}`),
  );
  // The store's container is created, not started, and its data directory filled; nothing is seeded.
  assert.equal(
    callsOf(m),
    `docker compose -f compose.yml create postgres
docker inspect --format {{.Image}} wow-e2e-bench-postgres
docker inspect --format {{.Config.Image}} wow-e2e-bench-postgres
docker run --rm --volumes-from wow-e2e-bench-postgres -v wow-e2e-bench-seed-7:/seed:ro --entrypoint cp postgres:17-alpine -a /seed/. /var/lib/postgresql/data/
`,
  );
  // A build with changes, which every run rebuilds: refused before anything is created.
  const dirty = machine(
    'commit=dddddddd0123456789abcdef0123456789abcdef\ndirty=yes\n',
  );
  const refused = harness(
    dirty,
    [`--install=${dirty.install}`, `--restore=${copy}`],
    'seed_restore postgres -f compose.yml',
  );
  assert.equal(refused.status, 2);
  assert.match(refused.stderr, /the server's build is dddddddd01 with changes/);
  assert.equal(callsOf(dirty), '');
  // Another build's copy: refused, naming what differs.
  const other = machine();
  const stamped = join(m.dir, 'other-install');
  mkdirSync(stamped);
  writeFileSync(
    join(stamped, 'wow-build-stamp'),
    'commit=dddddddd0123456789abcdef0123456789abcdef\ndirty=no\n',
  );
  const otherBuild = harness(
    other,
    [`--install=${stamped}`, `--restore=${copy}`],
    'seed_restore postgres -f compose.yml',
  );
  assert.equal(otherBuild.status, 2);
  assert.match(
    otherBuild.stderr,
    /--restore: the copy in .* is not this session's/,
  );
  assert.match(
    otherBuild.stderr,
    /< server=commit=aaaaaaaa0123456789abcdef0123456789abcdef dirty=no\n.*\n?.*> server=commit=dddddddd0123456789abcdef0123456789abcdef dirty=no/s,
  );
  assert.doesNotMatch(callsOf(other), /docker run/);
  // With --tmpfs, or from a directory that holds no copy: refused before anything is created.
  for (const args of [
    [`--install=${m.install}`, `--restore=${copy}`, '--tmpfs=true'],
    [`--install=${m.install}`, `--restore=${join(m.dir, 'none')}`],
  ]) {
    const none = machine();
    const result = harness(none, args, 'seed_restore postgres -f compose.yml');
    assert.equal(result.status, 2);
    assert.equal(callsOf(none), '');
  }
});
