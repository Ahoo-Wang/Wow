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
 * Building a board, in the kernel (D22 A–E, batch B1): the 24-column grid
 * and how a stored 12-column board is read into it, tabs, a view the board
 * owns, a panel's override of how it looks, the heading card, and the
 * edits a board is built by.
 */

import {
  calcGridItemPosition,
  defaultGridConfig,
} from 'react-grid-layout/core';
import { describe, expect, it } from 'vitest';
import {
  DASHBOARD_GRID_COLUMNS,
  LEGACY_GRID_COLUMNS,
  MAX_DASHBOARD_TABS,
  MAX_HEADING_LENGTH,
  addPanel,
  addTab,
  builtinFieldKinds,
  compactTab,
  defaultPanelSize,
  duplicatePanel,
  editContent,
  emptyDashboardConfig,
  freshId,
  isOwnedPanel,
  migrateDashboardConfig,
  movePanelToTab,
  moveTab,
  panelTab,
  referToSaved,
  referencedInstance,
  removePanel,
  removeTab,
  renamePanel,
  renameTab,
  replacePanelView,
  setPresentation,
  validateDashboard,
  type DashboardPanel,
  type DashboardViewConfig,
  type FilterLeaf,
  type FilterNode,
  type Issue,
  type PanelDefinition,
} from '../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  panelReference,
  preCDashboardConfig,
  recordConfig,
} from './fixtures.js';

function codes(issues: readonly Issue[]): string[] {
  return issues.map(found => found.code);
}

function note(id: string, layout: DashboardPanel['layout'], tab?: string) {
  return {
    id,
    kind: 'markdown',
    content: '',
    layout,
    ...(tab ? { tab } : {}),
  } as DashboardPanel;
}

function saved(id: string, layout: DashboardPanel['layout']): DashboardPanel {
  return { id, kind: 'view', instanceId: 'pending', bindings: [], layout };
}

function owned(id: string, layout = { x: 0, y: 0, w: 12, h: 4 }) {
  return {
    id,
    kind: 'view',
    owned: { definitionId: 'orders', config: analysisConfig() },
    bindings: [],
    layout,
  } as DashboardPanel;
}

const ORDERS: PanelDefinition = {
  definition: ordersDefinition(),
  fields: ordersDefinition().fields,
};

function validate(
  config: DashboardViewConfig,
  definitions?: (id: string) => PanelDefinition | null,
): Issue[] {
  return validateDashboard(
    config,
    'personal',
    new Map([['pending', panelReference()]]),
    builtinFieldKinds,
    definitions ? { definitions } : {},
  );
}

function layouts(config: DashboardViewConfig) {
  return Object.fromEntries(
    config.panels.map(panel => [panel.id, panel.layout]),
  );
}

