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

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
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
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  DataWorkbench,
  defaultMessages,
  formatMessage,
  zhCN,
  type ViewMessages,
} from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/**
 * A metric is *referred to* here, not titled: 「只保留」 names one where no
 * summary control stands beside it, so it says the same sentence the result
 * column is headed with — two summaries of one field stay two choices.
 */
const AMOUNT_SUM = formatMessage(defaultMessages, 'label.summary.of', {
  field: 'Amount',
  fn: defaultMessages['label.summary.fn.SUM'],
});

/**
 * 「只保留」 (D20 屏 B; Wow `having`): which of the grouped rows the result
 * keeps, said as rows of one comparison each under the metrics. The kernel
 * that reads and writes the expression is `test/having.test.ts`; this is the
 * block of controls over it — when it exists at all, what a row sends and
 * when, and what it does with a stored shape it cannot say.
 */

const view: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/**
 * Two measurable fields and a sample value, so the metric list has both
 * something to offer and something to leave out: Wow refuses a having over
 * an `ANY` metric, so the select must not list one.
 */
function keepingDefinition(having = true): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number', sortable: true },
      { name: 'cost', label: 'Cost', kind: 'number', sortable: true },
    ],
    analysis: {
      count: true,
      expressions: true,
      ...(having ? { having: true } : {}),
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM],
          any: true,
        },
        { field: 'cost', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

/** The config the suites start from: a count, an amount and a sample value. */
function keepingConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({
    metrics: [
      { alias: 'orders', type: 'COUNT' },
      {
        alias: 'amount',
        type: 'NUMERIC',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      },
      { alias: 'sample', type: 'ANY', field: 'amount' },
    ],
    chart: {
      type: 'bar',
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    },
    ...overrides,
  });
}

/** A saved analysis view on screen, its tray opened. */
async function open(
  config: Partial<AnalysisViewConfig> = {},
  definition: DataViewDefinition = keepingDefinition(),
): Promise<{ engine: ViewEngine; source: ViewSource }> {
  const store = new MemoryViewStore({
    instances: [{ ...view, config: keepingConfig(config) }],
  });
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [definition],
    store,
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  await openTray();
  return { engine, source };
}

/** The config being edited, an analysis config here by construction. */
function draft(engine: ViewEngine): AnalysisViewConfig {
  return engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;
}

/** The draft as the plain JSON it is, so a test can ask whether a key is there. */
const json = (value: object) => value as unknown as Record<string, unknown>;

const group = () =>
  document.querySelector<HTMLElement>('[data-slot="analysis-having"]');

const rows = () => [
  ...document.querySelectorAll<HTMLElement>('[data-slot="having-row"]'),
];

const addRow = () =>
  document.querySelector<HTMLElement>('[data-slot="add-having"]');

/** The aggregation the source was asked for last. */
function lastQuery(source: ViewSource) {
  const calls = vi.mocked(source.aggregate).mock.calls;
  return calls[calls.length - 1]?.[0];
}

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

/** Types a number into one of a row's boxes, the way a user fills it in. */
function type(box: HTMLElement, value: string) {
  fireEvent.change(box, { target: { value } });
}

