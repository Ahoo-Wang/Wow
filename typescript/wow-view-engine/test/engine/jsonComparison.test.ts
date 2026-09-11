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

import { sameJsonState } from '../../src/lib/snapshot.js';
import { expect, it } from 'vitest';
import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { instance, selected, setup } from './fixtures.js';

const composed = '\u00e9';
const decomposed = 'e\u0301';
const labels = { [composed]: 'first', [decomposed]: 'second' };
const reordered = { [decomposed]: 'second', [composed]: 'first' };

it('compares opaque JSON keys independently of insertion order and linguistic collation', () => {
  expect(sameJsonState({ labels }, { labels: reordered })).toBe(true);
  expect(
    sameJsonState(
      { labels },
      { labels: { [composed]: 'second', [decomposed]: 'first' } },
    ),
  ).toBe(false);
  expect(
    sameJsonState({ list: [1, 2], optional: undefined }, { list: [1, 2] }),
  ).toBe(true);
  expect(sameJsonState({ list: [1, 2] }, { list: [2, 1] })).toBe(false);
});

it('accepts a saved response that only reorders opaque component property keys', async () => {
  const saved = instance();
  saved.config.filters.root = {
    id: 'opaque',
    operator: FilterOperator.EQ,
    field: 'state.amount',
    component: { name: 'opaque' },
    props: { value: 1, labels },
  };
  const { engine } = setup({
    instances: { instances: [saved], defaultInstanceId: saved.id },
    filterCompilers: {
      opaque: {
        compile: props => filter.eq('state.amount', props.value as number),
      },
    },
    host: {
      resolveSource: () => ({ paged: async () => ({ total: 0, list: [] }) }),
      instance: {
        save: async value => ({
          ...value,
          revision: 'r2',
          config: {
            ...value.config,
            filters: {
              ...value.config.filters,
              root: {
                ...value.config.filters.root,
                props: { value: 1, labels: reordered },
              },
            },
          },
        }),
      },
    },
  });
  try {
    await engine.load();
    await expect(engine.save()).resolves.toBeUndefined();
    expect(selected(engine)).toMatchObject({
      requiresReload: false,
      writeError: null,
      baseline: { revision: 'r2' },
    });
  } finally {
    engine.dispose();
  }
});

it('compares sparse JSON arrays by their serialized null slots and ignores inherited keys', () => {
  expect(sameJsonState(new Array(1), [null])).toBe(true);
  expect(sameJsonState(new Array(1), [1])).toBe(false);
  expect(sameJsonState([1], new Array(1))).toBe(false);
  expect(
    sameJsonState({ optional: undefined, value: null }, { value: null }),
  ).toBe(true);
  expect(sameJsonState(Object.create({ inherited: 1 }), { inherited: 1 })).toBe(
    false,
  );
  expect(sameJsonState([1], { 0: 1 })).toBe(false);
});
