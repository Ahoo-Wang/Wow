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

/**
 * 「点击时…」 as a draft (D22 I, D23 Q17): what the form opens on, the
 * click it makes or why it makes none yet, and what a board's mapping keeps.
 */

import { describe, expect, it } from 'vitest';
import {
  clickDraftGaps,
  clickDraftOf,
  draftedClick,
  mappedValues,
  urlFillable,
  withBoard,
  type ClickDraft,
  type MappingSources,
} from '../src/dashboard/index.js';
import type { DashboardField } from '../src/index.js';
import { dashboardConfig } from './fixtures.js';

const area: DashboardField = { name: 'area', label: 'Area', kind: 'string' };
const region: DashboardField = {
  name: 'region',
  label: 'Region',
  kind: 'string',
};
const sources: MappingSources = {
  groups: [{ field: 'warehouse', type: 'TERMS' }],
  fields: [{ name: 'warehouse', label: 'Warehouse', kind: 'string' }],
  own: [region],
};
const regional = dashboardConfig({ fields: [area] });

describe('clickDraftOf', () => {
  it('opens on the stored click, every other choice blank', () => {
    expect(clickDraftOf(null, ['region'])).toMatchObject({
      choice: 'menu',
      filter: 'region',
      goKind: 'view',
    });
    expect(
      clickDraftOf({ kind: 'filter', filter: 'region' }, ['area', 'region']),
    ).toMatchObject({ choice: 'filter', filter: 'region' });
    expect(
      clickDraftOf({ kind: 'view', instanceId: 'orders' }, []),
    ).toMatchObject({
      choice: 'go',
      goKind: 'view',
      view: 'orders',
      filter: '',
    });
    expect(clickDraftOf({ kind: 'url', url: '/a/{{x}}' }, [])).toMatchObject({
      choice: 'go',
      goKind: 'url',
      url: '/a/{{x}}',
    });
    expect(
      clickDraftOf(
        {
          kind: 'dashboard',
          instanceId: 'regional',
          values: { area: { dimension: 'warehouse' } },
        },
        [],
      ),
    ).toMatchObject({
      goKind: 'dashboard',
      board: 'regional',
      values: { area: { dimension: 'warehouse' } },
    });
  });

  it('starts a stored filter no press can set any more at the first that can', () => {
    expect(
      clickDraftOf({ kind: 'filter', filter: 'gone' }, ['area']),
    ).toMatchObject({ choice: 'filter', filter: 'area' });
  });
});

describe('draftedClick', () => {
  const draft = (patch: Partial<ClickDraft>): ClickDraft => ({
    ...clickDraftOf(null, []),
    ...patch,
  });

  it('makes the click each choice says, or none while it lacks something', () => {
    expect(draftedClick(draft({}), undefined, sources)).toBeNull();
    expect(
      draftedClick(
        draft({ choice: 'filter', filter: 'region' }),
        undefined,
        sources,
      ),
    ).toEqual({ kind: 'filter', filter: 'region' });
    expect(
      draftedClick(draft({ choice: 'filter' }), undefined, sources),
    ).toBeUndefined();
    expect(
      draftedClick(draft({ choice: 'go', view: 'orders' }), undefined, sources),
    ).toEqual({ kind: 'view', instanceId: 'orders' });
    expect(
      draftedClick(draft({ choice: 'go' }), undefined, sources),
    ).toBeUndefined();
    expect(
      draftedClick(
        draft({ choice: 'go', goKind: 'url', url: ' /orders/{{warehouse}} ' }),
        undefined,
        sources,
      ),
    ).toEqual({ kind: 'url', url: '/orders/{{warehouse}}' });
    expect(
      draftedClick(
        draft({ choice: 'go', goKind: 'url', url: 'javascript:alert(1)' }),
        undefined,
        sources,
      ),
    ).toBeUndefined();
  });

  it('opens a board only once it is read, keeping the mapping that still holds', () => {
    const going = draft({
      choice: 'go',
      goKind: 'dashboard',
      board: 'regional',
      values: {
        area: { dimension: 'warehouse' },
        gone: { filter: 'region' },
      },
    });
    expect(draftedClick(going, undefined, sources)).toBeUndefined();
    expect(draftedClick(going, regional, sources)).toEqual({
      kind: 'dashboard',
      instanceId: 'regional',
      values: { area: { dimension: 'warehouse' } },
    });
  });

  it('names the tab a board opens on only while the board has it (D39)', () => {
    const tabbed = dashboardConfig({
      fields: [area],
      tabs: [
        { id: 'summary', title: 'Summary' },
        { id: 'detail', title: 'Detail' },
      ],
    });
    const going = draft({
      choice: 'go',
      goKind: 'dashboard',
      board: 'regional',
      tab: 'detail',
    });
    expect(draftedClick(going, tabbed, sources)).toMatchObject({
      tab: 'detail',
    });
    expect(draftedClick(going, regional, sources)).not.toHaveProperty('tab');
    expect(
      draftedClick({ ...going, tab: '' }, tabbed, sources),
    ).not.toHaveProperty('tab');
    // Read back as the form opens on it, and let go with another board.
    expect(
      clickDraftOf(
        {
          kind: 'dashboard',
          instanceId: 'regional',
          values: {},
          tab: 'detail',
        },
        [],
      ).tab,
    ).toBe('detail');
    expect(withBoard(going, 'another').tab).toBe('');
    expect(withBoard(going, 'regional').tab).toBe('detail');
  });
});

describe('the parts of a board destination', () => {
  it('names what no longer holds by the filter it was for', () => {
    expect(
      mappedValues(
        regional,
        {
          area: { filter: 'region' },
          gone: { dimension: 'warehouse' },
        },
        { ...sources, own: [] },
      ),
    ).toEqual({ kept: {}, stale: ['Area', 'gone'] });
  });

  it('drops the mapping when another board is picked, not when the same one is', () => {
    const before: ClickDraft = {
      ...clickDraftOf(null, []),
      board: 'regional',
      values: { area: { dimension: 'warehouse' } },
    };
    expect(withBoard(before, 'regional')).toBe(before);
    expect(withBoard(before, 'other')).toMatchObject({
      board: 'other',
      values: {},
    });
  });

  it('says what a destination lacks only for the kind picked', () => {
    const going = { ...clickDraftOf(null, []), choice: 'go' as const };
    expect(clickDraftGaps(going)).toEqual({
      view: true,
      url: false,
      board: false,
    });
    expect(clickDraftGaps({ ...going, goKind: 'url' })).toEqual({
      view: false,
      url: true,
      board: false,
    });
    expect(clickDraftGaps({ ...going, goKind: 'dashboard' })).toEqual({
      view: false,
      url: false,
      board: true,
    });
    expect(clickDraftGaps(clickDraftOf(null, []))).toEqual({
      view: false,
      url: false,
      board: false,
    });
    expect(urlFillable('https://example.com/{{warehouse}}')).toBe(true);
  });
});
