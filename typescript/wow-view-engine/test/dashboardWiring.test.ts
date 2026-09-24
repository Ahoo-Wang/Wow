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
 * Wiring a board's filters and setting them up, in the kernel (D22 G, batch
 * C1): which fields of a panel a filter can be wired to, auto-connect (the
 * same name and the same type, on any tab, over any data — user ruling
 * 同名同类型即接，跨定义也接), a panel added coming wired, what reaches a
 * panel and a tab, and each edit of the filters as a pure function.
 */

import { describe, expect, it } from 'vitest';
import {
  MAX_DASHBOARD_FILTERS,
  addFilter,
  autoBindings,
  bindPanel,
  filterReach,
  filtersOnTab,
  moveFilter,
  removeFilter,
  removeFixedScope,
  renameFilter,
  retypeFilter,
  setFilterDefault,
  setFilterMultiple,
  setFilterOptions,
  setFilterRequired,
  setTimeGrouping,
  unbindPanels,
  wireableFields,
  wiredOptions,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewPanel,
  type FieldDefinition,
  type PanelFields,
} from '../src/index.js';
import { dashboardConfig } from './fixtures.js';

const ORDERS: FieldDefinition[] = [
  { name: 'warehouse', label: 'Warehouse', kind: 'string' },
  { name: 'createdAt', label: 'Created', kind: 'datetime' },
  { name: 'customer', label: 'Customer', kind: 'reference', remote: 'people' },
  { name: 'amount', label: 'Amount', kind: 'number' },
];
/** Another dataset: its time is a `date`, and it has a `createdAt` too. */
const SHIPMENTS: FieldDefinition[] = [
  { name: 'createdAt', label: 'Shipped', kind: 'date' },
  { name: 'carrier', label: 'Carrier', kind: 'enum', options: [] },
];
/** A third, whose `createdAt` is no time at all. */
const NOTES: FieldDefinition[] = [
  { name: 'createdAt', label: 'Written by', kind: 'string' },
];

function view(
  id: string,
  instanceId: string,
  extra: Partial<DashboardViewPanel> = {},
): DashboardPanel {
  return {
    id,
    kind: 'view',
    instanceId,
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...extra,
  } as DashboardPanel;
}

const fieldsOf: PanelFields = panel => {
  if (panel.instanceId === 'orders') return ORDERS;
  if (panel.instanceId === 'shipments') return SHIPMENTS;
  if (panel.instanceId === 'notes') return NOTES;
  return null;
};

const CREATED: DashboardField = {
  name: 'created',
  label: 'Created',
  kind: 'datetime',
};

/** Four panels over three datasets on two tabs, a note among them. */
const board = dashboardConfig({
  tabs: [
    { id: 'one', title: 'One' },
    { id: 'two', title: 'Two' },
  ],
  fields: [CREATED],
  panels: [
    view('a', 'orders', { tab: 'one' }),
    view('b', 'orders', { tab: 'one' }),
    view('c', 'shipments', { tab: 'two' }),
    view('d', 'notes', { tab: 'two' }),
    {
      id: 'note',
      kind: 'markdown',
      content: 'hi',
      layout: { x: 0, y: 8, w: 6, h: 2 },
      tab: 'one',
    },
  ],
});

function bindingsOf(config: typeof board, id: string) {
  const found = config.panels.find(panel => panel.id === id);
  return found?.kind === 'view' ? found.bindings : undefined;
}

