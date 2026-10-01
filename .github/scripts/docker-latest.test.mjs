/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { movesLatest, remoteTags } from './docker-latest.mjs';

const tags = ['v9.0.19', 'v9.1.6', 'v9.2.0', 'v9.3.0-rc.0', 'v8.11.5'];

test('only a release tag of the highest stable line moves latest', () => {
  assert.ok(movesLatest('refs/tags/v9.2.0', tags));
  assert.ok(movesLatest('refs/tags/v9.2.1', [...tags, 'v9.2.1']));
  assert.ok(movesLatest('refs/tags/v9.3.0', [...tags, 'v9.3.0']));
  // A patch to an older line keeps latest where it is.
  assert.ok(!movesLatest('refs/tags/v9.1.7', [...tags, 'v9.1.7']));
  assert.ok(!movesLatest('refs/tags/v8.11.6', [...tags, 'v8.11.6']));
  // A pre-release outranks nothing, and is never latest itself.
  assert.ok(!movesLatest('refs/tags/v9.3.0-rc.0', tags));
  // Branches, the daily run and a dispatch on a branch.
  assert.ok(!movesLatest('refs/heads/main', tags));
  assert.ok(!movesLatest(undefined, tags));
  // The first tag of a repository.
  assert.ok(movesLatest('refs/tags/v1.0.0', []));
});

test('remote tags are read from ls-remote output', () => {
  assert.deepEqual(
    remoteTags(
      'aaa\trefs/tags/v9.1.6\nbbb\trefs/tags/v9.2.0\nccc\trefs/tags/other\n',
    ),
    ['v9.1.6', 'v9.2.0'],
  );
  assert.deepEqual(remoteTags(''), []);
});
