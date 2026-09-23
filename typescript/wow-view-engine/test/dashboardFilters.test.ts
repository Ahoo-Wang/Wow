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
 * A board's filters in the kernel (D22 F, batch C1): the five types and the
 * field kinds each reaches, the one condition a value stands for, what the
 * filters start at and take — a required one never blank — the condition a
 * panel runs under (a filter it is not wired to left out), how a value is
 * edited, and what admission says about the filters and the time grouping.
 */

import { AggregationDateUnit } from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  ANALYSIS_DATE_UNITS,
  DASHBOARD_FILTER_KINDS,
  DASHBOARD_FILTER_TYPES,
  MAX_DASHBOARD_FILTERS,
  admitFilters,
  builtinFieldKinds,
  defaultFilters,
  filterCondition,
  filterControlValue,
  filterEditor,
  filterOperatorOf,
  filterStoredValue,
  filterTypeOf,
  isBlankFilterValue,
  panelFilterTree,
  sameFilterType,
  validateDashboard,
  withFieldKinds,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type FieldKind,
  type Issue,
  type PanelReference,
} from '../src/index.js';
import { dashboardConfig, panelReference } from './fixtures.js';

const kinds = builtinFieldKinds;

function codes(issues: readonly Issue[]): string[] {
  return issues.map(found => found.code);
}

const REGION: DashboardField = {
  name: 'region',
  label: 'Region',
  kind: 'string',
};
const CREATED: DashboardField = {
  name: 'created',
  label: 'Created',
  kind: 'datetime',
};
const LAST_WEEK = { type: 'relative', amount: 7, unit: 'day' };

function viewPanel(overrides: Partial<DashboardPanel> = {}): DashboardPanel {
  return {
    id: 'orders',
    kind: 'view',
    instanceId: 'pending',
    bindings: [],
    layout: { x: 0, y: 0, w: 6, h: 4 },
    ...overrides,
  } as DashboardPanel;
}

function validate(
  config: DashboardViewConfig,
  references: Record<string, PanelReference> = { pending: panelReference() },
): Issue[] {
  return validateDashboard(
    config,
    'personal',
    new Map(Object.entries(references)),
    kinds,
  );
}

describe('the five filter types', () => {
  it('reads each field kind as the type it belongs to, and no other', () => {
    for (const type of DASHBOARD_FILTER_TYPES)
      for (const kind of DASHBOARD_FILTER_KINDS[type])
        expect(filterTypeOf(kind)).toBe(type);
    expect(filterTypeOf('array')).toBeNull();
    expect(filterTypeOf('search')).toBeNull();
    expect(filterTypeOf(undefined)).toBeNull();
  });

  it('wires a filter to any field of its type, and a kind outside the five to itself', () => {
    expect(sameFilterType('datetime', 'date')).toBe(true);
    expect(sameFilterType('string', 'enum')).toBe(true);
    expect(sameFilterType('string', 'number')).toBe(false);
    expect(sameFilterType('rounded', 'rounded')).toBe(true);
    expect(sameFilterType('rounded', 'number')).toBe(false);
  });

  it("offers every one of Wow's date units, coarsest first", () => {
    expect([...ANALYSIS_DATE_UNITS].sort()).toEqual(
      Object.values(AggregationDateUnit).sort(),
    );
    expect(ANALYSIS_DATE_UNITS[0]).toBe('YEAR');
  });
});