describe('wiring a filter', () => {
  it('lists the fields of its type, and only those', () => {
    expect(wireableFields(CREATED, ORDERS).map(field => field.name)).toEqual([
      'createdAt',
    ]);
    expect(
      wireableFields({ kind: 'string' }, SHIPMENTS).map(field => field.name),
    ).toEqual(['carrier']);
    expect(wireableFields({ kind: 'boolean' }, ORDERS)).toEqual([]);
  });

  it('wires the panel chosen by hand, then every panel with that name and type on its own — any tab, any data', () => {
    const { config, connected } = bindPanel(
      board,
      'created',
      'a',
      'createdAt',
      fieldsOf,
    );

    expect(bindingsOf(config, 'a')).toEqual([
      { globalField: 'created', panelField: 'createdAt' },
    ]);
    // `b` on the same data, `c` on another tab and another dataset whose
    // `createdAt` is a date; `d`'s `createdAt` is text, so it is left alone.
    expect(connected).toEqual(['b', 'c']);
    expect(bindingsOf(config, 'c')).toEqual([
      { globalField: 'created', panelField: 'createdAt', auto: true },
    ]);
    expect(bindingsOf(config, 'd')).toEqual([]);
  });

  it('leaves a panel wired already as it was wired', () => {
    const wired = {
      ...board,
      panels: board.panels.map(panel =>
        panel.id === 'b'
          ? {
              ...panel,
              bindings: [{ globalField: 'created', panelField: 'createdAt' }],
            }
          : panel,
      ),
    } as typeof board;
    const { config, connected } = bindPanel(
      wired,
      'created',
      'a',
      'createdAt',
      fieldsOf,
    );
    expect(connected).toEqual(['c']);
    expect(bindingsOf(config, 'b')).toEqual([
      { globalField: 'created', panelField: 'createdAt' },
    ]);
  });

  it('rewires a panel by hand, the old wire replaced', () => {
    const once = bindPanel(board, 'created', 'c', 'createdAt', fieldsOf).config;
    const again = bindPanel(once, 'created', 'c', 'createdAt', fieldsOf);
    expect(bindingsOf(again.config, 'c')).toEqual([
      { globalField: 'created', panelField: 'createdAt' },
    ]);
    expect(again.connected).toEqual([]);
  });

  it('does nothing for a filter, a panel or a field the board lacks, or a field of another type', () => {
    for (const [name, panel, field] of [
      ['gone', 'a', 'createdAt'],
      ['created', 'gone', 'createdAt'],
      ['created', 'note', 'createdAt'],
      ['created', 'a', 'nothing'],
      ['created', 'd', 'createdAt'],
      ['created', 'x', 'createdAt'],
    ])
      expect(bindPanel(board, name, panel, field, fieldsOf)).toEqual({
        config: board,
        connected: [],
      });
  });

  it('gives an id filter the candidate source of the field it is wired to', () => {
    const ids = dashboardConfig({
      fields: [{ name: 'customer', label: 'Customer', kind: 'reference' }],
      panels: [view('a', 'orders')],
    });
    const { config } = bindPanel(ids, 'customer', 'a', 'customer', fieldsOf);
    expect(config.fields[0].remote).toBe('people');
    // One that names a source keeps it.
    const own = {
      ...ids,
      fields: [{ ...ids.fields[0], remote: 'mine' }],
    };
    expect(
      bindPanel(own, 'customer', 'a', 'customer', fieldsOf).config.fields[0]
        .remote,
    ).toBe('mine');
  });

  it('takes wires off — the undo of auto-connect', () => {
    const { config, connected } = bindPanel(
      board,
      'created',
      'a',
      'createdAt',
      fieldsOf,
    );
    const undone = unbindPanels(config, 'created', connected);
    expect(bindingsOf(undone, 'a')).toHaveLength(1);
    expect(bindingsOf(undone, 'b')).toEqual([]);
    expect(bindingsOf(undone, 'c')).toEqual([]);
    expect(unbindPanels(undone, 'created', ['b', 'note'])).toBe(undone);
  });
});