describe('the 24-column grid and the boards stored before it', () => {
  /** A board as stored before the grid had 24 columns: no `columns`. */
  const legacy = {
    ...emptyDashboardConfig(),
    columns: undefined,
    tabs: undefined,
    panels: [
      saved('a', { x: 0, y: 0, w: 7, h: 4 }),
      saved('b', { x: 7, y: 0, w: 5, h: 4 }),
      note('c', { x: 0, y: 4, w: 4, h: 2 }),
    ],
  } as unknown as DashboardViewConfig;
  delete (legacy as Partial<DashboardViewConfig>).columns;
  delete (legacy as Partial<DashboardViewConfig>).tabs;
  delete (legacy as Partial<DashboardViewConfig>).fixed;

  it('reads a board that does not say its grid as 12 columns, doubling x and w', () => {
    const read = migrateDashboardConfig(legacy);

    expect(read.columns).toBe(DASHBOARD_GRID_COLUMNS);
    expect(read.tabs).toEqual([]);
    expect(layouts(read)).toEqual({
      a: { x: 0, y: 0, w: 14, h: 4 },
      b: { x: 14, y: 0, w: 10, h: 4 },
      c: { x: 0, y: 4, w: 8, h: 2 },
    });
    expect(validate(read)).toEqual([]);
  });

  it('leaves a board that says its grid alone, as the same object', () => {
    const current = dashboardConfig({
      panels: [note('a', { x: 3, y: 0, w: 5, h: 1 })],
    });
    const other = { ...current, columns: 12 } as unknown as DashboardViewConfig;

    expect(migrateDashboardConfig(current)).toBe(current);
    // Named, so taken at its word: admission refuses it rather than a guess.
    expect(migrateDashboardConfig(other)).toBe(other);
  });

  /**
   * D23 Q16, D26 Q31: a board condition written before batch C becomes its
   * filters' defaults where a filter could hold it; the rest becomes the
   * board's fixed scope (`fixed`), which is also the mark that the board
   * was read — a second read, or a read after any edit, moves nothing.
   */
  describe('a pre-C board condition', () => {
    const region = { name: 'region', label: '仓库', kind: 'string' } as const;
    const created = {
      name: 'created',
      label: '创建时间',
      kind: 'datetime',
    } as const;
    const amount = { name: 'amount', label: '金额', kind: 'number' } as const;
    const window = { type: 'relative', unit: 'day', amount: 30 };
    const EMPTY = { op: 'and', children: [] };

    it('moves a leaf a filter could hold into that filter’s default', () => {
      const board = preCDashboardConfig({
        fields: [region, created, amount],
        filter: {
          op: 'and',
          children: [
            { field: 'region', operator: 'IN', value: ['CN'] },
            { field: 'created', operator: 'BETWEEN', value: window },
            // An EQ of one number is the one-entry list the filter keeps.
            { field: 'amount', operator: 'EQ', value: 100 },
          ],
        },
      });

      const read = migrateDashboardConfig(board);

      expect(read.filter).toEqual(EMPTY);
      expect(read.fixed).toEqual(EMPTY);
      expect(read.fields).toEqual([
        { ...region, default: ['CN'] },
        { ...created, default: window },
        { ...amount, default: [100] },
      ]);
      // It marks itself: nothing is left to move.
      expect(migrateDashboardConfig(read)).toBe(read);
    });

    it('keeps what no filter could hold as the fixed scope', () => {
      const kept: FilterNode[] = [
        // Another operator than the filter asks with.
        { field: 'amount', operator: 'GT', value: 100 },
        // Two values for a filter that takes one.
        { field: 'region', operator: 'IN', value: ['CN', 'JP'] },
        // A field that is no filter of this board.
        { field: 'status', operator: 'IN', value: ['PAID'] },
        // A group is not taken apart.
        {
          op: 'or',
          children: [{ field: 'region', operator: 'IN', value: ['US'] }],
        },
        // A filter with a default of its own keeps it.
        {
          field: 'created',
          operator: 'BETWEEN',
          value: { type: 'relative', unit: 'day', amount: 7 },
        },
      ];
      const board = preCDashboardConfig({
        fields: [region, amount, { ...created, default: window }],
        filter: { op: 'and', children: kept },
      });

      const read = migrateDashboardConfig(board);

      expect(read.fixed).toEqual({ op: 'and', children: kept });
      expect(read.filter).toEqual(EMPTY);
      expect(read.fields).toBe(board.fields);
      expect(migrateDashboardConfig(read)).toBe(read);
    });

    it('moves one leaf per filter and keeps the second', () => {
      const second: FilterLeaf = {
        field: 'region',
        operator: 'IN',
        value: ['JP'],
      };
      const board = preCDashboardConfig({
        fields: [region],
        filter: {
          op: 'and',
          children: [
            { field: 'region', operator: 'IN', value: ['CN'] },
            second,
          ],
        },
      });

      const read = migrateDashboardConfig(board);

      expect(read.fields).toEqual([{ ...region, default: ['CN'] }]);
      expect(read.fixed.children).toEqual([second]);
      expect(read.filter).toEqual(EMPTY);
    });

    /**
     * A-02: the leaf a single-value filter could not hold stays fixed once
     * the board was read, whatever the author changes of that filter after
     * — turning on 「可多选」 must not make the next read move it into a
     * default a reader can clear.
     */
    it('never moves again once read, whatever the author changes of a filter', () => {
      const leaf: FilterLeaf = {
        field: 'region',
        operator: 'IN',
        value: ['EU', 'CN'],
      };
      const board = preCDashboardConfig({
        fields: [region],
        filter: { op: 'and', children: [leaf] },
      });

      const read = migrateDashboardConfig(board);
      const edited = {
        ...read,
        fields: read.fields.map(field => ({
          ...field,
          multiple: true as const,
        })),
      };
      const again = migrateDashboardConfig(edited);

      expect(again).toBe(edited);
      expect(again.fixed.children).toEqual([leaf]);
      expect(again.fields[0].default).toBeUndefined();
    });

    it('takes no tree apart that is not a plain AND, and fixes it whole', () => {
      const tree = {
        op: 'or' as const,
        children: [{ field: 'region', operator: 'IN' as const, value: ['CN'] }],
      };
      const board = preCDashboardConfig({ fields: [region], filter: tree });

      const read = migrateDashboardConfig(board);

      expect(read.fixed).toBe(tree);
      expect(read.filter).toEqual(EMPTY);
      expect(read.fields).toBe(board.fields);
    });

    it('leaves a board that has a fixed scope alone, whatever its filter says', () => {
      const board = dashboardConfig({
        fields: [region],
        filter: {
          op: 'and',
          children: [{ field: 'region', operator: 'IN', value: ['CN'] }],
        },
      });

      expect(migrateDashboardConfig(board)).toBe(board);
    });

    it('marks a board whose filter is no tree, and leaves the filter for admission', () => {
      const board = preCDashboardConfig({ filter: 'nope' as never });

      const read = migrateDashboardConfig(board);

      expect(read.fixed).toEqual(EMPTY);
      expect(read.filter).toBe('nope');
      expect(codes(validate(read))).toContain('config.filter.invalid');
    });
  });

  /**
   * The fixed scope is judged as `filter` was (D26 Q31): over the board's
   * own fields, at its own path, and every panel must carry all of it.
   */
  describe('admission of the fixed scope', () => {
    const region = { name: 'region', label: '仓库', kind: 'string' } as const;

    it('refuses one that is missing or no tree', () => {
      const without: Partial<DashboardViewConfig> = dashboardConfig();
      delete without.fixed;
      expect(validate(without as DashboardViewConfig)).toEqual([
        expect.objectContaining({
          code: 'config.filter.invalid',
          path: ['fixed'],
        }),
      ]);
    });

    it('judges its leaves against the board’s fields, at its path', () => {
      const board = dashboardConfig({
        fields: [region],
        fixed: {
          op: 'and',
          children: [{ field: 'nowhere', operator: 'IN', value: ['CN'] }],
        },
      });

      expect(
        validate(board).find(found => found.path[0] === 'fixed')?.path,
      ).toEqual(['fixed', 'children', 0]);
    });

    it('asks every data panel to carry every field it names', () => {
      const board = dashboardConfig({
        fields: [region],
        fixed: {
          op: 'and',
          children: [{ field: 'region', operator: 'IN', value: ['CN'] }],
        },
        panels: [saved('a', { x: 0, y: 0, w: 12, h: 4 })],
      });

      expect(codes(validate(board))).toContain('dashboard.binding.missing');
    });
  });

  it('carries what is no panel, or has no numbers, over untouched', () => {
    const odd = {
      ...legacy,
      panels: [
        'no panel',
        { id: 'x', layout: 'nowhere' },
        { id: 'y', layout: { x: '1', w: 2 } },
      ],
    } as unknown as DashboardViewConfig;

    const read = migrateDashboardConfig(odd);

    expect(read.panels[0]).toBe(odd.panels[0]);
    expect(read.panels[1]).toBe(odd.panels[1]);
    expect(read.panels[2].layout).toEqual({ x: '1', w: 4 });
    expect(
      migrateDashboardConfig({ ...legacy, panels: 'nope' } as never).panels,
    ).toBe('nope');
    expect(migrateDashboardConfig(null as never)).toBeNull();
  });

  /**
   * Measured, not argued: every panel a 12-column board can hold, drawn by
   * the grid library's own arithmetic at the margins `DashboardGrid` uses,
   * lands on the same pixels once read into 24 columns — at a phone-ish
   * `md`, a laptop and a wide screen. The story boards and the fixtures
   * are among these.
   */
  it('draws every 12-column panel on exactly the pixels it had', () => {
    const margin = defaultGridConfig.margin;
    const padding = defaultGridConfig.containerPadding ?? margin;
    const at = (cols: number, containerWidth: number) => ({
      margin,
      containerPadding: padding,
      containerWidth,
      cols,
      rowHeight: 80,
      maxRows: Infinity,
    });
    let compared = 0;
    for (const width of [768, 1003, 1280, 1920])
      for (let x = 0; x < LEGACY_GRID_COLUMNS; x += 1)
        for (let w = 1; x + w <= LEGACY_GRID_COLUMNS; w += 1) {
          const before = calcGridItemPosition(at(12, width), x, 3, w, 2);
          const read = migrateDashboardConfig({
            ...legacy,
            panels: [note('p', { x, y: 3, w, h: 2 })],
          });
          const { layout } = read.panels[0];
          const after = calcGridItemPosition(
            at(DASHBOARD_GRID_COLUMNS, width),
            layout.x,
            layout.y,
            layout.w,
            layout.h,
          );
          expect(Math.abs(after.left - before.left)).toBeLessThanOrEqual(1);
          expect(Math.abs(after.width - before.width)).toBeLessThanOrEqual(1);
          expect(after.top).toBe(before.top);
          expect(after.height).toBe(before.height);
          compared += 1;
        }
    expect(compared).toBe(4 * 78);
  });

  it('refuses a grid of another width, as an error of the board', () => {
    const other = {
      ...dashboardConfig(),
      columns: 12,
    } as unknown as DashboardViewConfig;
    expect(validate(other)).toEqual([
      expect.objectContaining({
        code: 'dashboard.grid.unsupported',
        path: ['columns'],
      }),
    ]);
  });

  it('starts an empty board in the form it saves in', () => {
    expect(emptyDashboardConfig()).toMatchObject({ columns: 24, tabs: [] });
  });
});

