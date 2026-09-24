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
 * A panel's click as the dashboard kernel reads it (D22 H, I): the shape
 * read as untrusted, the URL a page destination is filled into, the filters
 * a press can set, what admission says, and the edits that set it or take
 * it with an unwired filter.
 */

import { describe, expect, it } from 'vitest';
import {
  admitFilters,
  clickOf,
  crossFilterChoices,
  fillUrl,
  pressableGroups,
  removeFilter,
  retypeFilter,
  setPanelClick,
  takesGroup,
  unbindPanels,
  urlPlaceholders,
  validatePanelClick,
} from '../src/dashboard/index.js';
import {
  builtinFieldKinds,
  type DashboardField,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type PanelClick,
} from '../src/index.js';
import { analysisConfig, dashboardConfig, recordConfig } from './fixtures.js';

const region: DashboardField = {
  name: 'region',
  label: 'Region',
  kind: 'string',
};
const created: DashboardField = {
  name: 'created',
  label: 'Created',
  kind: 'datetime',
};

function panel(click?: unknown, id = 'chart'): DashboardViewPanel {
  return {
    id,
    kind: 'view',
    instanceId: 'by-warehouse',
    bindings: [
      { globalField: 'region', panelField: 'warehouse' },
      { globalField: 'created', panelField: 'createdAt' },
    ],
    layout: { x: 0, y: 0, w: 12, h: 4 },
    ...(click === undefined ? {} : { click }),
  } as DashboardViewPanel;
}

function board(...panels: DashboardViewPanel[]): DashboardViewConfig {
  return dashboardConfig({ fields: [region, created], panels });
}

const byWarehouse = { config: analysisConfig() };

describe('a panel’s click, read (D22 I)', () => {
  it('reads the three kinds and nothing else', () => {
    expect(clickOf(panel({ kind: 'filter', filter: 'region' }))).toEqual({
      kind: 'filter',
      filter: 'region',
    });
    expect(clickOf(panel({ kind: 'view', instanceId: 'list' }))).toEqual({
      kind: 'view',
      instanceId: 'list',
    });
    expect(clickOf(panel({ kind: 'url', url: '/o/{{warehouse}}' }))).toEqual({
      kind: 'url',
      url: '/o/{{warehouse}}',
    });
    for (const odd of [
      undefined,
      'filter',
      { kind: 'filter' },
      { kind: 'view', instanceId: '' },
      { kind: 'url', url: 3 },
      { kind: 'dashboard', instanceId: 'x' },
    ])
      expect(clickOf(panel(odd))).toBeNull();
    expect(clickOf({ kind: 'heading', content: 'x' })).toBeNull();
  });
});

describe('a page destination’s URL', () => {
  it('names each placeholder once, spaces inside the braces allowed', () => {
    expect(
      urlPlaceholders('/a/{{ warehouse }}?s={{status}}&w={{warehouse}}'),
    ).toEqual(['warehouse', 'status']);
  });

  it('fills each placeholder as one encoded component, an unknown one empty', () => {
    expect(
      fillUrl('/orders?w={{warehouse}}&s={{status}}', {
        warehouse: 'CN South/2 & more',
      }),
    ).toBe('/orders?w=CN%20South%2F2%20%26%20more&s=');
  });

  it('opens nothing a board may not: a scheme is refused, even one a value writes', () => {
    expect(fillUrl('javascript:alert({{x}})', { x: '1' })).toBeNull();
    // A value is one component: it can never add a scheme.
    expect(fillUrl('{{x}}', { x: 'javascript:alert(1)' })).toBe(
      'javascript%3Aalert(1)',
    );
    expect(fillUrl('https://example.com/{{x}}', { x: 'a' })).toBe(
      'https://example.com/a',
    );
  });
});

describe('the filters a press can set', () => {
  it('takes a date bucket for a date filter and one value for the rest', () => {
    expect(takesGroup('date', 'DATE_HISTOGRAM')).toBe(true);
    expect(takesGroup('date', 'TERMS')).toBe(false);
    expect(takesGroup('text', 'TERMS')).toBe(true);
    expect(takesGroup('number', 'HISTOGRAM')).toBe(false);
    expect(takesGroup(null, 'TERMS')).toBe(false);
  });

  it('lists a filter wired through a field the panel groups by, in a way it takes', () => {
    const groups = pressableGroups(byWarehouse.config).groups;
    expect(
      crossFilterChoices([region, created], panel(), groups).map(
        entry => `${entry.filter.name}:${entry.field}`,
      ),
    ).toEqual(['region:warehouse']);
  });

  it('reads no group to press on a record view or over expanded elements', () => {
    expect(pressableGroups(recordConfig()).pressable).toBe(false);
    expect(
      pressableGroups(
        analysisConfig({ elements: [{ field: 'lines' }] } as never),
      ).pressable,
    ).toBe(false);
    expect(pressableGroups(undefined).pressable).toBe(false);
  });
});