describe('the condition a filter stands for', () => {
  it('asks a date with a window, a yes or no with EQ, the rest with IN', () => {
    expect(filterOperatorOf(CREATED, kinds)).toBe('BETWEEN');
    expect(filterOperatorOf({ kind: 'boolean' }, kinds)).toBe('EQ');
    expect(filterOperatorOf(REGION, kinds)).toBe('IN');
    expect(filterOperatorOf({ kind: 'enum' }, kinds)).toBe('IN');
    expect(filterOperatorOf({ kind: 'reference' }, kinds)).toBe('IN');
    expect(filterOperatorOf({ kind: 'number' }, kinds)).toBe('IN');
    // Outside the five, the way the kind asks — and EQ for one no one knows.
    expect(filterOperatorOf({ kind: 'array' }, kinds)).toBe(
      kinds.get('array')?.defaultOperator,
    );
    expect(filterOperatorOf({ kind: 'nothing' }, kinds)).toBe('EQ');
  });

  it('narrows nothing while it is blank', () => {
    expect(filterCondition(REGION, undefined, kinds)).toBeNull();
    expect(filterCondition(REGION, [], kinds)).toBeNull();
    expect(filterCondition(CREATED, null, kinds)).toBeNull();
    expect(isBlankFilterValue(REGION, ['CN'], kinds)).toBe(false);
    // A kind the registry lacks is never blank: admission says why.
    expect(isBlankFilterValue({ ...REGION, kind: 'nothing' }, 'x', kinds)).toBe(
      false,
    );
    expect(filterCondition(REGION, ['CN'], kinds)).toEqual({
      field: 'region',
      operator: 'IN',
      value: ['CN'],
    });
  });
});

describe('what the filters hold', () => {
  const board = dashboardConfig({
    fields: [
      { ...REGION, default: ['CN'] },
      { ...CREATED, required: true, default: LAST_WEEK },
      { name: 'paid', label: 'Paid', kind: 'boolean' },
    ],
    timeGrouping: { units: ['DAY', 'WEEK', 'MONTH'], default: 'WEEK' },
  });

  it('starts at every default and the default unit', () => {
    expect(defaultFilters(board)).toEqual({
      values: { region: ['CN'], created: LAST_WEEK },
      unit: 'WEEK',
    });
    expect(admitFilters(board, null, kinds).filters).toEqual(
      defaultFilters(board),
    );
    expect(defaultFilters(dashboardConfig())).toEqual({ values: {} });
  });

  it('takes what a host asks, and a required filter never runs empty', () => {
    const { filters, refused } = admitFilters(
      board,
      { values: { region: ['EU'], paid: true }, unit: 'MONTH' },
      kinds,
    );

    expect(refused).toEqual([]);
    // `created` was not asked for, and it is required: its default holds.
    expect(filters).toEqual({
      values: { region: ['EU'], paid: true, created: LAST_WEEK },
      unit: 'MONTH',
    });
  });

  it('leaves out what the board does not take, and says so', () => {
    const { filters, refused } = admitFilters(
      board,
      {
        values: { gone: ['x'], paid: 'yes', region: [] },
        unit: 'YEAR' as never,
      },
      kinds,
    );

    expect(codes(refused)).toEqual([
      'dashboard.filter.unknown',
      'filter.value.expected-boolean',
      'dashboard.grouping.unit-unknown',
    ]);
    expect(refused[1].path).toEqual(['filters', 'paid']);
    // A blank value is a filter holding nothing, not a refusal.
    expect(filters).toEqual({ values: { created: LAST_WEEK }, unit: 'WEEK' });
  });

  it('refuses several values on a filter that takes one', () => {
    const { refused } = admitFilters(
      board,
      { values: { region: ['CN', 'EU'] } },
      kinds,
    );
    expect(codes(refused)).toEqual(['dashboard.field.not-multiple']);
    const several = dashboardConfig({
      fields: [{ ...REGION, multiple: true }],
    });
    expect(
      admitFilters(several, { values: { region: ['CN', 'EU'] } }, kinds)
        .refused,
    ).toEqual([]);
  });

  it('reads a stored values object that is no object as nothing asked', () => {
    const { filters } = admitFilters(
      board,
      { values: 'nonsense' as never },
      kinds,
    );
    expect(filters.values).toEqual({ created: LAST_WEEK });
  });
});