describe('tabs', () => {
  const two = dashboardConfig({
    tabs: [
      { id: 'tab-1', title: 'Sales' },
      { id: 'tab-2', title: 'Stock' },
    ],
    panels: [
      note('a', { x: 0, y: 0, w: 12, h: 2 }, 'tab-1'),
      note('b', { x: 0, y: 2, w: 12, h: 2 }, 'tab-1'),
      note('c', { x: 0, y: 0, w: 24, h: 2 }, 'tab-2'),
    ],
  });

  it('are ids and names; a panel sits on one of them', () => {
    expect(validate(two)).toEqual([]);
    expect(panelTab(two, two.panels[2])).toBe('tab-2');
  });

  it('refuse a missing or repeated id, and name a nameless tab by its place', () => {
    const bad = dashboardConfig({
      tabs: [
        { id: '', title: 'A' },
        { id: 'x', title: ' ' },
        { id: 'x', title: 'B' },
        'no tab',
      ] as never,
    });

    expect(validate(bad).map(found => [found.code, found.severity])).toEqual([
      ['dashboard.tab.id-empty', 'error'],
      ['dashboard.tab.title-empty', 'warning'],
      ['dashboard.tab.id-duplicate', 'error'],
      ['dashboard.shape.invalid', 'error'],
    ]);
    expect(codes(validate(dashboardConfig({ tabs: 'none' as never })))).toEqual(
      ['dashboard.shape.invalid'],
    );
  });

  it(`hold at most ${MAX_DASHBOARD_TABS}`, () => {
    const many = dashboardConfig({
      tabs: Array.from({ length: MAX_DASHBOARD_TABS + 1 }, (_, n) => ({
        id: `t${n}`,
        title: `T${n}`,
      })),
    });
    expect(codes(validate(many))).toEqual(['dashboard.tabs.too-many']);
    expect(
      addTab({ ...many, tabs: many.tabs.slice(1) }, 'One more', 'x'),
    ).toBeNull();
  });

  it('show a panel naming no tab of the board on the first, and say so', () => {
    const stray = dashboardConfig({
      ...two,
      panels: [
        note('a', { x: 0, y: 0, w: 1, h: 1 }, 'gone'),
        note('b', { x: 1, y: 0, w: 1, h: 1 }),
      ],
    });
    const untabbed = dashboardConfig({
      panels: [note('a', { x: 0, y: 0, w: 1, h: 1 }, 'gone')],
    });

    expect(panelTab(stray, stray.panels[0])).toBe('tab-1');
    expect(panelTab(stray, stray.panels[1])).toBe('tab-1');
    expect(panelTab(untabbed, untabbed.panels[0])).toBeNull();
    for (const config of [stray, untabbed])
      expect(
        validate(config).every(
          found =>
            found.code === 'dashboard.panel.tab-unknown' &&
            found.severity === 'warning',
        ),
      ).toBe(true);
    expect(validate(stray)).toHaveLength(2);
  });

  it('start with two when the first is added: the panels there go on the first', () => {
    const board = dashboardConfig({
      panels: [note('a', { x: 0, y: 0, w: 1, h: 1 })],
    });

    const added = addTab(board, ' Stock ', 'Overview');

    expect(added?.id).toBe('tab-2');
    expect(added?.config.tabs).toEqual([
      { id: 'tab-1', title: 'Overview' },
      { id: 'tab-2', title: 'Stock' },
    ]);
    expect(added?.config.panels[0].tab).toBe('tab-1');
    expect(addTab(board, 'Stock', ' ')).toBeNull();
    expect(addTab(board, ' ', 'Overview')).toBeNull();
  });

  it('add one more last, under a fresh id', () => {
    const added = addTab(two, 'Returns', 'unused');
    expect(added?.config.tabs.map(tab => tab.id)).toEqual([
      'tab-1',
      'tab-2',
      'tab-3',
    ]);
    expect(freshId(['panel-1', 'panel-3'], 'panel')).toBe('panel-2');
  });

  it('are renamed, reordered, and left alone for a blank name or an unknown tab', () => {
    expect(renameTab(two, 'tab-2', ' Inventory ').tabs[1].title).toBe(
      'Inventory',
    );
    expect(renameTab(two, 'tab-2', 'Stock')).toBe(two);
    expect(renameTab(two, 'tab-2', ' ')).toBe(two);
    expect(renameTab(two, 'ghost', 'X')).toBe(two);

    expect(moveTab(two, 'tab-2', 0).tabs.map(tab => tab.id)).toEqual([
      'tab-2',
      'tab-1',
    ]);
    expect(moveTab(two, 'tab-2', 9).tabs.map(tab => tab.id)).toEqual([
      'tab-1',
      'tab-2',
    ]);
    expect(moveTab(two, 'tab-2', 1)).toBe(two);
    expect(moveTab(two, 'ghost', 0)).toBe(two);
  });

  it('are removed with their panels, never the last one', () => {
    const removed = removeTab(two, 'tab-1');

    expect(removed.tabs).toEqual([{ id: 'tab-2', title: 'Stock' }]);
    expect(removed.panels.map(panel => panel.id)).toEqual(['c']);
    expect(removeTab(removed, 'tab-2')).toBe(removed);
    expect(removeTab(two, 'ghost')).toBe(two);
  });

  it('take a panel moved there at their first free place, and close up behind it', () => {
    const moved = movePanelToTab(two, 'a', 'tab-2');

    expect(moved.panels.find(panel => panel.id === 'a')).toMatchObject({
      tab: 'tab-2',
      layout: { x: 0, y: 2, w: 12, h: 2 },
    });
    // b rises into the room a left on the first tab.
    expect(moved.panels.find(panel => panel.id === 'b')?.layout.y).toBe(0);
    expect(movePanelToTab(two, 'a', 'tab-1')).toBe(two);
    expect(movePanelToTab(two, 'a', 'ghost')).toBe(two);
    expect(movePanelToTab(two, 'ghost', 'tab-2')).toBe(two);
  });
});

