/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The harness's build stamps (test/benchmarks/harness/stamp.sh), sourced alone under bench.sh's own shell options
// (`set -euo pipefail`) with stubs of its log, options and build, against a git repository of its own: nothing is
// built and no harness command runs.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  appendFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const ROOT = join(import.meta.dirname, '../..');
const HARNESS = join(ROOT, 'test/benchmarks/harness');
const STAMP_SH = join(HARNESS, 'stamp.sh');
const SERVER = 'test/benchmarks/server/build/install/benchmark-server';
const LOAD = 'test/benchmarks/load/build/install/benchmark-load';

const git = (repo, ...args) =>
  spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).stdout.trim();

const REPORT = 'test/benchmarks/results/reports/m5max-idle/before/idle.txt';

/** A repository with one commit and nothing else: no build, no stamp; [files] are committed too. */
function repository(files = {}) {
  const repo = mkdtempSync(join(tmpdir(), 'bench-stamp-'));
  git(repo, 'init', '-q');
  writeFileSync(join(repo, 'README.md'), 'tree\n');
  writeFileSync(join(repo, '.gitignore'), 'build/\nout/\n.gradle/\n.kotlin/\n');
  for (const [file, text] of Object.entries(files)) write(repo, file, text);
  git(repo, 'add', '.');
  git(
    repo,
    '-c',
    'user.name=bench',
    '-c',
    'user.email=bench@example.invalid',
    'commit',
    '-qm',
    'tree',
  );
  return repo;
}

/** Writes [text] to [file] (relative to [repo]), its directories too. */
function write(repo, file, text) {
  mkdirSync(join(repo, file, '..'), { recursive: true });
  writeFileSync(join(repo, file), text);
}

/** Installs [dir] (relative to [repo]) stamped [commit] and [dirty]. */
function stamped(repo, dir, commit, dirty = 'no') {
  mkdirSync(join(repo, dir), { recursive: true });
  writeFileSync(
    join(repo, dir, 'wow-build-stamp'),
    `commit=${commit}\ndirty=${dirty}\n`,
  );
}

/** Runs [script] with stamp.sh sourced for [repo] under `set -euo pipefail`; build only says so. */
const run = (repo, script, { options = '', env = {} } = {}) =>
  spawnSync(
    'bash',
    [
      '-c',
      `set -euo pipefail
log() { echo "$*" >&2; }
build() { echo built; }
OPTIONS=${JSON.stringify(options)}
opt() { local line value="$2"; while IFS= read -r line; do [ "\${line%%=*}" = "$1" ] && value=\${line#*=}; done <<EOF
$OPTIONS
EOF
echo "$value"; }
REPO=${JSON.stringify(repo)}
OUT=${JSON.stringify(join(repo, 'out'))}
mkdir -p "$OUT"
. ${JSON.stringify(STAMP_SH)}
${script}`,
    ],
    { encoding: 'utf8', env: { ...process.env, CI: '', ...env } },
  );

test('with nothing set up, the installs are rebuilt and the run names them unstamped', () => {
  const repo = repository();
  const result = run(repo, 'fresh_installs; record_installs; echo done');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'built\ndone\n');
  assert.match(
    result.stderr,
    /its installs are not its build: benchmark-server \(unstamped\) benchmark-load \(unstamped\); rebuilding/,
  );
  assert.equal(
    readFileSync(join(repo, 'out', 'install.txt'), 'utf8'),
    'server=unstamped\nload=unstamped\n',
  );
});

test("installs stamped with the clean tree's commit are not rebuilt, and the run names their commit", () => {
  const repo = repository();
  const head = git(repo, 'rev-parse', 'HEAD');
  stamped(repo, SERVER, head);
  stamped(repo, LOAD, head);
  const result = run(repo, 'fresh_installs; record_installs');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
  assert.match(
    result.stderr,
    new RegExp(
      `server build ${head.slice(0, 10)}, load generator ${head.slice(0, 10)}`,
    ),
  );
  assert.equal(
    readFileSync(join(repo, 'out', 'install.txt'), 'utf8'),
    `server=commit=${head} dirty=no\nload=commit=${head} dirty=no\n`,
  );
});

