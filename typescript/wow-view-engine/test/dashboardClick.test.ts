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
  boardFilterChoices,
  boardValueChoices,
  validateBoardClick,
  validatePanelClick,
  type PanelReference,
} from '../src/dashboard/index.js';
import {
  builtinFieldKinds,
  type DashboardField,
  type DashboardViewConfig,
  type BoardValueSource,
  type DashboardViewPanel,
  type FieldDefinition,
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
  it('reads the four kinds and nothing else', () => {
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
    expect(
      clickOf(
        panel({
          kind: 'dashboard',
          instanceId: 'regional',
          values: { area: { dimension: 'warehouse' } },
        }),
      ),
    ).toEqual({
      kind: 'dashboard',
      instanceId: 'regional',
      values: { area: { dimension: 'warehouse' } },
    });
    expect(
      clickOf(
        panel({
          kind: 'dashboard',
          instanceId: 'regional',
          values: { area: { filter: 'region' } },
        }),
      ),
    ).toMatchObject({ values: { area: { filter: 'region' } } });
    for (const odd of [
      undefined,
      'filter',
      { kind: 'filter' },
      { kind: 'view', instanceId: '' },
      { kind: 'url', url: 3 },
      { kind: 'dashboard', instanceId: 'x' },
      { kind: 'dashboard', instanceId: '', values: {} },
      { kind: 'dashboard', instanceId: 'x', values: { area: 3 } },
      { kind: 'dashboard', instanceId: 'x', values: { area: 'warehouse' } },
      { kind: 'dashboard', instanceId: 'x', values: { area: {} } },
      {
        kind: 'dashboard',
        instanceId: 'x',
        values: { area: { dimension: 'warehouse', filter: 'region' } },
      },
      { kind: 'boards', instanceId: 'x', values: {} },
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

describe('a click that opens another board (D23 Q17)', () => {
  /** The board a press opens: an area and a period, and a text one. */
  const regional = (fields: DashboardField[]): PanelReference => ({
    definition: { id: 'overview', title: 'Overview', kind: 'dashboard' },
    fields: [],
    instance: {
      id: 'regional',
      definitionId: 'overview',
      title: 'Regional',
      scope: 'shared',
      revision: '1',
      config: dashboardConfig({ fields }),
    },
  });
  const area: DashboardField = { name: 'area', label: 'Area', kind: 'string' };
  const period: DashboardField = {
    name: 'period',
    label: 'Period',
    kind: 'datetime',
  };
  const groups = [
    { field: 'warehouse', type: 'TERMS' as const },
    { field: 'createdAt', type: 'DATE_HISTOGRAM' as const },
  ];
  const fields: FieldDefinition[] = [
    { name: 'warehouse', label: 'Warehouse', kind: 'string' },
    { name: 'createdAt', label: 'Created', kind: 'datetime' },
  ];
  const click = (values: Record<string, BoardValueSource>) => ({
    kind: 'dashboard' as const,
    instanceId: 'regional',
    values,
  });
  const judged = (
    values: Record<string, BoardValueSource>,
    target: PanelReference | null | undefined,
    known: typeof groups | null = groups,
  ) =>
    validateBoardClick(click(values), ['panels', 0, 'click'], known, {
      fields,
      // This board's own filters: a text Region, a date Created.
      own: [region, created],
      target,
    }).map(found => `${found.code}:${found.severity}`);

  it('offers each filter the dimensions whose field and bucketing it takes, never by name', () => {
    expect(boardValueChoices(area, groups, fields)).toEqual([groups[0]]);
    expect(boardValueChoices(period, groups, fields)).toEqual([groups[1]]);
    // A number filter takes neither a text value nor a date bucket.
    expect(
      boardValueChoices(
        { name: 'n', label: 'N', kind: 'number' },
        groups,
        fields,
      ),
    ).toEqual([]);
    // Without the panel's fields, the bucketing alone is asked.
    expect(boardValueChoices(area, groups, null)).toEqual([groups[0]]);
    // A text dimension over a field of another type is no choice.
    expect(
      boardValueChoices(
        area,
        [{ field: 'amount', type: 'TERMS' }],
        [{ name: 'amount', label: 'Amount', kind: 'number' }],
      ),
    ).toEqual([]);
  });

  it('says nothing of a mapping that holds, and judges only the panel before the board is read', () => {
    const target = regional([area, period]);
    expect(
      judged(
        {
          area: { dimension: 'warehouse' },
          period: { dimension: 'createdAt' },
        },
        target,
      ),
    ).toEqual([]);
    expect(judged({}, target)).toEqual([]);
    expect(judged({ area: { dimension: 'warehouse' } }, undefined)).toEqual([]);
    expect(judged({ area: { dimension: 'status' } }, undefined)).toEqual([
      'dashboard.click.board-dimension-unknown:warning',
    ]);
  });

  it('warns of a filter the board no longer has, a dimension that cannot fill it, or a board gone', () => {
    expect(
      judged({ gone: { dimension: 'warehouse' } }, regional([area])),
    ).toEqual(['dashboard.click.board-filter-unknown:warning']);
    expect(
      judged({ period: { dimension: 'warehouse' } }, regional([period])),
    ).toEqual(['dashboard.click.board-filter-mismatch:warning']);
    expect(judged({ area: { dimension: 'warehouse' } }, null)).toEqual([
      'dashboard.click.board-gone:warning',
    ]);
    const list = regional([area]);
    expect(
      judged(
        { area: { dimension: 'warehouse' } },
        { ...list, instance: { ...list.instance, config: recordConfig() } },
      ),
    ).toEqual(['dashboard.click.board-not-a-board:warning']);
  });

  it('is judged in admission against the board once the references hold it', () => {
    const target = panel(click({ area: { dimension: 'status' } }));
    const refs = new Map([['regional', regional([])]]);
    expect(
      validatePanelClick(
        target,
        ['panels', 0],
        board(target),
        byWarehouse,
        refs,
      ).map(found => found.code),
    ).toEqual([
      'dashboard.click.board-dimension-unknown',
      'dashboard.click.board-filter-unknown',
    ]);
    // Unread, only the panel's side is judged.
    expect(
      validatePanelClick(target, ['panels', 0], board(target), byWarehouse).map(
        found => found.code,
      ),
    ).toEqual(['dashboard.click.board-dimension-unknown']);
  });
});

describe('a mapping from one of this board’s filters (D23 Q17, 2026-09-23)', () => {
  const target = (fields: DashboardField[]): PanelReference => ({
    definition: { id: 'overview', title: 'Overview', kind: 'dashboard' },
    fields: [],
    instance: {
      id: 'regional',
      definitionId: 'overview',
      title: 'Regional',
      scope: 'shared',
      revision: '1',
      config: dashboardConfig({ fields }),
    },
  });
  const area: DashboardField = { name: 'area', label: 'Area', kind: 'enum' };
  const period: DashboardField = {
    name: 'period',
    label: 'Period',
    kind: 'date',
  };
  const judged = (
    values: Record<string, BoardValueSource>,
    read: PanelReference | undefined,
  ) =>
    validateBoardClick(
      { kind: 'dashboard', instanceId: 'regional', values },
      ['panels', 0, 'click'],
      null,
      { fields: null, own: [region, created], target: read },
    ).map(found => found.code);

  it('offers this board’s filters of the same type, never by name', () => {
    // A text filter takes a text one (string and enum are one family),
    // a date one a date one; neither the other.
    expect(boardFilterChoices(area, [region, created])).toEqual([region]);
    expect(boardFilterChoices(period, [region, created])).toEqual([created]);
    expect(
      boardFilterChoices({ name: 'n', label: 'N', kind: 'number' }, [
        region,
        created,
      ]),
    ).toEqual([]);
  });

  it('warns of a filter this board no longer has before the target is read', () => {
    expect(judged({ area: { filter: 'region' } }, undefined)).toEqual([]);
    expect(judged({ area: { filter: 'gone' } }, undefined)).toEqual([
      'dashboard.click.board-source-unknown',
    ]);
  });

  it('warns once the target is read of a filter it lacks or of another type', () => {
    expect(
      judged({ area: { filter: 'region' } }, target([area, period])),
    ).toEqual([]);
    expect(judged({ area: { filter: 'created' } }, target([area]))).toEqual([
      'dashboard.click.board-filter-mismatch',
    ]);
    expect(judged({ zone: { filter: 'region' } }, target([area]))).toEqual([
      'dashboard.click.board-filter-unknown',
    ]);
    // A source gone is said once, not again as a mismatch.
    expect(judged({ area: { filter: 'gone' } }, target([area]))).toEqual([
      'dashboard.click.board-source-unknown',
    ]);
  });

  it('is judged in admission against this board’s own filters', () => {
    const stale = panel({
      kind: 'dashboard',
      instanceId: 'regional',
      values: { area: { filter: 'gone' } },
    });
    expect(
      validatePanelClick(stale, ['panels', 0], board(stale), byWarehouse).map(
        found => found.code,
      ),
    ).toEqual(['dashboard.click.board-source-unknown']);
  });
});
