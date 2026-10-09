/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { TYPE_LABELS, labelForTitle } from './title-label.mjs';
import { ROOT } from './project-version.mjs';

test('the title type picks the release-notes label', () => {
  for (const [title, label] of [
    ['feat(view-engine): embeds keep their order', 'enhancement'],
    ['fix(bi): keep verified Kafka ingress attached across DEPLOY', 'bug'],
    [
      'perf(core): copy-on-write message headers (P1 row 2, round 3)',
      'performance',
    ],
    ['docs(design): wow-bi refactor design', 'documentation'],
    [
      'refactor(bi)!: one reconciler decides every object action (R4)',
      'maintenance',
    ],
    [
      'test(bi): golden DEPLOY/RESET scenarios and package DAG guard (R0)',
      'maintenance',
    ],
    ['chore(release): prepare 9.3.0', 'maintenance'],
    ['ci: run the labeler after the path labeler', 'maintenance'],
    ['build: pin the Kotlin plugin', 'maintenance'],
    ['style(bi): indent every store DDL clause alike', 'maintenance'],
    ['revert: "feat(core): spin"', 'maintenance'],
  ])
    assert.equal(labelForTitle(title), label, title);
});

test('a deps scope is a dependency update whatever the type', () => {
  assert.equal(
    labelForTitle('fix(deps): update kotlin to v2.4.21'),
    'dependencies',
  );
  assert.equal(
    labelForTitle('chore(ci, deps): bump actions/checkout'),
    'dependencies',
  );
});

test('a title that is not a Conventional Commit gets no label', () => {
  for (const title of [
    'fix(deps) Update shadcn to ^4.21.4',
    'Update README',
    'feature: not a Conventional Commit type',
    'fix:',
    'Fix(bi): capitalised types are not types',
    '',
    undefined,
  ])
    assert.equal(labelForTitle(title), null, String(title));
});

test('every type label is a category of the generated release notes', () => {
  const releaseNotes = readFileSync(join(ROOT, '.github/release.yml'), 'utf8');
  for (const label of new Set([...Object.values(TYPE_LABELS), 'dependencies']))
    assert.match(releaseNotes, new RegExp(`^\\s+- ${label}$`, 'm'), label);
});