describe('what admission says about a click', () => {
  const codes = (click: unknown, view: object | null = byWarehouse) => {
    const target = panel(click);
    return validatePanelClick(
      target,
      ['panels', 0],
      board(target),
      view as never,
    ).map(found => `${found.code}:${found.severity}`);
  };

  it('says nothing of a click that can do what it says', () => {
    expect(codes(undefined)).toEqual([]);
    expect(codes({ kind: 'filter', filter: 'region' })).toEqual([]);
    expect(codes({ kind: 'url', url: '/w/{{warehouse}}' })).toEqual([]);
    expect(codes({ kind: 'view', instanceId: 'list' })).toEqual([]);
  });

  it('warns of each click that cannot, and never refuses the board for it', () => {
    expect(codes('x')).toEqual(['dashboard.click.invalid:warning']);
    expect(
      codes({ kind: 'filter', filter: 'region' }, { config: recordConfig() }),
    ).toEqual(['dashboard.click.unpressable:warning']);
    expect(codes({ kind: 'filter', filter: 'gone' })).toEqual([
      'dashboard.click.filter-unknown:warning',
    ]);
    expect(codes({ kind: 'filter', filter: 'created' })).toEqual([
      'dashboard.click.filter-ungrouped:warning',
    ]);
    expect(codes({ kind: 'url', url: 'javascript:{{x}}' })).toEqual([
      'dashboard.click.url-unsafe:warning',
    ]);
    expect(codes({ kind: 'url', url: '/s/{{status}}' })).toEqual([
      'dashboard.click.url-unknown-field:warning',
    ]);
  });

  it('judges only what the board says before the view is known', () => {
    expect(codes({ kind: 'filter', filter: 'created' }, null)).toEqual([]);
    expect(codes({ kind: 'url', url: '/s/{{status}}' }, null)).toEqual([]);
  });

  it('warns of a filter the panel is not wired to', () => {
    const target = {
      ...panel({ kind: 'filter', filter: 'region' }),
      bindings: [],
    };
    expect(
      validatePanelClick(target, ['panels', 0], board(target), byWarehouse).map(
        found => found.code,
      ),
    ).toEqual(['dashboard.click.filter-unwired']);
  });
});

describe('setting a click, and what takes it away', () => {
  const click: PanelClick = { kind: 'filter', filter: 'region' };

  it('sets it on a data panel and takes it off with null', () => {
    const set = setPanelClick(board(panel()), 'chart', click);
    expect((set.panels[0] as DashboardViewPanel).click).toEqual(click);
    const off = setPanelClick(set, 'chart', null);
    expect('click' in off.panels[0]).toBe(false);
    // Nothing to take off, and a panel the board lacks: the same board.
    expect(setPanelClick(off, 'chart', null)).toBe(off);
    expect(setPanelClick(off, 'nope', click)).toBe(off);
  });

  it('goes with the filter it sets when the filter is unwired, retyped or removed', () => {
    const start = board(panel(click), panel(click, 'other'));
    const unwired = unbindPanels(start, 'region', ['chart']);
    expect('click' in unwired.panels[0]).toBe(false);
    expect((unwired.panels[1] as DashboardViewPanel).click).toEqual(click);
    for (const next of [
      removeFilter(start, 'region'),
      retypeFilter(start, 'region', 'number'),
    ])
      expect(next.panels.every(entry => !('click' in entry))).toBe(true);
    // A click setting another filter stays.
    const kept = unbindPanels(start, 'created', ['chart']);
    expect((kept.panels[0] as DashboardViewPanel).click).toEqual(click);
  });
});

describe('where a pressed value came from (DashboardFilters.from)', () => {
  const config = board(panel({ kind: 'filter', filter: 'region' }));

  it('keeps it for a value held and a panel whose press sets that filter', () => {
    const { filters, refused } = admitFilters(
      config,
      { values: { region: ['CN'] }, from: { region: 'chart' } },
      builtinFieldKinds,
    );
    expect(refused).toEqual([]);
    expect(filters).toEqual({
      values: { region: ['CN'] },
      from: { region: 'chart' },
    });
  });

  it('lets a stale one go without refusing anything', () => {
    for (const from of [
      { region: 'nope' },
      { created: 'chart' },
      { region: 3 },
      'chart',
    ]) {
      const { filters, refused } = admitFilters(
        config,
        { values: { region: ['CN'] }, from } as never,
        builtinFieldKinds,
      );
      expect(refused).toEqual([]);
      expect(filters).toEqual({ values: { region: ['CN'] } });
    }
    // A value not held has no press to come from.
    expect(
      admitFilters(
        config,
        { values: {}, from: { region: 'chart' } },
        builtinFieldKinds,
      ).filters,
    ).toEqual({ values: {} });
  });
});
