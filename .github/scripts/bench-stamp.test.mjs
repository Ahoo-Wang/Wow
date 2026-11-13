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
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

const STAMP_SH = join(
  import.meta.dirname,
  '../../test/benchmarks/harness/stamp.sh',
);
const SERVER = 'test/benchmarks/server/build/install/benchmark-server';
const LOAD = 'test/benchmarks/load/build/install/benchmark-load';

const git = (repo, ...args) =>
  spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).stdout.trim();

/** A repository with one commit and nothing else: no build, no stamp. */
function repository() {
  const repo = mkdtempSync(join(tmpdir(), 'bench-stamp-'));
  git(repo, 'init', '-q');
  writeFileSync(join(repo, 'README.md'), 'tree\n');
  writeFileSync(join(repo, '.gitignore'), 'build/\nout/\n');
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
