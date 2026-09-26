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
 * A time since another moment as a condition (N3, Wow's `EXPRESSION` over a
 * `DATE_DIFF`): 「发货时间 距 付款时间 > 48 小时」 — a time field's own
 * condition, admitted against the fields beside it, compiled, read out,
 * gated by the descriptor and written in the condition editor.
 */

import {
  aggregation,
  ComparisonOperator,
  DateDiffUnit,
  filter as wow,
  FilterOperator,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  compileFilter,
  describeFilter,
  durationFrom,
  filterFields,
  isDurationFilterValue,
  MemoryViewStore,
  relativeTree,
  validateFilter,
  ViewEngine,
  type DataViewDefinition,
  type FieldDefinition,
  type FilterLeaf,
  type FilterTree,
  type ViewRuntime,
  type ViewSource,
} from '../src/index.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { withCanonicalNames } from '../src/capabilities/aliases.js';
import { useFilterEditor } from '../src/react/index.js';
import { AppliedBar } from '../src/ui/AppliedBar.js';
import { FilterPanel } from '../src/ui/index.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

afterEach(cleanup);

const FIELDS: FieldDefinition[] = [
  { name: 'paidAt', label: 'Paid', kind: 'datetime' },
  { name: 'shippedAt', label: 'Shipped', kind: 'datetime' },
  { name: 'status', label: 'Status', kind: 'string' },
  {
    name: 'lines',
    label: 'Lines',
    kind: 'elementMatch',
    elements: [
      { name: 'at', label: 'Line at', kind: 'datetime' },
      { name: 'done', label: 'Line done', kind: 'datetime' },
    ],
  },
];

const LATE: FilterLeaf = {
  field: 'shippedAt',
  operator: 'EXPRESSION',
  value: { from: 'paidAt', comparison: 'GT', value: 48, unit: 'HOUR' },
};
const tree = (...children: FilterTree['children']): FilterTree => ({
  op: 'and',
  children,
});
const codes = (tree: FilterTree, fields = FIELDS) =>
  validateFilter(fields, tree, builtinFieldKinds).map(found => found.code);

