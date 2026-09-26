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
 * A board's date filter shows what the board reads (2026-09-26 review,
 * P1-1 and P1-10): a required date turned to 「指定日期」 with no day yet
 * leaves its panels saying so rather than showing the old answer; a
 * one-day filter offers only days; a one-day range reads as that day; and
 * an optional date holding nothing says each panel reads its own dates.
 */

import {
  AggregationDateUnit,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  admitFilters,
  isOneDayValue,
  setFilterOneDay,
  validateFilterFields,
  type DashboardField,
  type DashboardPanel,
  type DashboardViewConfig,
  type DataViewDefinition,
  type ViewInstance,
} from '../src/index.js';
import {
  DashboardWorkbench,
  ViewSurface,
  defaultMessages,
} from '../src/ui/index.js';

const OWN = defaultMessages['label.filters.date-own'];
import { DateValue } from '../src/ui/filter/inputs/date.js';
import {
  analysisConfig,
  dashboardConfig,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const YESTERDAY = { type: 'preset', preset: 'yesterday' } as const;

function orders(): DataViewDefinition {
  const base = ordersDefinition();
  return {
    ...base,
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
    ],
    analysis: {
      count: true,
      fields: [
        ...(base.analysis?.fields ?? []),
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
      ],
    },
  };
}

const views: ViewInstance[] = [
  {
    id: 'trend',
    definitionId: 'orders',
    title: 'Orders by day',
    scope: 'shared',
    revision: 'r1',
    config: analysisConfig({
      groups: [
        {
          alias: 'createdAt',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'DAY',
        },
      ],
    }),
  },
  {
    id: 'list',
    definitionId: 'orders',
    title: 'Order list',
    scope: 'shared',
    revision: 'r1',
    config: recordConfig(),
  },
];

const DAY_FILTER: DashboardField = {
  name: 'day',
  label: 'Day',
  kind: 'datetime',
  required: true,
  default: YESTERDAY,
  oneDay: true,
};

function board(field: DashboardField = DAY_FILTER): DashboardViewConfig {
  return dashboardConfig({
    fields: [field],
    panels: [
      {
        id: 'a',
        kind: 'view',
        instanceId: 'trend',
        bindings: [{ globalField: field.name, panelField: 'createdAt' }],
        layout: { x: 0, y: 0, w: 12, h: 4 },
        title: 'Trend',
      },
      {
        id: 'b',
        kind: 'view',
        instanceId: 'list',
        bindings: [],
        layout: { x: 12, y: 0, w: 12, h: 4 },
        title: 'List',
      },
    ] as DashboardPanel[],
  });
}

function setup(config: DashboardViewConfig = board()) {
  const engine = new ViewEngine({
    definitions: [orders(), overviewDefinition()],
    store: new MemoryViewStore({
      instances: [
        ...views,
        {
          id: 'board',
          definitionId: 'overview',
          title: 'Operations',
          scope: 'personal',
          revision: '1',
          config,
        },
      ],
    }),
    resolveSource: () => testSource(),
  });
  render(
    <DashboardWorkbench
      engine={engine}
      definitionId="overview"
      instanceId="board"
    />,
  );
}

async function pick(combobox: string, option: string) {
  await userEvent.click(
    await screen.findByRole('combobox', { name: combobox }),
  );
  await userEvent.click(await screen.findByRole('option', { name: option }));
}

const optionsOf = async (combobox: string) => {
  await userEvent.click(
    await screen.findByRole('combobox', { name: combobox }),
  );
  const names = (await screen.findAllByRole('option')).map(
    option => option.textContent,
  );
  await userEvent.keyboard('{Escape}');
  return names;
};

const awaiting = () =>
  document.querySelectorAll('[data-slot="panel-awaiting-date"]');

describe('a one-day value', () => {
  it('is a day that has come by name, or one calendar day', () => {
    expect(isOneDayValue(YESTERDAY)).toBe(true);
    expect(isOneDayValue({ type: 'preset', preset: 'tomorrow' })).toBe(false);
    expect(isOneDayValue({ type: 'preset', preset: 'thisMonth' })).toBe(false);
    expect(
      isOneDayValue({ type: 'relative', amount: 1, unit: 'day' } as never),
    ).toBe(false);
    expect(
      isOneDayValue({ type: 'absolute', from: '2026-09-21', to: '2026-09-21' }),
    ).toBe(true);
    expect(isOneDayValue({ type: 'absolute', from: '2026-09-21' })).toBe(true);
    expect(
      isOneDayValue({ type: 'absolute', from: '2026-09-20', to: '2026-09-21' }),
    ).toBe(false);
    expect(isOneDayValue({ type: 'absolute', from: '2026-09-21T08:00' })).toBe(
      false,
    );
    expect(
      isOneDayValue({
        type: 'absolute',
        from: '2026-09-21',
        timeZone: 'UTC',
      } as never),
    ).toBe(false);
    expect(isOneDayValue(null)).toBe(false);
  });

  it('is what a one-day filter admits, and its config declares', () => {
    const kinds = builtinFieldKinds;
    const { filters, refused } = admitFilters(
      board(),
      {
        values: {
          day: { type: 'absolute', from: '2026-09-01', to: '2026-09-21' },
        },
      },
      kinds,
    );
    expect(refused.map(found => found.code)).toEqual([
      'dashboard.field.not-one-day',
    ]);
    expect(filters.values.day).toEqual(YESTERDAY);
    const limits = DEFAULT_RUNTIME_LIMITS;
    const codes = (field: DashboardField) =>
      validateFilterFields(board(field), kinds, limits).map(
        found => found.code,
      );
    expect(codes(DAY_FILTER)).toEqual([]);
    expect(
      codes({ ...DAY_FILTER, default: { type: 'preset', preset: 'thisWeek' } }),
    ).toEqual(['dashboard.field.not-one-day']);
    expect(
      codes({ name: 'day', label: 'Day', kind: 'string', oneDay: true }),
    ).toEqual(['dashboard.field.one-day-not-date']);
    expect(codes({ ...DAY_FILTER, oneDay: 'yes' as never })).toEqual([
      'dashboard.shape.invalid',
    ]);
  });

  it('is switched on, and a default that is no day goes with it', () => {
    const plain: DashboardField = {
      name: 'day',
      label: 'Day',
      kind: 'datetime',
      default: { type: 'preset', preset: 'thisMonth' },
    };
    const on = setFilterOneDay(board(plain), 'day', true).fields[0];
    expect(on).toEqual({
      name: 'day',
      label: 'Day',
      kind: 'datetime',
      oneDay: true,
    });
    const kept = setFilterOneDay(
      board({ ...plain, default: YESTERDAY }),
      'day',
      true,
    );
    expect(kept.fields[0].default).toEqual(YESTERDAY);
    expect(
      setFilterOneDay(kept, 'day', false).fields[0].oneDay,
    ).toBeUndefined();
    expect(setFilterOneDay(kept, 'day', true)).toBe(kept);
  });
});