test("another commit's build, or a tree with changes, is rebuilt; --install's server is not checked", () => {
  const repo = repository();
  const head = git(repo, 'rev-parse', 'HEAD');
  stamped(repo, SERVER, 'a'.repeat(40));
  stamped(repo, LOAD, head);
  const other = run(repo, 'fresh_installs');
  assert.equal(other.stdout, 'built\n');
  assert.match(other.stderr, /benchmark-server \(aaaaaaaaaa\); rebuilding/);
  assert.equal(
    run(repo, 'fresh_installs', { options: 'install=/elsewhere' }).stdout,
    '',
    "--install names another build: only the load generator's is this tree's",
  );
  stamped(repo, SERVER, head);
  writeFileSync(join(repo, 'README.md'), 'changed\n');
  const changed = run(repo, 'fresh_installs');
  assert.equal(changed.stdout, 'built\n', 'a tree with changes is rebuilt');
  assert.match(changed.stderr, /this tree is [0-9a-f]{10} with changes/);
});

test("on CI another commit's stamp stops the run, an unstamped build is only named, and nothing is built", () => {
  const repo = repository();
  const head = git(repo, 'rev-parse', 'HEAD');
  stamped(repo, LOAD, head);
  const unstamped = run(repo, 'fresh_installs; echo passed', {
    env: { CI: 'true' },
  });
  assert.equal(unstamped.status, 0, unstamped.stderr);
  assert.equal(unstamped.stdout, 'passed\n');
  stamped(repo, SERVER, 'b'.repeat(40));
  const stale = run(repo, 'fresh_installs; echo passed', {
    env: { CI: 'true' },
  });
  assert.equal(stale.status, 2);
  assert.equal(stale.stdout, '');
  assert.match(
    stale.stderr,
    /benchmark-server is the build of bbbbbbbbbb, not of this tree \([0-9a-f]{10}\): on CI the builds are the workflow's, so the run stops/,
  );
});

test('outside a git repository the tree is unknown with changes, and no helper ends the script', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bench-stamp-none-'));
  const result = run(dir, 'echo "$(tree_stamp)"; stamp_text unstamped');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'commit=unknown dirty=yes\nunstamped\n');
  assert.equal(existsSync(join(dir, 'out')), true);
});

test("a reused run names the server build its session's up started, not one built since", () => {
  const repo = repository();
  const head = git(repo, 'rev-parse', 'HEAD');
  stamped(repo, SERVER, head);
  stamped(repo, LOAD, head);
  const session = join(repo, 'out', 'session');
  // `up` writes its session's line while its servers run this build.
  assert.equal(
    run(repo, `session_builds > ${JSON.stringify(session)}`).status,
    0,
  );
  // A rebuild after `up`, of another commit: the session's servers still run the first.
  stamped(repo, SERVER, 'c'.repeat(40));
  const reused = run(repo, `. ${JSON.stringify(session)}; record_installs`);
  assert.equal(reused.status, 0, reused.stderr);
  assert.equal(
    readFileSync(join(repo, 'out', 'install.txt'), 'utf8'),
    `server=commit=${head} dirty=no\nload=commit=${head} dirty=no\n`,
  );
  assert.match(reused.stderr, new RegExp(`server build ${head.slice(0, 10)},`));
  const fresh = run(repo, 'record_installs');
  assert.match(
    readFileSync(join(repo, 'out', 'install.txt'), 'utf8'),
    /^server=commit=c{40} dirty=no\n/,
    'a run without a session names the install as it is',
  );
  assert.equal(fresh.status, 0);
});

test('measure checks the builds of a program that runs Wow and names them; the ClickHouse insert runs none', () => {
  const repo = repository();
  // measure.sh sourced too, with parse stubbed as bench.sh's: the walk stops at its missing AccessWalkBench (exit 3),
  // after it checked and named its builds; the insert experiment is stubbed, so no container starts.
  const measure = args => `HARNESS=${JSON.stringify(HARNESS)}
parse() { local a; for a in "$@"; do OPTIONS="$OPTIONS\${a#--}
"; done; }
check_java() { :; }
. "$HARNESS/measure.sh"
measure_clickhouse_insert() { echo inserted; }
measure ${args}`;
  const walk = run(repo, measure(`--program=walk --out="$OUT/walk"`));
  assert.equal(walk.status, 3, walk.stderr);
  assert.equal(walk.stdout, 'built\n');
  assert.match(walk.stderr, /has no AccessWalkBench/);
  assert.equal(
    readFileSync(join(repo, 'out', 'walk', 'install.txt'), 'utf8'),
    'server=unstamped\nload=unstamped\n',
  );
  const insert = run(repo, measure('--program=clickhouse-insert'));
  assert.equal(insert.status, 0, insert.stderr);
  assert.equal(insert.stdout, 'inserted\n');
});