describe('a panel added comes wired', () => {
  it('through the name its filter is wired through most, else its own', () => {
    const wired = bindPanel(
      board,
      'created',
      'a',
      'createdAt',
      fieldsOf,
    ).config;
    expect(autoBindings(wired, SHIPMENTS)).toEqual([
      { globalField: 'created', panelField: 'createdAt', auto: true },
    ]);
    // Nothing wired yet: a field named as the filter is.
    const named = dashboardConfig({
      fields: [{ name: 'createdAt', label: 'Created', kind: 'date' }],
    });
    expect(autoBindings(named, ORDERS)).toEqual([
      { globalField: 'createdAt', panelField: 'createdAt', auto: true },
    ]);
    expect(autoBindings(board, ORDERS)).toEqual([]);
    expect(autoBindings(wired, NOTES)).toEqual([]);
  });

  it('keeps the wires it was given', () => {
    const wired = bindPanel(
      board,
      'created',
      'a',
      'createdAt',
      fieldsOf,
    ).config;
    expect(
      autoBindings(wired, ORDERS, [
        { globalField: 'created', panelField: 'createdAt' },
      ]),
    ).toEqual([]);
  });
});

describe('what reaches a panel and a tab', () => {
  const wired = bindPanel(
    {
      ...board,
      fields: [
        CREATED,
        { name: 'paid', label: 'Paid', kind: 'boolean' },
        { name: 'carrier', label: 'Carrier', kind: 'string' },
      ],
    },
    'created',
    'a',
    'createdAt',
    fieldsOf,
  ).config;
  const panel = (id: string) =>
    wired.panels.find(entry => entry.id === id) as DashboardPanel;

  it('says wired, by hand or on its own, or why not', () => {
    expect(filterReach(wired, panel('a'), ORDERS)).toEqual({
      created: { wired: true, field: 'createdAt', auto: false },
      paid: { wired: false, why: 'no-field' },
      carrier: { wired: false, why: 'unwired' },
    });
    expect(filterReach(wired, panel('c'), SHIPMENTS).created).toEqual({
      wired: true,
      field: 'createdAt',
      auto: true,
    });
  });

  it('answers for the wires alone while the fields are not known, and nothing for content', () => {
    expect(filterReach(wired, panel('b'), null)).toEqual({
      created: { wired: true, field: 'createdAt', auto: true },
    });
    expect(filterReach(wired, panel('note'), ORDERS)).toEqual({});
  });

  it('names the filters that reach something on a tab', () => {
    const panels = wired.panels.map(entry => ({
      tab: entry.tab ?? null,
      reach: filterReach(wired, entry, fieldsOf(entry as DashboardViewPanel)),
    }));
    expect([...filtersOnTab(panels, 'one')]).toEqual(['created']);
    expect([...filtersOnTab(panels, 'two')]).toEqual(['created']);
    expect([...filtersOnTab(panels, null)]).toEqual([]);
  });
});