describe('the date control', () => {
  function control(props: Partial<Parameters<typeof DateValue>[0]> = {}) {
    const onChange = vi.fn();
    render(
      <ViewSurface locale="en-US" timeZone="UTC">
        <DateValue
          value={null}
          onChange={onChange}
          label="Day"
          range
          withTime={false}
          {...props}
        />
      </ViewSurface>,
    );
    return onChange;
  }

  it('offers a one-day filter a day off the calendar or a day by name', async () => {
    control({ value: YESTERDAY, oneDay: true, required: true });
    expect(await optionsOf('Day kind')).toEqual(['Specific dates', 'A period']);
    expect(await optionsOf('Day period')).toEqual([
      'today',
      'yesterday',
      'the day before yesterday',
    ]);
  });

  it('writes a picked day as both ends of a one-day filter', async () => {
    const onChange = control({
      value: { type: 'absolute', from: '2026-09-20', to: '2026-09-20' },
      oneDay: true,
      required: true,
    });
    // One day, said once.
    const trigger = screen.getByRole('button', { name: 'Day' });
    expect(trigger.textContent).toBe('Sep 20, 2026');
    await userEvent.click(trigger);
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: /September 21/,
      }),
    );
    expect(onChange).toHaveBeenLastCalledWith({
      type: 'absolute',
      from: '2026-09-21',
      to: '2026-09-21',
    });
  });

  it('reads a range of one day as that day, and a longer one as two', () => {
    control({
      value: { type: 'absolute', from: '2026-09-20', to: '2026-09-20' },
    });
    expect(screen.getByRole('button', { name: 'Day' }).textContent).toBe(
      'Sep 20, 2026',
    );
    cleanup();
    control({
      value: { type: 'absolute', from: '2026-09-20', to: '2026-09-21' },
    });
    expect(screen.getByRole('button', { name: 'Day' }).textContent).toBe(
      'Sep 20, 2026 – Sep 21, 2026',
    );
  });

  it('says a value of nothing in its own words, and goes back to it', async () => {
    const onChange = control({
      blank: OWN,
      value: YESTERDAY,
    });
    await pick('Day kind', OWN);
    expect(onChange).toHaveBeenLastCalledWith(null);
    cleanup();
    control({ blank: OWN });
    expect(
      screen.getByRole('combobox', { name: 'Day kind' }).textContent,
    ).toContain(OWN);
    expect(screen.queryByRole('button', { name: 'Day' })).toBeNull();
  });

  it('tells whether it waits for a day, and stops when it goes', async () => {
    const onAwaiting = vi.fn();
    control({ value: YESTERDAY, required: true, onAwaiting });
    expect(onAwaiting).toHaveBeenLastCalledWith(false);
    await pick('Day kind', 'Specific dates');
    expect(onAwaiting).toHaveBeenLastCalledWith(true);
    cleanup();
    expect(onAwaiting).toHaveBeenLastCalledWith(false);
  });
});

describe('a board whose date waits for a day', () => {
  it('has the panels wired to it say so, and show numbers once a day is picked', async () => {
    setup();
    await screen.findByRole('combobox', { name: 'Day kind' });
    expect(awaiting()).toHaveLength(0);
    await pick('Day kind', 'Specific dates');
    await waitFor(() => expect(awaiting()).toHaveLength(1));
    // Only the panel the filter reaches; the list is not narrowed by it.
    expect(
      awaiting()[0].closest('[data-slot="dashboard-panel"]')?.textContent,
    ).toContain('Trend');
    expect(awaiting()[0].textContent).toContain('Pick a date');
    await pick('Day kind', 'A period');
    await waitFor(() => expect(awaiting()).toHaveLength(0));
  });

  it('says an optional date holding nothing reads each panel’s own dates', async () => {
    setup(board({ name: 'day', label: 'Day', kind: 'datetime' }));
    const shape = await screen.findByRole('combobox', { name: 'Day kind' });
    expect(shape.textContent).toContain(OWN);
  });

  it('offers the one-day switch in a date filter’s settings', async () => {
    setup();
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    await userEvent.click(
      await screen.findByRole('button', { name: 'Settings of “Day”' }),
    );
    const toggle = await screen.findByRole('checkbox', {
      name: /One day only/,
    });
    expect(toggle.getAttribute('aria-checked')).toBe('true');
    await userEvent.click(toggle);
    await waitFor(() =>
      expect(toggle.getAttribute('aria-checked')).toBe('false'),
    );
  });
});
