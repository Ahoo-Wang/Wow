/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { LABEL, isBreakingTitle } from './breaking-label.mjs';
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
  // pull_request_target: the title reaches the script only through env.
  assert.doesNotMatch(workflow, /run:[^\n]*\$\{\{\s*github\.event/);
  for (const [, action] of workflow.matchAll(/uses: (\S+)/g))
    assert.match(action, /@[0-9a-f]{40}$/, `${action} is not pinned to a SHA`);
});