describe('keeping only some of the groups', () => {
  /**
   * A having is the definition's to allow — a backend that cannot filter
   * grouped rows should not be offered the control — and Wow refuses one
   * over an ungrouped aggregation, so the block waits for a dimension too.
   * Neither is drawn disabled: there is nothing the user could do about it.
   */
  it('exists only where the capability declares it and a dimension is there', async () => {
    await open({}, keepingDefinition(false));
    expect(group()).toBeNull();

    cleanup();
    await open({ groups: [] });
    expect(group()).toBeNull();

    cleanup();
    await open();
    expect(group()).not.toBeNull();
    // Named by its visible label — the legend — in the result slot, not by
    // an `aria-label` a sighted reader never sees (2026-09-23 audit).
    expect(
      within(
        screen.getByRole('region', {
          name: defaultMessages['label.analysis.slot.result'],
        }),
      ).getByRole('group', {
        name: defaultMessages['label.analysis.having-title'],
      }),
    ).toBe(group());
    expect(group()!.hasAttribute('aria-label')).toBe(false);
    // Its one way in says what it adds, under the label that says why.
    expect(addRow()!.textContent).toBe(
      defaultMessages['label.analysis.having'],
    );
  });

  /**
   * The legend says 「只保留」 once; a second row reads on from the first
   * with 「并且」, because every one of them has to hold.
   */
  it('joins the rows after the first with the word that says all must hold', async () => {
    await open();

    fireEvent.click(addRow()!);
    fireEvent.click(addRow()!);

    await waitFor(() => expect(rows()).toHaveLength(2));
    const and = defaultMessages['label.analysis.having-and'];
    expect(within(rows()[0]).queryByText(and)).toBeNull();
    expect(within(rows()[1]).getByText(and)).toBeDefined();
  });

  /**
   * A row without a value is the editor's, not the config's: 「金额合计
   * 大于 」 is not a comparison Wow can run, and a config on disk is always
   * one it accepts. So the row appears, and nothing is written until it says
   * a number.
   */
  it('adds a row that writes nothing until it has a value', async () => {
    const { engine } = await open();

    fireEvent.click(addRow()!);

    await waitFor(() => expect(rows()).toHaveLength(1));
    expect('having' in json(draft(engine))).toBe(false);

    type(
      within(rows()[0]).getByLabelText(
        defaultMessages['label.analysis.having-value'],
      ),
      '2000',
    );

    await waitFor(() =>
      expect(draft(engine).having).toEqual({
        type: 'CONDITION',
        metric: 'orders',
        operator: 'GT',
        value: 2000,
      }),
    );
  });

  /**
   * Several rows are one AND — every one of them has to hold — and taking
   * one back returns the expression to what the rest of them say, down to
   * the key being gone when the last row goes. A config is plain JSON: a
   * `having: undefined` left behind is a shape no new config would have.
   */
  it('reads several rows as one AND, and unwinds to no key at all', async () => {
    const { engine } = await open();
    const value = (at: number) =>
      within(rows()[at]).getByLabelText(
        defaultMessages['label.analysis.having-value'],
      );

    fireEvent.click(addRow()!);
    type(await waitFor(() => value(0)), '2000');
    await waitFor(() => expect(draft(engine).having?.type).toBe('CONDITION'));

    fireEvent.click(addRow()!);
    await waitFor(() => expect(rows()).toHaveLength(2));
    // Metric, comparison, value and remove wear the same four names in
    // every row, so the row itself is the group that says which one it is.
    expect(rows().map(row => row.getAttribute('role'))).toEqual([
      'group',
      'group',
    ]);
    expect(rows().map(row => row.getAttribute('aria-label'))).toEqual([
      'Keep-only condition 1',
      'Keep-only condition 2',
    ]);
    type(value(1), '5');

    await waitFor(() =>
      expect(draft(engine).having).toEqual({
        type: 'AND',
        operands: [
          { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 2000 },
          { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 5 },
        ],
      }),
    );

    // The note is about the rows, so it is there while they are.
    expect(
      document.querySelector('[data-slot="having-note"]')?.textContent,
    ).toBe(defaultMessages['label.analysis.having-note']);

    fireEvent.click(
      within(rows()[1]).getByRole('button', {
        name: defaultMessages['label.analysis.remove-having'],
      }),
    );
    await waitFor(() => expect(draft(engine).having?.type).toBe('CONDITION'));

    fireEvent.click(
      within(rows()[0]).getByRole('button', {
        name: defaultMessages['label.analysis.remove-having'],
      }),
    );
    await waitFor(() => expect('having' in json(draft(engine))).toBe(false));
    expect(document.querySelector('[data-slot="having-note"]')).toBeNull();
  });

  /**
   * Wow's having compares numbers, and a sample value is not one — it
   * refuses a having over an `ANY` metric outright. So the select does not
   * list one: a choice that makes the config unrunnable is not a choice.
   */
  it('offers every metric but the sample value', async () => {
    const user = userEvent.setup();
    await open();

    fireEvent.click(addRow()!);
    await waitFor(() => expect(rows()).toHaveLength(1));
    await user.click(
      within(rows()[0]).getByLabelText(
        defaultMessages['label.analysis.having-metric'],
      ),
    );

    expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
    ).toEqual([defaultMessages['label.analysis.row-count'], AMOUNT_SUM]);
  });

  /**
   * The comparison is the row's second control, and it writes the operator
   * Wow names rather than the words the row reads as.
   */
  it('writes the comparison the row is set to', async () => {
    const user = userEvent.setup();
    const { engine } = await open({
      having: { type: 'CONDITION', metric: 'amount', operator: 'GT', value: 1 },
    });

    await user.click(
      within(rows()[0]).getByLabelText(
        defaultMessages['label.analysis.having-operator'],
      ),
    );
    await user.click(
      await screen.findByRole('option', {
        name: defaultMessages['label.having.op.LTE'],
      }),
    );

    await waitFor(() =>
      expect(draft(engine).having).toEqual({
        type: 'CONDITION',
        metric: 'amount',
        operator: 'LTE',
        value: 1,
      }),
    );
  });

  /**
   * And which metric a row keeps by: the row is a sentence with three
   * changeable words, not a fixed one with a number in it.
   */
  it('writes the metric the row keeps by', async () => {
    const user = userEvent.setup();
    const { engine } = await open({
      having: { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 3 },
    });

    await user.click(
      within(rows()[0]).getByLabelText(
        defaultMessages['label.analysis.having-metric'],
      ),
    );
    await user.click(await screen.findByRole('option', { name: AMOUNT_SUM }));

    await waitFor(() =>
      expect(draft(engine).having).toEqual({
        type: 'CONDITION',
        metric: 'amount',
        operator: 'GT',
        value: 3,
      }),
    );
  });

  /**
   * Wow's having is a small language — ranges, sets, null checks, OR trees —
   * and the rows say one shape of it. A stored config may hold any of the
   * rest, and flattening a `BETWEEN` into two rows would be the editor
   * inventing a config the author never wrote. So it says the rule is there,
   * and offers the one honest way out.
   */
  it('shows a stored shape the rows cannot say, and clears it', async () => {
    const { engine } = await open({
      having: { type: 'BETWEEN', metric: 'amount', lower: 10, upper: 20 },
    });

    expect(rows()).toHaveLength(0);
    expect(addRow()).toBeNull();
    expect(
      within(group()!).getByText(
        defaultMessages['label.analysis.having-unreadable'],
      ),
    ).toBeDefined();

    fireEvent.click(
      within(group()!).getByRole('button', {
        name: defaultMessages['label.analysis.clear-having'],
      }),
    );

    await waitFor(() => expect('having' in json(draft(engine))).toBe(false));
    expect(
      within(group()!).queryByText(
        defaultMessages['label.analysis.having-unreadable'],
      ),
    ).toBeNull();
  });

  /** And the whole point: the query that runs carries it. */
  it('sends the having with the query Apply runs', async () => {
    const { source } = await open();

    fireEvent.click(addRow()!);
    type(
      await waitFor(() =>
        within(rows()[0]).getByLabelText(
          defaultMessages['label.analysis.having-value'],
        ),
      ),
      '2000',
    );
    fireEvent.click(applyButton());

    await waitFor(() =>
      expect(lastQuery(source)?.having).toEqual({
        type: 'CONDITION',
        metric: 'orders',
        operator: 'GT',
        value: 2000,
      }),
    );
  });
});

