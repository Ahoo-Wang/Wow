/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { LABEL, isBreakingBody, isBreakingTitle } from './breaking-label.mjs';
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
