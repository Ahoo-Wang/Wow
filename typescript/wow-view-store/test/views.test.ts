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

import { describe, expect, it } from 'vitest';
import type { SystemView } from '../src/generated/view-store/types.js';
import {
  preferencesInput,
  systemSummary,
  toPreferences,
  viewInstance,
} from '../src/views.js';
import { wordingOf } from '../src/wire.js';

const configured: SystemView = {
  id: 'open',
  definitionId: 'orders',
  title: 'Open',
  kind: 'record',
  scope: 'system',
  revision: 'r1',
  config: { kind: 'record' },
  source: 'configured',
  version: null,
};

describe('the shapes the store reads', () => {
  it('marks a stored system view, and only a stored one', () => {
    const stored = { ...configured, source: 'stored' as const, version: 3 };
    expect(viewInstance(stored)).toMatchObject({ stored: true });
    expect(systemSummary(stored)).toMatchObject({ stored: true });
    expect(viewInstance(configured)).not.toHaveProperty('stored');
    expect(systemSummary(configured)).not.toHaveProperty('stored');
    // A stored source without a version is not one a write may expect.
    expect(
      viewInstance({ ...configured, source: 'stored', version: null }),
    ).not.toHaveProperty('stored');
  });

  it('reads a saved view by id at its revision, unmarked', () => {
    const personal = viewInstance({
      ...configured,
      scope: 'personal',
      revision: '2',
      version: 2,
      source: null,
    });
    expect(personal).toEqual({
      id: 'open',
      definitionId: 'orders',
      title: 'Open',
      scope: 'personal',
      revision: '2',
      config: { kind: 'record' },
    });
  });

  it('reads preferences never set as the port leaves them', () => {
    expect(
      toPreferences({ definitionId: 'orders', order: [], version: 0 }),
    ).toEqual({
      order: [],
      defaultInstanceId: null,
      revision: '0',
    });
    expect(
      toPreferences({
        definitionId: 'orders',
        order: ['a'],
        defaultInstanceId: 'a',
        autoRun: true,
        lastTabs: { a: 'table' },
        version: 2,
      }),
    ).toEqual({
      order: ['a'],
      defaultInstanceId: 'a',
      autoRun: true,
      lastTabs: { a: 'table' },
      revision: '2',
    });
  });

  it('writes preferences without the revision, copying what it holds', () => {
    const lastTabs = { a: 'table' };
    const input = preferencesInput({
      order: ['a'],
      defaultInstanceId: undefined as unknown as null,
      autoRun: false,
      lastTabs,
    });
    expect(input).toEqual({
      order: ['a'],
      defaultInstanceId: null,
      autoRun: false,
      lastTabs,
    });
    expect(input.lastTabs).not.toBe(lastTabs);
  });

  it('reads a stored wording as what it stores, nothing more', () => {
    expect(wordingOf({ title: null, recordNoun: null, fields: null })).toEqual(
      {},
    );
    expect(
      wordingOf({ title: 'Orders', recordNoun: 'order', fields: {} }),
    ).toEqual({ title: 'Orders', recordNoun: 'order', fields: {} });
  });
});