describe('a time since another moment, in the kernel', () => {
  it('is a time kind’s condition, whole only once its earlier time is picked', () => {
    expect(builtinFieldKinds.get('datetime')!.operators).toContain(
      'EXPRESSION',
    );
    expect(builtinFieldKinds.get('date')!.operators).toContain('EXPRESSION');
    expect(isDurationFilterValue(LATE.value)).toBe(true);
    expect(isDurationFilterValue({ ...(LATE.value as object), from: '' })).toBe(
      false,
    );
    // Still being written: nothing to admit, nothing to compile.
    expect(codes(tree({ ...LATE, value: null }))).toEqual([]);
    expect(
      compileFilter(
        FIELDS,
        tree({ ...LATE, value: { from: '', comparison: 'GT' } }),
        builtinFieldKinds,
        { now: new Date(0), timeZone: 'UTC' },
      ),
    ).toEqual(
      compileFilter(FIELDS, tree(), builtinFieldKinds, {
        now: new Date(0),
        timeZone: 'UTC',
      }),
    );
  });

  it('is admitted against the times beside it', () => {
    expect(codes(tree(LATE))).toEqual([]);
    const value = LATE.value as object;
    expect(codes(tree({ ...LATE, value: { ...value, value: 'x' } }))).toEqual([
      'filter.value.expected-duration',
    ]);
    expect(
      codes(tree({ ...LATE, value: { ...value, from: 'shippedAt' } })),
    ).toEqual(['filter.value.duration-same-time']);
    expect(codes(tree({ ...LATE, value: { ...value, from: 'gone' } }))).toEqual(
      ['filter.value.duration-from-unknown'],
    );
    expect(
      codes(tree({ ...LATE, value: { ...value, from: 'status' } })),
    ).toEqual(['filter.value.duration-from-not-time']);
  });

  it('asks about the gap, so it stands beside a condition on the field’s own value', () => {
    expect(
      codes(
        tree(LATE, {
          field: 'shippedAt',
          operator: 'BETWEEN',
          value: { type: 'preset', preset: 'thisMonth' },
        }),
      ),
    ).toEqual([]);
  });

  it('is kept out of one entry of an element, as Wow keeps it', () => {
    expect(
      codes(
        tree({
          field: 'lines',
          operator: 'ELEMENT_MATCH',
          value: tree({
            field: 'lines.done',
            operator: 'EXPRESSION',
            value: {
              from: 'lines.at',
              comparison: 'GT',
              value: 1,
              unit: 'DAY',
            },
          }) as never,
        }),
      ),
    ).toContain('filter.element.duration');
  });

  it('compiles to an EXPRESSION over the time between the two', () => {
    expect(
      compileFilter(FIELDS, tree(LATE), builtinFieldKinds, {
        now: new Date(0),
        timeZone: 'UTC',
      }),
    ).toEqual(
      wow.expression(
        aggregation.dateDiff('paidAt', 'shippedAt', DateDiffUnit.HOUR),
        ComparisonOperator.GT,
        48,
      ),
    );
    expect(
      (
        compileFilter(FIELDS, tree(LATE), builtinFieldKinds, {
          now: new Date(0),
          timeZone: 'UTC',
        }) as { op: string }
      ).op,
    ).toBe(FilterOperator.EXPRESSION);
  });

  it('reads out as the gap, naming both times by their labels', () => {
    const [item] = describeFilter(FIELDS, tree(LATE), builtinFieldKinds);
    expect(item).toMatchObject({
      label: 'Shipped',
      value: {
        kind: 'duration',
        from: 'Paid',
        comparison: 'GT',
        amount: 48,
        unit: 'HOUR',
      },
    });
    expect(item!.text).toBe('Shipped since Paid GT 48 hour');
  });

  it('names its earlier time wherever a tree’s fields are read or renamed', () => {
    expect(filterFields(tree(LATE))).toEqual(['shippedAt', 'paidAt']);
    expect(durationFrom(LATE)).toBe('paidAt');
    expect(durationFrom({ ...LATE, operator: 'GT' })).toBeUndefined();
    const relative = relativeTree(
      tree({
        ...LATE,
        field: 'lines.done',
        value: { ...(LATE.value as object), from: 'lines.at' },
      }),
      'lines.',
    );
    expect(relative.children[0]).toMatchObject({
      field: 'done',
      value: { from: 'at' },
    });
    const renamed = withCanonicalNames(recordConfig({ filter: tree(LATE) }), {
      paidAt: 'state.paidAt',
    });
    expect(renamed.filter.children[0]).toMatchObject({
      value: { from: 'state.paidAt' },
    });
  });
});

describe('narrowing by the descriptor', () => {
  function definition(): DataViewDefinition {
    return ordersDefinition({
      fields: [...ordersDefinition().fields, ...FIELDS.slice(0, 2)],
    });
  }
  function described(expressions: boolean): QueryModelDescriptor {
    const base = ordersDescriptor();
    return {
      ...base,
      record: {
        ...base.record,
        rootOperators: expressions
          ? [...base.record.rootOperators, FilterOperator.EXPRESSION]
          : base.record.rootOperators.filter(
              operator => operator !== FilterOperator.EXPRESSION,
            ),
      },
      fields: [
        ...base.fields,
        describedField('paidAt', {
          filter: { operators: [FilterOperator.BETWEEN] },
        }),
        describedField('shippedAt', {
          filter: { operators: [FilterOperator.BETWEEN] },
        }),
      ],
    };
  }
  const operators = (expressions: boolean) =>
    narrowDefinition(
      definition(),
      described(expressions),
      builtinFieldKinds,
    ).definition.fields.find(field => field.name === 'shippedAt')?.operators;

  it('keeps it where the entry lists EXPRESSION among its root operators', () => {
    expect(operators(true)).toEqual(['BETWEEN', 'EXPRESSION']);
  });

  it('takes it away where the entry keeps expensive operators off', () => {
    expect(operators(false)).toEqual(['BETWEEN']);
  });
});

function Bar({ runtime }: { runtime: ViewRuntime }) {
  const filter = useFilterEditor(runtime);
  return <AppliedBar filter={filter} asked />;
}