test('a report written or edited under results/reports/ leaves the tree clean; any other change, under results/ too, does not', () => {
  const repo = repository({ [REPORT]: 'session\n' });
  const head = git(repo, 'rev-parse', 'HEAD');
  stamped(repo, SERVER, head);
  stamped(repo, LOAD, head);
  write(
    repo,
    'test/benchmarks/results/reports/m5max-idle/after/idle.txt',
    'new session\n',
  );
  appendFileSync(join(repo, REPORT), 'edited\n');
  const report = run(repo, 'echo "$(tree_stamp)"; fresh_installs');
  assert.equal(report.status, 0, report.stderr);
  assert.equal(
    report.stdout,
    `commit=${head} dirty=no\n`,
    "a --report session's output is not code: the installs are reused",
  );
  for (const file of [
    'test/benchmarks/results/e2e.md',
    'test/benchmarks/results/reports-old/idle.txt',
    'test/benchmarks/harness/bench.sh',
  ]) {
    write(repo, file, 'changed\n');
    const changed = run(repo, 'echo "$(tree_stamp)"; fresh_installs');
    assert.equal(changed.stdout, `commit=${head} dirty=yes\nbuilt\n`, file);
    rmSync(join(repo, file));
  }
  appendFileSync(join(repo, 'README.md'), 'changed\n');
  assert.equal(
    run(repo, 'tree_stamp').stdout,
    `commit=${head} dirty=yes\n`,
    'a tracked file changed outside the reports',
  );
});

// The build's stamp (test/benchmarks/build-stamp.gradle.kts), run for real by this repository's Gradle wrapper on a
// project of its own that only applies it, against the same repository as tree_stamp: both readers say the same.
const java = spawnSync('java', ['-version']).status === 0;
test(
  "the build's stamp and tree_stamp judge the same tree the same",
  { skip: !java && 'no java on the PATH to run Gradle with', timeout: 300_000 },
  () => {
    const repo = repository({
      [REPORT]: 'session\n',
      'settings.gradle.kts': 'rootProject.name = "stamp"\n',
      'build.gradle.kts': `plugins { distribution }\napply(from = ${JSON.stringify(join(ROOT, 'test/benchmarks/build-stamp.gradle.kts'))})\n`,
      // The daemon this starts leaves soon after: it is no other build's.
      'gradle.properties': 'org.gradle.daemon.idletimeout=10000\n',
    });
    const buildStamp = () => {
      const result = spawnSync(
        join(ROOT, 'gradlew'),
        ['-p', repo, '--quiet', '--console=plain', 'buildStamp'],
        { encoding: 'utf8' },
      );
      assert.equal(result.status, 0, result.stdout + result.stderr);
      const text = readFileSync(
        join(repo, 'build/stamp/wow-build-stamp'),
        'utf8',
      );
      return text.trim().replace('\n', ' ');
    };
    const head = git(repo, 'rev-parse', 'HEAD');
    write(
      repo,
      'test/benchmarks/results/reports/m5max-idle/after/idle.txt',
      'new session\n',
    );
    appendFileSync(join(repo, REPORT), 'edited\n');
    assert.equal(run(repo, 'tree_stamp').stdout, `commit=${head} dirty=no\n`);
    assert.equal(buildStamp(), `commit=${head} dirty=no`);
    write(repo, 'test/benchmarks/results/e2e.md', 'changed\n');
    assert.equal(run(repo, 'tree_stamp').stdout, `commit=${head} dirty=yes\n`);
    assert.equal(buildStamp(), `commit=${head} dirty=yes`);
  },
);
