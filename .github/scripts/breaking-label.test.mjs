/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  LABEL,
  breakingSurfaceFiles,
  isBreakingBody,
  isBreakingTitle,
  isSurfaceFile,
  removedSurfaceLines,
} from './breaking-label.mjs';
import { ROOT } from './project-version.mjs';

test('a `!` before the colon marks a breaking title', () => {
  for (const title of [
    'feat(view-engine)!: embeds never write',
    'refactor(wow-react)!: own the public hook types (refactor B1) (#3361)',
    'fix!: drop the legacy entry',
    'Feat(Wow-Client)!: upper-case type',
  ])
    assert.equal(isBreakingTitle(title), true, title);
});

test('other titles are not breaking', () => {
  for (const title of [
    'feat(view-engine): brush a stretch of a time axis',
    'ci(release): npm smoke test after publish',
    'fix: say BREAKING CHANGE in the subject is not a footer',
    'feat(scope): wow! a bang after the colon',
    'Revert "feat(x)!: y"',
    'chore(deps)!something: no colon after the bang',
    '',
  ])
    assert.equal(isBreakingTitle(title), false, title);
});

test('the label exists in the release-notes categories and the labeler workflow runs the script', () => {
  const release = readFileSync(join(ROOT, '.github/release.yml'), 'utf8');
  assert.match(release, new RegExp(`- ${LABEL}\\n`));
  const workflow = readFileSync(
    join(ROOT, '.github/workflows/pr-labeler.yml'),
    'utf8',
  );
  assert.match(workflow, /^ {6}- edited$/m, 'a retitled PR is relabelled');
  assert.match(workflow, /node \.github\/scripts\/breaking-label\.mjs/);
  assert.match(
    workflow,
    /PR_BODY: \$\{\{ github\.event\.pull_request\.body \}\}/,
  );
  // pull_request_target: the title reaches the script only through env.
  assert.doesNotMatch(workflow, /run:[^\n]*\$\{\{\s*github\.event/);
  for (const [, action] of workflow.matchAll(/uses: (\S+)/g))
    assert.match(action, /@[0-9a-f]{40}$/, `${action} is not pinned to a SHA`);
});

test('a description that declares a break is breaking', () => {
  for (const body of [
    'Goal\n\nBREAKING CHANGE: the cursor token format changed',
    '## Breaking\n\n- `QueryCapability` is an enum',
    '### Breaking (SPI)\n\nx',
    '## Breaking changes\n\n- the cursor token format changed',
    '## Breaking:\n\nx',
    '## Breaking\n\n### SPI\n\n- `QueryBackend` has four primitives',
    '## Breaking changes\n\nNone of the old tokens are accepted.',
    'Intro\r\n\r\n## Breaking\r\n\r\n- x\r\n',
    '- [x] **Breaking**: it does not, and the title has `!`.',
  ])
    assert.equal(isBreakingBody(body), true, body);
});

test('other descriptions are not breaking', () => {
  for (const body of [
    '',
    undefined,
    '- [ ] **Breaking**: it does not, and the title has `!`.',
    '## For B7 (release notes, Breaking section)',
    'Not breaking: no REST path changes.',
    '```\nBREAKING CHANGE: inside a fence\n## Breaking\n```',
    '#### Breaking at level four is a detail, not a section',
    // A breaking section that says there is nothing.
    '## Breaking changes\n\nNone.',
    '## Breaking\n\nNo\n\n## Verification\n\n- x',
    '## Breaking\n\nN/A',
    '### Breaking changes\n\n_None._\n',
    '## Breaking changes\n\nNo breaking changes.',
    '## Breaking\n\n<!-- who is affected, how to migrate -->\n\n## Changes\n\nx',
    '## Breaking\n',
    // A heading that only starts with the word.
    '### Breaking down the work\n\n1. the gate\n2. the labeler',
    '## Breaking-change policy\n\nx',
    // Behaviour changes are the release-notes section for what is not breaking.
    '## Behaviour changes\n\nA missing index answers an empty page instead of 503.',
    '## Behavior changes\n\nx',
  ])
    assert.equal(isBreakingBody(body), false, `${body}`);
});

test('the pull request template asks about breaking changes the way the labeler reads them', () => {
  const template = readFileSync(
    join(ROOT, '.github/PULL_REQUEST_TEMPLATE'),
    'utf8',
  );
  const box = template.split('\n').find(line => line.includes('**Breaking**'));
  assert.ok(box, 'the template has a breaking box');
  assert.equal(isBreakingBody(box), false, 'unticked, it does not label');
  assert.equal(isBreakingBody(box.replace('[ ]', '[x]')), true);
});

const KOTLIN = 'compensation/wow-compensation-api/api/wow-compensation-api.api';
const REPORT = 'typescript/wow-client/test/api/root.api.md';
const SURFACE = 'typescript/wow-client/test/surface/root.txt';

/** A "list pull request files" entry for a modified file. */
const modified = (filename, patch) => ({
  filename,
  status: 'modified',
  deletions: patch.split('\n').filter(line => line.startsWith('-')).length,
  patch,
});

test('every committed ABI dump, API report and surface list is a surface record', () => {
  const tracked = execFileSync(
    'git',
    [
      'ls-files',
      '*/api/*.api',
      'typescript/*/test/api/*.api.md',
      'typescript/*/test/surface/*.txt',
    ],
    { cwd: ROOT, encoding: 'utf8' },
  )
    .split('\n')
    .filter(Boolean);
  assert.ok(tracked.some(path => path.endsWith('.api')));
  assert.ok(tracked.some(path => path.endsWith('.api.md')));
  assert.ok(tracked.some(path => path.endsWith('.txt')));
  for (const path of tracked) assert.equal(isSurfaceFile(path), true, path);
  for (const path of [
    'wow-api/src/main/kotlin/me/ahoo/wow/api/Identifier.kt',
    'typescript/wow-client/src/api.md',
    'typescript/wow-client/test/surface.test.ts',
    'typescript/wow-client/test/api/root.api.ts',
    'documentation/docs/en/api/index.md',
  ])
    assert.equal(isSurfaceFile(path), false, path);
});

test('additions only to each surface record add no label', () => {
  const files = [
    modified(
      KOTLIN,
      '@@ -3,3 +3,4 @@ public final class me/ahoo/wow/compensation/CompensationService {\n' +
        '\tpublic static final field INSTANCE Lme/ahoo/wow/compensation/CompensationService;\n' +
        '+\tpublic static final field NEW Ljava/lang/String;\n' +
        '\tpublic static final field SERVICE_ALIAS Ljava/lang/String;',
    ),
    modified(
      REPORT,
      '@@ -10,6 +10,10 @@ export interface A {\n }\n \n+// @public\n+export function added(): void;\n+\n // @public\n export interface B {',
    ),
    // The header's name count changes with every addition.
    modified(
      SURFACE,
      '@@ -1,4 +1,5 @@\n-# @ahoo-wang/wow-client — 151 names, from src/index.ts.\n+# @ahoo-wang/wow-client — 152 names, from src/index.ts.\n # Written by test/publicSurface.test.ts; a change here is a change to\n value A\n+value Added\n value B',
    ),
    // A file added whole.
    {
      filename: 'view-store/wow-view-store-new/api/wow-view-store-new.api',
      status: 'added',
      deletions: 0,
      patch: '@@ -0,0 +1,2 @@\n+public final class X {\n+}',
    },
  ];
  assert.deepEqual(breakingSurfaceFiles(files), []);
});

test('one removed line in each kind of surface record adds the label', () => {
  const cases = [
    modified(
      KOTLIN,
      '@@ -3,4 +3,3 @@\n \tpublic static final field INSTANCE Lx;\n-\tpublic static final field SERVICE_ALIAS Ljava/lang/String;\n \tpublic static final field SERVICE_NAME Ljava/lang/String;',
    ),
    modified(
      REPORT,
      '@@ -10,6 +10,4 @@\n }\n \n-// @public\n-export function removed(): void;\n \n // @public',
    ),
    modified(SURFACE, '@@ -5,3 +5,2 @@\n value A\n-value Removed\n value B'),
  ];
  for (const file of cases) {
    const [result] = breakingSurfaceFiles([file]);
    assert.ok(result, file.filename);
    assert.equal(result.file, file.filename);
    assert.ok(result.lines.length >= 1, file.filename);
  }
});

test('a changed signature is a -/+ pair, and its removed half counts', () => {
  const report = modified(
    REPORT,
    '@@ -20,3 +20,3 @@\n // @public\n-export function query(id: string): Promise<View>;\n+export function query(id: string, options?: QueryOptions): Promise<View>;\n ',
  );
  assert.deepEqual(removedSurfaceLines(report), [
    'export function query(id: string): Promise<View>;',
  ]);
  // A default-valued parameter still changes the JVM signature.
  const kotlin = modified(
    KOTLIN,
    '@@ -9,1 +9,1 @@\n-\tpublic fun <init> (Ljava/lang/String;)V\n+\tpublic fun <init> (Ljava/lang/String;I)V',
  );
  assert.equal(removedSurfaceLines(kotlin).length, 1);
});

test('re-indented or moved lines and blank lines do not count', () => {
  const file = modified(
    REPORT,
    '@@ -1,8 +1,8 @@\n' +
      '-    field(name: string): Field;\n' +
      '+  field(name: string):   Field;\n' +
      '-export interface A {}\n' +
      '-\n' +
      ' export interface B {}\n' +
      '+export interface A {}',
  );
  assert.deepEqual(removedSurfaceLines(file), []);
  // TSDoc added to a declaration: only the report's comment changes.
  const documented = modified(
    REPORT,
    '@@ -1,2 +1,2 @@\n-// @public (undocumented)\n+// @public @deprecated\n export const a: number;',
  );
  assert.deepEqual(removedSurfaceLines(documented), []);
  // A line that appears twice and comes back once still lost one copy.
  const twice = modified(
    SURFACE,
    '@@ -1,2 +1,1 @@\n-value A\n-value A\n+value A',
  );
  assert.deepEqual(removedSurfaceLines(twice), ['value A']);
});

test('renamed, moved and deleted surface records are judged by their lines', () => {
  // Same content under a new path (a module moved): nothing removed.
  assert.deepEqual(
    removedSurfaceLines({
      filename: 'view-store/wow-view-store-api/api/wow-view-store-api.api',
      previous_filename: 'wow-view-store-api/api/wow-view-store-api.api',
      status: 'renamed',
      deletions: 0,
    }),
    [],
  );
  // Renamed with a removal: the removal counts.
  assert.deepEqual(
    removedSurfaceLines({
      filename: 'typescript/wow-client/test/surface/index.txt',
      previous_filename: SURFACE,
      status: 'renamed',
      deletions: 1,
      patch: '@@ -5,3 +5,2 @@\n value A\n-value Removed\n value B',
    }),
    ['value Removed'],
  );
  // Moved out of the surface paths: still judged, since the old path was one.
  assert.equal(
    removedSurfaceLines({
      filename: 'typescript/wow-client/docs/old-root.api.md',
      previous_filename: REPORT,
      status: 'renamed',
      deletions: 1,
      patch: '@@ -1,2 +1,1 @@\n-export const gone: number;\n // @public',
    }).length,
    1,
  );
  // A deleted record removes every declaration.
  assert.deepEqual(
    removedSurfaceLines({
      filename: REPORT,
      status: 'removed',
      deletions: 2,
      patch: '@@ -1,2 +0,0 @@\n-// @public\n-export const gone: number;',
    }),
    ['export const gone: number;'],
  );
  // No patch (too large for GitHub to show): any deletion counts.
  assert.equal(
    removedSurfaceLines({ filename: KOTLIN, status: 'modified', deletions: 4 })
      .length,
    1,
  );
  assert.deepEqual(
    removedSurfaceLines({ filename: KOTLIN, status: 'modified', deletions: 0 }),
    [],
  );
});

test('removed lines in other files add no label', () => {
  assert.deepEqual(
    breakingSurfaceFiles([
      modified(
        'typescript/wow-client/src/index.ts',
        '@@ -1 +0,0 @@\n-export const x = 1;',
      ),
      modified('wow-core/src/main/kotlin/A.kt', '@@ -1 +0,0 @@\n-fun a() {}'),
    ]),
    [],
  );
});

test('the labeler workflow can read the pull request diff', () => {
  const workflow = readFileSync(
    join(ROOT, '.github/workflows/pr-labeler.yml'),
    'utf8',
  );
  // The file list comes from the REST API with the job token; the PR's
  // code is never checked out (pull_request_target).
  assert.match(workflow, /^ {6}- synchronize$/m, 'a new push is relabelled');
  assert.match(workflow, /GH_TOKEN: \$\{\{ github\.token \}\}/);
  assert.doesNotMatch(
    workflow,
    /ref: \$\{\{ github\.event\.pull_request\.head/,
  );
});