describe('a time since another moment, in the condition editor', () => {
  async function open(fields: FieldDefinition[], filter?: FilterTree) {
    const source: ViewSource = testSource();
    const engine = new ViewEngine({
      definitions: [
        ordersDefinition({
          fields: [...ordersDefinition().fields, ...fields],
          views: [
            {
              id: 'late',
              title: 'Late',
              config: recordConfig({
                filter: filter ?? tree(),
                filterMode: 'advanced',
              }),
            },
          ],
        }),
      ],
      store: new MemoryViewStore({ instances: [] }),
      resolveSource: () => source,
    });
    const runtime = await engine.open('system:orders:late');
    return { engine, runtime, source };
  }

  it('is written as the earlier time, a comparison, an amount and a unit', async () => {
    const user = userEvent.setup();
    const { engine, runtime, source } = await open(FIELDS.slice(0, 2), {
      op: 'and',
      children: [{ field: 'shippedAt', operator: 'EXPRESSION', value: null }],
    });
    function Panel() {
      const filter = useFilterEditor(runtime);
      return <FilterPanel filter={filter} />;
    }
    render(<Panel />);
    const pill = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="filter-condition"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    const from = within(pill).getByRole('combobox', {
      name: 'Since which time',
    });
    await user.click(from);
    await user.click(await screen.findByRole('option', { name: 'Paid' }));
    await waitFor(() =>
      expect(
        (
          (runtime.getSnapshot().draft as { filter: FilterTree }).filter
            .children[0] as FilterLeaf
        ).value,
      ).toEqual({ from: 'paidAt', comparison: 'GT', value: 0, unit: 'HOUR' }),
    );
    fireEvent.change(within(pill).getByRole('textbox', { name: /Amount$/ }), {
      target: { value: '48' },
    });
    await user.click(within(pill).getByRole('combobox', { name: 'Unit' }));
    await user.click(await screen.findByRole('option', { name: 'Days' }));
    await user.click(
      within(pill).getByRole('combobox', { name: 'Comparison' }),
    );
    await user.click(await screen.findByRole('option', { name: '≥' }));
    await waitFor(() =>
      expect(
        (
          (runtime.getSnapshot().draft as { filter: FilterTree }).filter
            .children[0] as FilterLeaf
        ).value,
      ).toEqual({ from: 'paidAt', comparison: 'GTE', value: 48, unit: 'DAY' }),
    );

    runtime.apply();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    const calls = vi.mocked(source.paged).mock.calls;
    expect(calls[calls.length - 1]?.[0].filter).toEqual(
      wow.expression(
        aggregation.dateDiff('paidAt', 'shippedAt', DateDiffUnit.DAY),
        ComparisonOperator.GTE,
        48,
      ),
    );
    engine.dispose();
  });

  it('reads in the applied bar as the gap', async () => {
    const { engine, runtime, source } = await open(
      FIELDS.slice(0, 2),
      tree(LATE),
    );
    runtime.apply();
    await waitFor(() => expect(source.paged).toHaveBeenCalled());
    render(
      <MessagesProvider messages={zhCN}>
        <Bar runtime={runtime} />
      </MessagesProvider>,
    );
    await waitFor(() =>
      expect(screen.getByText('Shipped 距 Paid > 48 小时')).toBeDefined(),
    );
    engine.dispose();
  });

  it('is offered only where another time stands beside the field', async () => {
    const { engine, runtime } = await open(FIELDS.slice(0, 1));
    function Operators() {
      const filter = useFilterEditor(runtime);
      return <output>{filter.operatorsFor('paidAt').join(',')}</output>;
    }
    render(<Operators />);
    await waitFor(() =>
      expect(screen.getByRole('status').textContent).not.toContain(
        'EXPRESSION',
      ),
    );
    engine.dispose();

    const second = await open(FIELDS.slice(0, 2));
    function Both() {
      const filter = useFilterEditor(second.runtime);
      return <output>{filter.operatorsFor('paidAt').join(',')}</output>;
    }
    cleanup();
    render(<Both />);
    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toContain('EXPRESSION'),
    );
    second.engine.dispose();
  });
});