describe('panels the board owns', () => {
  it('are judged against their definition, with bindings and the global filter', () => {
    const board = dashboardConfig({
      fields: [{ name: 'region', label: 'Region', kind: 'string' }],
      panels: [
        {
          ...owned('own'),
          bindings: [{ globalField: 'region', panelField: 'nowhere' }],
        } as DashboardPanel,
      ],
    });

    expect(codes(validate(board, () => ORDERS))).toEqual([
      'dashboard.binding.panel-unknown',
    ]);
    expect(isOwnedPanel(board.panels[0])).toBe(true);
    expect(referencedInstance(board.panels[0])).toBeUndefined();
  });

  it('are judged by their shape alone where no definition can be looked up', () => {
    expect(validate(dashboardConfig({ panels: [owned('own')] }))).toEqual([]);
  });

  it('name a definition this release declares, of data with an analysis', () => {
    const board = dashboardConfig({ panels: [owned('own')] });

    expect(validate(board, () => null)).toEqual([
      expect.objectContaining({
        code: 'dashboard.panel.definition-unknown',
        path: ['panels', 0, 'owned', 'definitionId'],
        severity: 'error',
      }),
    ]);
    expect(
      codes(
        validate(board, () => ({
          definition: ordersDefinition({ analysis: undefined }),
          fields: [],
        })),
      ),
    ).toEqual(['dashboard.panel.kind-unsupported']);
  });

  it('hold an analysis, in the expected shape', () => {
    const shaped = (value: unknown) =>
      dashboardConfig({
        panels: [{ ...owned('own'), owned: value } as DashboardPanel],
      });

    for (const value of [
      null,
      { definitionId: 1, config: analysisConfig() },
      { definitionId: 'orders', config: recordConfig() },
      { definitionId: 'orders' },
    ])
      expect(codes(validate(shaped(value), () => ORDERS))).toEqual([
        'dashboard.panel.owned-invalid',
      ]);
  });

  it('point at a saved view or own one — never both, never neither', () => {
    const both = { ...owned('own'), instanceId: 'pending' } as DashboardPanel;
    const neither = {
      ...saved('none', { x: 0, y: 0, w: 1, h: 1 }),
      instanceId: undefined,
    } as unknown as DashboardPanel;

    for (const panel of [both, neither])
      expect(codes(validate(dashboardConfig({ panels: [panel] })))).toEqual([
        'dashboard.panel.source-invalid',
      ]);
  });

  it('point at the saved view they are saved as, keeping how they look', () => {
    const board = dashboardConfig({
      panels: [
        {
          ...owned('own'),
          presentation: { layout: 'chart' },
        } as DashboardPanel,
        saved('other', { x: 12, y: 0, w: 12, h: 4 }),
      ],
    });

    const promoted = referToSaved(board, 'own', 'view-9');

    expect(promoted.panels[0]).toMatchObject({
      instanceId: 'view-9',
      presentation: { layout: 'chart' },
    });
    expect(promoted.panels[0]).not.toHaveProperty('owned');
    expect(referToSaved(board, 'other', 'pending')).toBe(board);
  });

  /** 「复制为共享视图并替换」 (D22 B): the copy is the same view, so nothing else moves. */
  it('point a saved panel at its copy, keeping its look and its click', () => {
    const board = dashboardConfig({
      panels: [
        {
          ...saved('mine', { x: 0, y: 0, w: 12, h: 4 }),
          title: 'Mine',
          presentation: { layout: 'chart' },
          click: { kind: 'url', url: 'https://example.com' },
        } as DashboardPanel,
      ],
    });

    expect(referToSaved(board, 'mine', 'copy-1').panels[0]).toEqual({
      ...board.panels[0],
      instanceId: 'copy-1',
    });
  });
});