describe('setting up the filters', () => {
  const empty = dashboardConfig();

  it('adds one, last, named the first filter-n not taken', () => {
    const first = addFilter(empty, { type: 'date', label: ' Created ' });
    expect(first?.name).toBe('filter-1');
    expect(first?.config.fields).toEqual([
      { name: 'filter-1', label: 'Created', kind: 'datetime' },
    ]);
    const second = addFilter(first!.config, { type: 'text', label: 'Region' });
    expect(
      second?.config.fields.map(field => [field.name, field.kind]),
    ).toEqual([
      ['filter-1', 'datetime'],
      ['filter-2', 'string'],
    ]);
    const full = dashboardConfig({
      fields: Array.from({ length: MAX_DASHBOARD_FILTERS }, (_, index) => ({
        name: `f${index}`,
        label: 'F',
        kind: 'string',
      })),
    });
    expect(addFilter(full, { type: 'text', label: 'One more' })).toBeNull();
  });

  it('renames one, a blank name not taken', () => {
    const named = renameFilter(board, 'created', ' Ordered ');
    expect(named.fields[0].label).toBe('Ordered');
    expect(renameFilter(named, 'created', 'Ordered')).toBe(named);
    expect(renameFilter(board, 'created', '  ')).toBe(board);
    expect(renameFilter(board, 'gone', 'x')).toBe(board);
  });

  it('retypes one: its value, list and wires go, and several stays where it can', () => {
    const wired = bindPanel(
      dashboardConfig({
        ...board,
        fields: [
          {
            name: 'region',
            label: 'Region',
            kind: 'string',
            multiple: true,
            default: ['CN'],
            options: [{ value: 'CN', label: 'China' }],
          },
        ],
      }),
      'region',
      'a',
      'warehouse',
      fieldsOf,
    ).config;
    const retyped = retypeFilter(wired, 'region', 'number');
    expect(retyped.fields).toEqual([
      { name: 'region', label: 'Region', kind: 'number', multiple: true },
    ]);
    expect(bindingsOf(retyped, 'a')).toEqual([]);
    expect(retypeFilter(wired, 'region', 'date').fields[0]).toEqual({
      name: 'region',
      label: 'Region',
      kind: 'datetime',
    });
    expect(retypeFilter(wired, 'region', 'text')).toBe(wired);
    expect(retypeFilter(wired, 'gone', 'text')).toBe(wired);
  });

  it('removes one and every wire to it', () => {
    const wired = bindPanel(
      board,
      'created',
      'a',
      'createdAt',
      fieldsOf,
    ).config;
    const removed = removeFilter(wired, 'created');
    expect(removed.fields).toEqual([]);
    expect(bindingsOf(removed, 'a')).toEqual([]);
    expect(bindingsOf(removed, 'c')).toEqual([]);
    expect(removeFilter(removed, 'created')).toBe(removed);
  });

  it('sets what it starts at, whether it is required, takes several, or picks from a list', () => {
    const week = { type: 'relative', amount: 7, unit: 'day' };
    const started = setFilterDefault(board, 'created', week);
    expect(started.fields[0].default).toEqual(week);
    expect(setFilterDefault(started, 'created', null).fields[0]).toEqual(
      CREATED,
    );
    expect(setFilterDefault(board, 'created', null)).toBe(board);

    const required = setFilterRequired(board, 'created', true);
    expect(required.fields[0].required).toBe(true);
    expect(setFilterRequired(required, 'created', true)).toBe(required);
    expect(setFilterRequired(required, 'created', false).fields[0]).toEqual(
      CREATED,
    );

    const options = [{ value: 'CN', label: 'China' }];
    const listed = setFilterOptions(board, 'created', options);
    expect(listed.fields[0].options).toEqual(options);
    expect(setFilterOptions(listed, 'created', null).fields[0]).toEqual(
      CREATED,
    );
    expect(setFilterOptions(board, 'created', null)).toBe(board);
  });

  it('turning several off keeps the first value it starts at', () => {
    const several = dashboardConfig({
      fields: [
        {
          name: 'region',
          label: 'Region',
          kind: 'string',
          multiple: true,
          default: ['CN', 'EU'],
        },
        {
          name: 'who',
          label: 'Who',
          kind: 'reference',
          multiple: true,
          default: { items: [{ id: 'a' }, { id: 'b' }] },
        },
        {
          name: 'one',
          label: 'One',
          kind: 'reference',
          multiple: true,
          default: { items: [{ id: 'a' }] },
        },
        { name: 'paid', label: 'Paid', kind: 'string', multiple: true },
      ],
    });
    let single = several;
    for (const name of ['region', 'who', 'one', 'paid'])
      single = setFilterMultiple(single, name, false);
    expect(single.fields).toEqual([
      { name: 'region', label: 'Region', kind: 'string', default: ['CN'] },
      {
        name: 'who',
        label: 'Who',
        kind: 'reference',
        default: { items: [{ id: 'a' }] },
      },
      {
        name: 'one',
        label: 'One',
        kind: 'reference',
        default: { items: [{ id: 'a' }] },
      },
      { name: 'paid', label: 'Paid', kind: 'string' },
    ]);
    expect(setFilterMultiple(single, 'paid', false)).toBe(single);
    expect(setFilterMultiple(single, 'paid', true).fields[3].multiple).toBe(
      true,
    );
  });

  it('moves one along the bar, within its ends', () => {
    const three = dashboardConfig({
      fields: ['a', 'b', 'c'].map(name => ({
        name,
        label: name,
        kind: 'string',
      })),
    });
    const names = (config: typeof three) =>
      config.fields.map(field => field.name);
    expect(names(moveFilter(three, 'a', 2))).toEqual(['b', 'c', 'a']);
    expect(names(moveFilter(three, 'c', -5))).toEqual(['c', 'a', 'b']);
    expect(moveFilter(three, 'b', 1)).toBe(three);
    expect(moveFilter(three, 'gone', 0)).toBe(three);
  });

  it('sets the time grouping, and takes it off', () => {
    const grouping = { units: ['DAY', 'WEEK'], default: 'DAY' } as const;
    const grouped = setTimeGrouping(board, {
      units: [...grouping.units],
      default: grouping.default,
    });
    expect(grouped.timeGrouping?.units).toEqual(['DAY', 'WEEK']);
    expect(setTimeGrouping(grouped, null).timeGrouping).toBeUndefined();
    expect(setTimeGrouping(board, null)).toBe(board);
  });

  it('takes the fixed scope out whole, leaving the empty tree (D23 Q16)', () => {
    const fixed = dashboardConfig({
      fixed: {
        op: 'or',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'EU' }],
      },
    });
    const removed = removeFixedScope(fixed);
    // The member stays: its being there says the board is read (D26 Q31).
    expect(removed.fixed).toEqual({ op: 'and', children: [] });
    expect(removed.fields).toBe(fixed.fields);
    expect(removeFixedScope(removed)).toBe(removed);
    expect(removeFixedScope(board)).toBe(board);
  });
});