/**
 * A saved view opens with its tray folded, so what 「只保留」 dropped was
 * nowhere on screen: the table drew some of the groups, the totals row
 * counted every record, and a reader took the missing groups for groups
 * without data (the 2026-09-23 audit, P0-2). The result's reading says it,
 * after the metrics and in the tray's own words, read off the config that
 * ran.
 */
describe("the result's reading says which groups were kept", () => {
  /** A saved view on screen, its tray left folded as it opens. */
  async function showSaved(
    config: Partial<AnalysisViewConfig>,
    {
      messages,
      locale,
      autoRun,
    }: { messages?: ViewMessages; locale?: string; autoRun?: boolean } = {},
  ): Promise<void> {
    const store = new MemoryViewStore({
      instances: [{ ...view, config: keepingConfig(config) }],
      ...(autoRun === undefined
        ? {}
        : {
            preferences: {
              orders: {
                order: [],
                defaultInstanceId: null,
                autoRun,
                revision: 'p1',
              },
            },
          }),
    });
    const source = testSource();
    const engine = new ViewEngine({
      definitions: [keepingDefinition()],
      store,
      resolveSource: () => source,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
        messages={messages}
        locale={locale}
      />,
    );
    await waitFor(() => expect(reading()).not.toBeNull());
  }

  const reading = () =>
    document.querySelector<HTMLElement>('[data-slot="analysis-reading"]');

  const SHAPED =
    'By Warehouse · Record count, Sum of Amount, Any value of Amount';

  it('says the having after the metrics with the tray folded', async () => {
    await showSaved({
      having: {
        type: 'CONDITION',
        metric: 'amount',
        operator: 'GT',
        value: 2000,
      },
    });

    expect(document.querySelector('[data-slot="analysis-tray"]')).toBeNull();
    expect(reading()!.textContent).toBe(
      `${SHAPED} · Keep only Sum of Amount more than 2,000`,
    );
  });

  it('joins several rows with the word the tray joins them with', async () => {
    await showSaved({
      having: {
        type: 'AND',
        operands: [
          { type: 'CONDITION', metric: 'amount', operator: 'GT', value: 2000 },
          { type: 'CONDITION', metric: 'orders', operator: 'GTE', value: 2 },
        ],
      },
    });

    expect(reading()!.textContent).toBe(
      `${SHAPED} · Keep only Sum of Amount more than 2,000 and Record count at least 2`,
    );
  });

  /**
   * A shape the rows cannot say is still said to be there — groups were
   * dropped all the same — without claiming which rule dropped them.
   */
  it('says a having of another shape is there without spelling it', async () => {
    await showSaved({
      having: { type: 'BETWEEN', metric: 'amount', lower: 10, upper: 20 },
    });

    expect(reading()!.textContent).toBe(
      `${SHAPED} · Keep only groups matching a custom rule`,
    );
  });

  it('says nothing more where every group was kept', async () => {
    await showSaved({});

    expect(reading()!.textContent).toBe(SHAPED);
  });

  it('reads it in Chinese with zhCN', async () => {
    await showSaved(
      {
        having: {
          type: 'AND',
          operands: [
            {
              type: 'CONDITION',
              metric: 'amount',
              operator: 'GT',
              value: 2000,
            },
            { type: 'CONDITION', metric: 'orders', operator: 'LTE', value: 5 },
          ],
        },
      },
      { messages: zhCN, locale: 'zh-CN' },
    );

    expect(reading()!.textContent).toBe(
      '按Warehouse · 记录数、Amount的合计、Amount的任一值 · 只保留 Amount的合计 大于 2,000 并且 记录数 不大于 5',
    );

    cleanup();
    await showSaved(
      { having: { type: 'BETWEEN', metric: 'amount', lower: 10, upper: 20 } },
      { messages: zhCN, locale: 'zh-CN' },
    );
    expect(reading()!.textContent).toBe(
      '按Warehouse · 记录数、Amount的合计、Amount的任一值 · 只保留符合自定义规则的组',
    );
  });

  /**
   * The reading is the result's, so a comparison edited in the tray is not
   * said until it has run: the numbers below still answer the old one.
   */
  it('reads the having that ran, not the one being edited', async () => {
    await showSaved(
      {
        having: {
          type: 'CONDITION',
          metric: 'amount',
          operator: 'GT',
          value: 2000,
        },
      },
      { autoRun: false },
    );
    await openTray();

    type(
      within(rows()[0]).getByLabelText(
        defaultMessages['label.analysis.having-value'],
      ),
      '3000',
    );

    expect(reading()!.textContent).toBe(
      `${SHAPED} · Keep only Sum of Amount more than 2,000`,
    );
    fireEvent.click(applyButton());
    await waitFor(() =>
      expect(reading()!.textContent).toBe(
        `${SHAPED} · Keep only Sum of Amount more than 3,000`,
      ),
    );
  });
});