describe("a panel's override of how it looks", () => {
  it('is an object of the look members, else dropped with a note', () => {
    const with_ = (presentation: unknown) =>
      dashboardConfig({
        panels: [
          {
            ...saved('p', { x: 0, y: 0, w: 1, h: 1 }),
            presentation,
          } as DashboardPanel,
        ],
      });

    expect(
      validate(with_({ layout: 'chart', chart: { type: 'pie' }, table: {} })),
    ).toEqual([]);
    for (const bad of ['chart', { groups: [] }])
      expect(validate(with_(bad))).toEqual([
        expect.objectContaining({
          code: 'dashboard.panel.presentation-dropped',
          path: ['panels', 0, 'presentation'],
          severity: 'warning',
        }),
      ]);
  });

  it('is set, and reset to the view’s own look', () => {
    const board = dashboardConfig({
      panels: [saved('p', { x: 0, y: 0, w: 1, h: 1 })],
    });

    const set = setPresentation(board, 'p', { layout: 'chart' });
    expect(set.panels[0]).toMatchObject({ presentation: { layout: 'chart' } });
    const reset = setPresentation(set, 'p', null);
    expect(reset.panels[0]).not.toHaveProperty('presentation');
    expect(setPresentation(reset, 'p', {})).toBe(reset);
    expect(setPresentation(board, 'ghost', { layout: 'chart' })).toBe(board);
  });
});

