/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { ROOT } from './project-version.mjs';

// A failed job's Gradle home loses only the record of the artifacts a repository answered 404 for, after its daemon
// stopped, before setup-gradle saves the home to the cache; and the step never fails.

const SCRIPT = join(ROOT, '.github/scripts/gradle-forget-missing.sh');
const RECORD = 'caches/modules-2/metadata-2.107/module-artifact.bin';
const KEPT = [
  'caches/modules-2/metadata-2.107/module-metadata.bin',
  'caches/modules-2/metadata-2.107/resource-at-url.bin',
  'caches/modules-2/files-2.1/org.jspecify/jspecify/1.0.0/7425a601/jspecify-1.0.0.jar',
];

/** A checkout (with a wrapper that logs its arguments, exiting [exit]) and a Gradle home holding [files]. */
function fixture({
  wrapper = true,
  daemon = true,
  files = [RECORD, ...KEPT],
  exit = 0,
} = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'gradle-forget-missing-'));
  const checkout = join(dir, 'checkout');
  const home = join(dir, 'home');
  mkdirSync(checkout);
  mkdirSync(home);
  if (wrapper) {
    const gradlew = join(checkout, 'gradlew');
    writeFileSync(
      gradlew,
      `#!/usr/bin/env bash\necho "$@" >> "${join(dir, 'gradlew.log')}"\nexit ${exit}\n`,
    );
    chmodSync(gradlew, 0o755);
  }
  if (daemon) mkdirSync(join(home, 'daemon/9.9.1'), { recursive: true });
  for (const file of files) {
    mkdirSync(join(home, file, '..'), { recursive: true });
    writeFileSync(join(home, file), 'x');
  }
  const run = () =>
    spawnSync('bash', [SCRIPT], {
      cwd: checkout,
      env: { ...process.env, GRADLE_USER_HOME: home },
      encoding: 'utf8',
    });
  const calls = () =>
    existsSync(join(dir, 'gradlew.log'))
      ? readFileSync(join(dir, 'gradlew.log'), 'utf8').trim().split('\n')
      : [];
  return { home, run, calls };
}

test('the daemon is stopped, then only the record of missing artifacts is removed', () => {
  const { home, run, calls } = fixture();
  const result = run();
  assert.equal(result.status, 0);
  assert.deepEqual(calls(), ['--stop']);
  assert.equal(existsSync(join(home, RECORD)), false);
  for (const file of KEPT) assert.ok(existsSync(join(home, file)), file);
  assert.match(
    result.stdout,
    /gradle-forget-missing: removed .*module-artifact\.bin/,
  );
});

test('a job where Gradle never ran stops nothing and fails nothing', () => {
  // No daemon, no record: the wrapper is not run (it would download a distribution just to stop nothing).
  const noDaemon = fixture({ daemon: false, files: [] });
  const result = noDaemon.run();
  assert.equal(result.status, 0);
  assert.deepEqual(noDaemon.calls(), []);
  assert.match(result.stdout, /no record of missing artifacts/);
  // No wrapper at all (a job that failed at its checkout) and no Gradle home.
  const bare = fixture({ wrapper: false, daemon: false, files: [] });
  assert.equal(bare.run().status, 0);
});

test('a wrapper that fails to stop the daemon neither fails the step nor keeps the record', () => {
  const { home, run, calls } = fixture({ exit: 1 });
  const result = run();
  assert.equal(result.status, 0);
  assert.deepEqual(calls(), ['--stop']);
  assert.equal(existsSync(join(home, RECORD)), false);
  assert.match(result.stdout, /--stop failed; the record is removed anyway/);
});

test('every metadata version a home holds loses its record, and nothing else', () => {
  const older = 'caches/modules-2/metadata-2.106/module-artifact.bin';
  const { home, run } = fixture({ files: [RECORD, older, ...KEPT] });
  assert.equal(run().status, 0);
  assert.equal(existsSync(join(home, RECORD)), false);
  assert.equal(existsSync(join(home, older)), false);
  assert.deepEqual(
    readdirSync(join(home, 'caches/modules-2/metadata-2.106')),
    [],
  );
});

/** Each job of each workflow that sets up Gradle: its file, name, whether it only reads the cache, and its text. */
function gradleJobs() {
  const dir = join(ROOT, '.github/workflows');
  return readdirSync(dir)
    .filter(name => /\.ya?ml$/.test(name))
    .flatMap(file => {
      const lines = readFileSync(join(dir, file), 'utf8').split('\n');
      const jobs = lines.indexOf('jobs:');
      if (jobs < 0) return [];
      const starts = lines.flatMap((line, i) =>
        i > jobs && /^ {2}[A-Za-z0-9_-]+:\s*$/.test(line) ? [i] : [],
      );
      return starts.map((start, k) => {
        const text = lines
          .slice(start, starts[k + 1] ?? lines.length)
          .join('\n');
        return {
          file,
          name: lines[start].trim().slice(0, -1),
          readOnly: /cache-read-only:\s*true\b/.test(text),
          text,
        };
      });
    })
    .filter(job => /gradle\/actions\/setup-gradle@/.test(job.text));
}

const STEP = [
  '      - name: Forget the artifacts a repository answered 404 for',
  "        if: failure() && hashFiles('gradlew') != ''",
  '        run: .github/scripts/gradle-forget-missing.sh',
].join('\n');

test('every job that saves the Gradle cache forgets its 404 records last when it fails; a read-only one does not', () => {
  const jobs = gradleJobs();
  const writers = jobs.filter(job => !job.readOnly);
  // The twelve of 2026-11-19 but h2-stage2.yml's, deleted with spike H2's stage 2: on main they save the Gradle home,
  // a pull request's runs only read it.
  assert.deepEqual(writers.map(job => `${job.file}:${job.name}`).sort(), [
    'ci.yml:axon-server',
    'ci.yml:catalog-console-e2e',
    'ci.yml:check',
    'ci.yml:eshop-web-e2e',
    'ci.yml:integration',
    'ci.yml:migrate-data-image',
    'ci.yml:ops-console-e2e',
    'ci.yml:view-store-contract',
    'documentation-deploy.yml:dokka',
    'mongo-cluster.yml:cluster',
    'soak.yml:soak',
  ]);
  for (const job of writers) {
    // The job's last step, so that it follows every step that ran Gradle; the comment after it is the next job's.
    const steps = job.text
      .replace(/\n(?:\s*\n| {2}#.*\n?)*$/, '')
      .split(/\n(?= {6}- )/);
    assert.equal(steps.at(-1), STEP, `${job.file}:${job.name}`);
  }
  for (const job of jobs.filter(job => job.readOnly))
    assert.ok(
      !job.text.includes('gradle-forget-missing'),
      `${job.file}:${job.name}`,
    );
});