describe('the list the wired fields declare', () => {
  const STATUS: FieldDefinition[] = [
    {
      name: 'status',
      label: 'Status',
      kind: 'enum',
      options: [
        { value: 'PENDING', label: 'Pending', tone: 'warning' },
        { value: 'SHIPPED', label: 'Shipped' },
      ],
    },
  ];
  const STATE: FieldDefinition[] = [
    {
      name: 'state',
      label: 'State',
      kind: 'enum',
      options: [
        { value: 'SHIPPED', label: 'Sent' },
        { value: 'LOST', label: 'Lost' },
      ],
    },
  ];
  const lists: PanelFields = panel => {
    if (panel.instanceId === 'status') return STATUS;
    if (panel.instanceId === 'state') return STATE;
    if (panel.instanceId === 'orders') return ORDERS;
    return null;
  };
  const wired = dashboardConfig({
    fields: [{ name: 'phase', label: 'Phase', kind: 'string' }],
    panels: [
      view('a', 'status', {
        bindings: [{ globalField: 'phase', panelField: 'status' }],
      }),
      view('b', 'state', {
        bindings: [{ globalField: 'phase', panelField: 'state', auto: true }],
      }),
      // A field without a list adds nothing to it.
      view('c', 'orders', {
        bindings: [{ globalField: 'phase', panelField: 'warehouse' }],
      }),
      // Not wired: not asked.
      view('d', 'status'),
      view('e', 'unknown', {
        bindings: [{ globalField: 'phase', panelField: 'x' }],
      }),
    ],
  });

  it('is every option of every wired field, the same code once, labels shown', () => {
    expect(wiredOptions(wired, 'phase', lists)).toEqual([
      { value: 'PENDING', label: 'Pending' },
      { value: 'SHIPPED', label: 'Shipped' },
      { value: 'LOST', label: 'Lost' },
    ]);
  });

  it('is nothing where no wired field declares one', () => {
    expect(wiredOptions(wired, 'gone', lists)).toBeNull();
    expect(
      wiredOptions(
        dashboardConfig({
          ...wired,
          panels: [wired.panels[2]],
        }),
        'phase',
        lists,
      ),
    ).toBeNull();
  });
});
