/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { ROOT } from './project-version.mjs';

// The `allow-large-removal` label lifts PR Safety's 10% removal rule on a
// pull request a reviewer has confirmed is a deliberate cleanup. Only a
// maintainer applies it, by hand; nothing in the repository may apply it.

const LABEL = 'allow-large-removal';

const read = file => readFileSync(join(ROOT, file), 'utf8');

test('pr-safety.sh passes its own self-test', () => {
  // PR Safety runs the base's script, so a pull request that edits the script
  // is only exercised by this test.
  const output = execFileSync(
    'bash',
    [join(ROOT, '.github/scripts/pr-safety.sh'), '--self-test'],
    { encoding: 'utf8' },
  );
  assert.match(output, /PR safety self-test passed\./);
});

test('PR Safety re-runs when a label is added or removed', () => {
  const workflow = read('.github/workflows/pr-safety.yml');
  const types = /^ {4}types: \[(.*)\]$/m.exec(workflow);
  assert.ok(types, 'pull_request_target types');
  for (const type of ['labeled', 'unlabeled'])
    assert.ok(types[1].split(/,\s*/).includes(type), type);
});

test(`no labeler, workflow or script applies ${LABEL}`, () => {
  const scripts = readdirSync(join(ROOT, '.github/scripts'))
    .filter(name => name.endsWith('.mjs') && !name.endsWith('.test.mjs'))
    .map(name => `.github/scripts/${name}`);
  // pr-safety.yml only reads (`pull-requests: read`); it cannot label.
  const workflows = readdirSync(join(ROOT, '.github/workflows'))
    .filter(name => /\.ya?ml$/.test(name) && name !== 'pr-safety.yml')
    .map(name => `.github/workflows/${name}`);
  for (const file of ['.github/labeler.yml', ...workflows, ...scripts])
    assert.ok(
      !read(file).includes(LABEL),
      `${file} names ${LABEL}: only a maintainer applies it, by hand`,
    );
});