describe('the condition one panel runs under', () => {
  const board = dashboardConfig({
    fields: [REGION, CREATED, { name: 'paid', label: 'Paid', kind: 'boolean' }],
  });
  const filters = {
    values: { region: ['CN'], created: LAST_WEEK, paid: true },
  };

  it("is each wired filter's condition, in the panel's own field names", () => {
    expect(
      panelFilterTree(
        board,
        filters,
        [
          { globalField: 'region', panelField: 'warehouse' },
          { globalField: 'created', panelField: 'createdAt', auto: true },
        ],
        kinds,
      ),
    ).toEqual({
      op: 'and',
      children: [
        { field: 'warehouse', operator: 'IN', value: ['CN'] },
        { field: 'createdAt', operator: 'BETWEEN', value: LAST_WEEK },
      ],
    });
  });

  it('leaves out a filter the panel is not wired to, and one holding nothing', () => {
    expect(
      panelFilterTree(
        board,
        { values: { region: ['CN'] } },
        [
          { globalField: 'created', panelField: 'createdAt' },
          { globalField: 'gone', panelField: 'x' },
          { globalField: 'region', panelField: 'warehouse' },
          // One filter becomes one condition; a second wire is not a second.
          { globalField: 'region', panelField: 'status' },
        ],
        kinds,
      ),
    ).toEqual({
      op: 'and',
      children: [{ field: 'warehouse', operator: 'IN', value: ['CN'] }],
    });
    expect(panelFilterTree(board, filters, [], kinds)).toBeNull();
  });
});

describe('how a filter is edited', () => {
  it("uses the condition editor's own controls, by type", () => {
    expect(filterEditor(CREATED, undefined, kinds)).toMatchObject({
      input: 'dateRange',
    });
    expect(filterEditor(CREATED, LAST_WEEK, kinds)).toMatchObject({
      input: 'relativeDate',
    });
    expect(filterEditor({ ...REGION, kind: 'boolean' }, true, kinds)).toEqual({
      input: 'boolean',
    });
    expect(filterEditor(REGION, undefined, kinds)).toEqual({
      input: 'text',
      multiple: false,
    });
    expect(
      filterEditor({ ...REGION, multiple: true }, undefined, kinds),
    ).toEqual({ input: 'text', multiple: true });
    const options = [{ value: 'CN', label: 'China' }];
    expect(filterEditor({ ...REGION, options }, undefined, kinds)).toEqual({
      input: 'select',
      multiple: false,
      options,
    });
    expect(filterEditor({ ...REGION, kind: 'enum' }, undefined, kinds)).toEqual(
      { input: 'select', multiple: false, options: [] },
    );
    expect(
      filterEditor(
        { ...REGION, kind: 'reference', remote: 'customers', options },
        undefined,
        kinds,
      ),
    ).toEqual({ input: 'remote', multiple: false, remote: 'customers' });
    expect(
      filterEditor({ ...REGION, kind: 'number' }, undefined, kinds),
    ).toEqual({ input: 'number', multiple: false });
  });

  it('asks a kind outside the five for its own control, and none for an unknown one', () => {
    const rounded: FieldKind = {
      ...(kinds.get('number') as FieldKind),
      id: 'rounded',
      editor: () => ({ input: 'number', range: true }),
    };
    expect(
      filterEditor(
        { ...REGION, kind: 'rounded' },
        undefined,
        withFieldKinds(kinds, [rounded]),
      ),
    ).toEqual({ input: 'number', range: true });
    expect(filterEditor({ ...REGION, kind: 'nothing' }, 1, kinds)).toEqual({
      input: 'none',
    });
  });

  it('edits a one-value filter as that value, and stores it as a list', () => {
    expect(filterControlValue(REGION, ['CN'])).toBe('CN');
    expect(filterControlValue(REGION, undefined)).toBeNull();
    expect(filterStoredValue(REGION, 'EU', kinds)).toEqual(['EU']);
    expect(filterStoredValue(REGION, '', kinds)).toBeNull();
    expect(filterStoredValue(REGION, null, kinds)).toBeNull();
    // A list handed back keeps the last value picked.
    expect(filterStoredValue(REGION, ['CN', 'EU'], kinds)).toEqual(['EU']);
  });

  it('edits a many-value filter as its list', () => {
    const many = { ...REGION, multiple: true as const };
    expect(filterControlValue(many, undefined)).toEqual([]);
    expect(filterControlValue(many, ['CN'])).toEqual(['CN']);
    expect(filterStoredValue(many, ['CN', 'EU'], kinds)).toEqual(['CN', 'EU']);
    expect(filterStoredValue(many, 'CN', kinds)).toBeNull();
  });

  it('keeps an id filter a reference, one item where it takes one', () => {
    const ids: DashboardField = { ...REGION, kind: 'reference' };
    const two = {
      items: [
        { id: 'c-1', label: 'Ada' },
        { id: 'c-2', label: 'Bo' },
      ],
    };
    expect(filterControlValue(ids, undefined)).toEqual({ items: [] });
    expect(filterStoredValue(ids, two, kinds)).toEqual({
      items: [{ id: 'c-2', label: 'Bo' }],
    });
    expect(filterStoredValue({ ...ids, multiple: true }, two, kinds)).toEqual(
      two,
    );
    expect(filterStoredValue(ids, { items: [] }, kinds)).toBeNull();
  });

  it('hands a date or a yes-or-no through as it is', () => {
    expect(filterControlValue(CREATED, undefined)).toBeNull();
    expect(filterControlValue(CREATED, LAST_WEEK)).toEqual(LAST_WEEK);
    expect(filterStoredValue(CREATED, LAST_WEEK, kinds)).toEqual(LAST_WEEK);
    expect(filterStoredValue(CREATED, null, kinds)).toBeNull();
  });
});