describe('the heading card', () => {
  it('holds one line of plain text, up to a limit', () => {
    const heading = (content: unknown) =>
      dashboardConfig({
        panels: [
          {
            id: 'h',
            kind: 'heading',
            content,
            layout: { x: 0, y: 0, w: 24, h: 1 },
          } as DashboardPanel,
        ],
      });

    expect(validate(heading('Sales'))).toEqual([]);
    expect(validate(heading(''))).toEqual([]);
    expect(
      codes(validate(heading('x'.repeat(MAX_HEADING_LENGTH + 1)))),
    ).toEqual(['dashboard.heading.too-long']);
    expect(codes(validate(heading(3)))).toEqual(['dashboard.shape.invalid']);
  });
});

describe('building a board', () => {
  const board = dashboardConfig({
    panels: [
      saved('a', { x: 0, y: 0, w: 12, h: 4 }),
      note('b', { x: 0, y: 4, w: 24, h: 2 }),
    ],
  });

  it('adds a panel at the first free place at or below the row on screen', () => {
    const added = addPanel(board, { kind: 'view', instanceId: 'pending' });

    expect(added?.id).toBe('panel-1');
    expect(added?.config.panels[2]).toEqual({
      id: 'panel-1',
      kind: 'view',
      instanceId: 'pending',
      bindings: [],
      layout: { x: 12, y: 0, w: 12, h: 4 },
    });
    expect(
      addPanel(board, { kind: 'heading', content: 'Stock' }, { fromRow: 5 })
        ?.config.panels[2].layout,
    ).toEqual({ x: 0, y: 6, w: 24, h: 1 });
  });

  it('sizes a new panel by what it shows (D22 A)', () => {
    const metric = analysisConfig({
      layout: 'chart',
      chart: { type: 'metric', metric: { value: 'orders' } } as never,
    });
    expect(defaultPanelSize({ kind: 'view' }, metric)).toEqual({ w: 6, h: 2 });
    expect(
      defaultPanelSize({ kind: 'view' }, analysisConfig({ layout: 'chart' })),
    ).toEqual({ w: 12, h: 4 });
    expect(defaultPanelSize({ kind: 'view' }, analysisConfig())).toEqual({
      w: 24,
      h: 4,
    });
    expect(defaultPanelSize({ kind: 'view' }, recordConfig())).toEqual({
      w: 24,
      h: 5,
    });
    expect(defaultPanelSize({ kind: 'view' })).toEqual({ w: 12, h: 4 });
    expect(defaultPanelSize(owned('o') as never)).toEqual({ w: 24, h: 4 });
    expect(defaultPanelSize({ kind: 'heading' })).toEqual({ w: 24, h: 1 });
    expect(defaultPanelSize({ kind: 'markdown' })).toEqual({ w: 12, h: 3 });
    expect(defaultPanelSize({ kind: 'image' })).toEqual({ w: 8, h: 4 });
    expect(defaultPanelSize({ kind: 'links' })).toEqual({ w: 8, h: 3 });
  });

  it('adds onto the tab asked for, the first when it names none of the board', () => {
    const tabbed = addTab(board, 'Two', 'One')!.config;

    expect(
      addPanel(tabbed, { kind: 'markdown', content: '' }, { tab: 'tab-2' })
        ?.config.panels[2],
    ).toMatchObject({
      tab: 'tab-2',
      layout: { x: 0, y: 0 },
    });
    expect(
      addPanel(tabbed, { kind: 'markdown', content: '' }, { tab: 'ghost' })
        ?.config.panels[2].tab,
    ).toBe('tab-1');
  });

  it('adds nothing past the most panels a board may hold', () => {
    expect(
      addPanel(board, { kind: 'markdown', content: '' }, { max: 2 }),
    ).toBeNull();
  });

  it('removes a panel and closes up behind it', () => {
    const removed = removePanel(board, 'a');

    expect(removed.panels).toEqual([note('b', { x: 0, y: 0, w: 24, h: 2 })]);
    expect(removePanel(board, 'ghost')).toBe(board);
  });

  it('duplicates a panel beside it when there is room, under it when not', () => {
    const beside = duplicatePanel(board, 'a');
    expect(beside?.config.panels[2]).toMatchObject({
      id: 'panel-1',
      instanceId: 'pending',
      layout: { x: 12, y: 0, w: 12, h: 4 },
    });
    const under = duplicatePanel(board, 'b');
    expect(under?.config.panels[2]).toMatchObject({
      kind: 'markdown',
      layout: { x: 0, y: 6, w: 24, h: 2 },
    });
    expect(duplicatePanel(board, 'ghost')).toBeNull();
    expect(duplicatePanel(board, 'a', 2)).toBeNull();
  });

  it('copies an owned view with the panel', () => {
    const own = dashboardConfig({ panels: [owned('o')] });
    const copy = duplicatePanel(own, 'o')!.config.panels[1];

    expect(isOwnedPanel(copy)).toBe(true);
    expect(copy).not.toHaveProperty('instanceId');
  });

  it('renames a panel, and a blank name takes the title off', () => {
    const named = renamePanel(board, 'a', ' Pending ');
    expect(named.panels[0].title).toBe('Pending');
    expect(renamePanel(named, 'a', 'Pending')).toBe(named);
    expect(renamePanel(named, 'a', ' ').panels[0]).not.toHaveProperty('title');
    expect(renamePanel(board, 'a', '')).toBe(board);
  });

  it('replaces the view a panel shows, letting its override go', () => {
    const looked = setPresentation(board, 'a', { layout: 'chart' });

    const replaced = replacePanelView(looked, 'a', 'other');
    expect(replaced.panels[0]).toMatchObject({
      instanceId: 'other',
      bindings: [],
    });
    expect(replaced.panels[0]).not.toHaveProperty('presentation');
    expect(replacePanelView(board, 'a', 'pending')).toBe(board);
    expect(replacePanelView(board, 'b', 'other')).toBe(board);
    expect(
      replacePanelView(dashboardConfig({ panels: [owned('o')] }), 'o', 'v')
        .panels[0],
    ).not.toHaveProperty('owned');
  });

  it('edits what a content panel holds, and nothing else', () => {
    expect(editContent(board, 'b', { content: 'Hi' }).panels[1]).toMatchObject({
      content: 'Hi',
    });
    expect(editContent(board, 'b', { kind: 'image' } as never)).toBe(board);
    expect(editContent(board, 'a', { content: 'Hi' } as never)).toBe(board);
  });

  it('compacts one tab and leaves the rest alone', () => {
    const holey = dashboardConfig({
      panels: [note('a', { x: 0, y: 5, w: 1, h: 1 })],
    });
    expect(compactTab(holey, null).panels[0].layout.y).toBe(0);
  });
});
