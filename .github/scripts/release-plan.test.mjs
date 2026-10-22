/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { ROOT } from './project-version.mjs';
import { NOTES, outputLines, releasePlan } from './release-plan.mjs';

const TAGS = [
  'v9.8.0',
  'v9.9.0',
  'v10.0.0-alpha.3',
  'v10.0.0-alpha.4',
  'vv5.22.3',
];

test('a pre-release keeps its dist-tag, its version-only image and stays off latest', () => {
  for (const [tag, npmDistTag] of [
    ['v10.0.0-alpha.4', 'alpha'],
    ['v10.0.0-beta.1', 'beta'],
    ['v10.0.0-rc.1', 'next'],
  ]) {
    const plan = releasePlan(`refs/tags/${tag}`, [...TAGS, tag]);
    assert.equal(plan.prerelease, true, tag);
    assert.equal(plan.latest, false, tag);
    assert.equal(plan.npmDistTag, npmDistTag, tag);
    assert.deepEqual(plan.imageTags, [tag.slice(1)], tag);
    assert.equal(plan.notes, NOTES.prerelease, tag);
  }
});

test('a stable release on the latest line takes latest everywhere', () => {
  const plan = releasePlan('refs/tags/v10.0.0', [...TAGS, 'v10.0.0']);
  assert.deepEqual(plan, {
    tag: 'v10.0.0',
    version: '10.0.0',
    major: 10,
    line: '10.0',
    prerelease: false,
    latest: true,
    npmDistTag: 'latest',
    imageTags: ['10.0.0', '10.0', 'latest'],
    notes: NOTES.stable,
  });
  // A dispatch before the tag is fetched decides the same.
  assert.equal(releasePlan('refs/tags/v10.0.1', TAGS).latest, true);
  assert.deepEqual(
    outputLines(plan).filter(line => /^(latest|image-tags)=/.test(line)),
    ['latest=true', 'image-tags=10.0.0,10.0,latest'],
  );
});

test('a patch to an older line moves neither latest nor the npm latest dist-tag', () => {
  const tags = [...TAGS, 'v10.0.0', 'v10.1.0', 'v10.0.1'];
  const plan = releasePlan('refs/tags/v10.0.1', tags);
  assert.equal(plan.prerelease, false);
  assert.equal(plan.latest, false);
  assert.equal(plan.npmDistTag, 'release-10.0');
  assert.deepEqual(plan.imageTags, ['10.0.1', '10.0']);
  assert.equal(plan.notes, NOTES.stable);
  // A pre-release of a later line does not make a stable patch an older line.
  assert.equal(
    releasePlan('refs/tags/v10.1.1', [...tags, 'v10.2.0-rc.1']).latest,
    true,
  );
});

test('an older major and anything but a v<version> tag are refused', () => {
  assert.throws(
    () => releasePlan('refs/tags/v9.9.1', [...TAGS, 'v9.9.1']),
    /older major line than v10; 9\.x releases from the v9 branch/,
  );
  assert.throws(
    () => releasePlan('refs/tags/v9.10.0-rc.1', TAGS),
    /9\.x releases from the v9 branch/,
  );
  for (const ref of ['refs/heads/main', 'refs/tags/10.0.0', undefined])
    assert.throws(() => releasePlan(ref, TAGS), /Releases run from a v\* tag/);
  for (const ref of ['refs/tags/v10', 'refs/tags/v10.0', 'refs/tags/vv5.22.3'])
    assert.throws(() => releasePlan(ref, TAGS), /is not a release tag/);
});

test('both release workflows take their decisions from the plan', () => {
  const workflow = name =>
    readFileSync(join(ROOT, '.github/workflows', name), 'utf8');
  for (const name of ['package-deploy.yml', 'image-deploy.yml']) {
    const text = workflow(name);
    assert.match(text, /node \.github\/scripts\/release-plan\.mjs/, name);
    // No version special case in the admission: the plan decides.
    assert.doesNotMatch(text, /refs\/tags\/v\d/, name);
  }
  for (const notes of Object.values(NOTES))
    assert.ok(existsSync(join(ROOT, notes)), notes);
});