describe('admission of the filters', () => {
  it('needs a default for a required filter, since it never runs empty', () => {
    const issues = validate(
      dashboardConfig({ fields: [{ ...REGION, required: true }] }),
    );
    expect(codes(issues)).toEqual(['dashboard.field.required-no-default']);
    expect(issues[0].path).toEqual(['fields', 0, 'default']);
    expect(
      validate(
        dashboardConfig({
          fields: [{ ...REGION, required: true, default: [] }],
        }),
      ).map(found => found.code),
    ).toEqual(['dashboard.field.required-no-default']);
    expect(
      validate(
        dashboardConfig({
          fields: [{ ...REGION, required: true, default: ['CN'] }],
        }),
      ),
    ).toEqual([]);
  });

  it('judges a default as a value of its filter', () => {
    const issues = validate(
      dashboardConfig({
        fields: [
          { ...CREATED, default: 'yesterday' },
          { ...REGION, default: ['CN', 'EU'] },
        ],
      }),
    );
    expect(codes(issues)).toEqual([
      'filter.value.expected-date',
      'dashboard.field.not-multiple',
    ]);
    expect(issues[0].path).toEqual(['fields', 0, 'default']);
  });

  it('writes its switches as true or not at all, and says a list is empty', () => {
    const issues = validate(
      dashboardConfig({
        fields: [
          {
            ...REGION,
            required: false,
            multiple: 'yes',
            options: [],
          } as unknown as DashboardField,
          { ...CREATED, options: 'x' } as unknown as DashboardField,
        ],
      }),
    );
    expect(
      issues.map(found => [found.code, found.severity, found.path]),
    ).toEqual([
      ['dashboard.shape.invalid', 'error', ['fields', 0, 'required']],
      ['dashboard.shape.invalid', 'error', ['fields', 0, 'multiple']],
      ['dashboard.field.options-empty', 'warning', ['fields', 0, 'options']],
      ['dashboard.shape.invalid', 'error', ['fields', 1, 'options']],
    ]);
  });

  it('holds a board to a number of filters', () => {
    const fields = Array.from(
      { length: MAX_DASHBOARD_FILTERS + 1 },
      (_, index) => ({ ...REGION, name: `f${index}` }),
    );
    expect(codes(validate(dashboardConfig({ fields })))).toEqual([
      'dashboard.fields.too-many',
    ]);
  });

  it('judges a default through the field it reaches on each panel', () => {
    const issues = validate(
      dashboardConfig({
        fields: [{ ...REGION, kind: 'number', default: [7] }],
        panels: [
          viewPanel({
            bindings: [{ globalField: 'region', panelField: 'amount' }],
          }),
        ],
      }),
    );
    expect(issues).toEqual([]);

    const narrowed = panelReference(
      {},
      {
        fields: [
          {
            name: 'warehouse',
            label: 'Warehouse',
            kind: 'enum',
            options: [{ value: 'CN', label: 'China' }],
          },
        ],
      },
    );
    const refused = validate(
      dashboardConfig({
        fields: [{ ...REGION, default: ['EU'] }],
        panels: [
          viewPanel({
            bindings: [{ globalField: 'region', panelField: 'warehouse' }],
          }),
        ],
      }),
      { pending: narrowed },
    );
    // `EU` is not one of the warehouses this panel's field offers.
    expect(refused[0].path.slice(0, 3)).toEqual(['panels', 0, 'filter']);
  });
});

describe('admission of the wiring', () => {
  const withCreated = panelReference(
    {},
    {
      fields: [
        { name: 'warehouse', label: 'Warehouse', kind: 'enum', options: [] },
        { name: 'createdAt', label: 'Created', kind: 'date' },
        { name: 'amount', label: 'Amount', kind: 'number' },
      ],
    },
  );

  it('wires a filter to any field of its type', () => {
    const issues = validate(
      dashboardConfig({
        fields: [REGION, CREATED],
        panels: [
          viewPanel({
            bindings: [
              { globalField: 'region', panelField: 'warehouse' },
              { globalField: 'created', panelField: 'createdAt', auto: true },
            ],
          }),
        ],
      }),
      { pending: withCreated },
    );
    expect(issues).toEqual([]);
  });

  it('refuses a wire to a field of another type, and an auto mark that is not true', () => {
    const issues = validate(
      dashboardConfig({
        fields: [REGION],
        panels: [
          viewPanel({
            bindings: [
              {
                globalField: 'region',
                panelField: 'amount',
                auto: 'yes',
              } as never,
            ],
          }),
        ],
      }),
      { pending: withCreated },
    );
    expect(issues.map(found => [found.code, found.path])).toEqual([
      ['dashboard.shape.invalid', ['panels', 0, 'bindings', 0, 'auto']],
      ['dashboard.binding.kind-mismatch', ['panels', 0, 'bindings', 0]],
    ]);
  });
});

describe('admission of the time grouping', () => {
  const grouped = (timeGrouping: unknown) =>
    codes(
      validate(
        dashboardConfig({
          timeGrouping: timeGrouping as DashboardViewConfig['timeGrouping'],
        }),
      ),
    );

  it('offers some units, each a date unit once, and a default among them', () => {
    expect(grouped({ units: ['DAY', 'WEEK'], default: 'DAY' })).toEqual([]);
    expect(grouped(undefined)).toEqual([]);
    expect(grouped({ units: [], default: 'DAY' })).toEqual([
      'dashboard.grouping.units-empty',
    ]);
    expect(
      grouped({ units: ['DAY', 'FORTNIGHT', 'DAY'], default: 'MONTH' }),
    ).toEqual([
      'dashboard.grouping.unit-unknown',
      'dashboard.grouping.unit-duplicate',
      'dashboard.grouping.unit-unknown',
    ]);
    expect(grouped('weekly')).toEqual(['dashboard.shape.invalid']);
  });
});
