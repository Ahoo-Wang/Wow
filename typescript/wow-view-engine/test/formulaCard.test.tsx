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
} from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/**
 * The two metrics an analyst writes rather than picks (D20 屏 B), and the
 * editor over the groups they are ordered by.
 *
 * A **formula** is a number per record — 金额 − 成本 — summarised across the
 * group; a **derived** metric is arithmetic over two metrics of the same
 * row. Both are one operation over two operands, and both are named by what
 * they say rather than by a field. The kernel is `test/having.test.ts`
 * 「formulas」; this is the card, the menu it is added from, and the column
 * the result draws.
 */

const view: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/** Two measurable fields, so a formula says something a single field cannot. */
function writingDefinition(expressions = true): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number', sortable: true },
      { name: 'cost', label: 'Cost', kind: 'number', sortable: true },
    ],
    analysis: {
      count: true,
      having: true,
      ...(expressions ? { expressions: true } : {}),
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'amount',
          groups: [],
          functions: [AggregationFunction.SUM, AggregationFunction.AVG],
          any: true,
        },
        { field: 'cost', groups: [], functions: [AggregationFunction.SUM] },
      ],
    },
  });
}

/** A count, an amount and a sample value: two a derived metric may read, one it may not. */
function writingConfig(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisConfig({
    layout: 'table',
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

async function open(
  config: Partial<AnalysisViewConfig> = {},
  definition: DataViewDefinition = writingDefinition(),
): Promise<{ engine: ViewEngine; source: ViewSource }> {
  const store = new MemoryViewStore({
    instances: [{ ...view, config: writingConfig(config) }],
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

function draft(engine: ViewEngine): AnalysisViewConfig {
  return engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;
}

const label = (key: keyof typeof defaultMessages, name: string) =>
  formatMessage(defaultMessages, key, { name });

/**
 * How a metric is *referred to* away from its own title: the summary and
 * what it is of, in the catalogue's order — the same sentence the result
 * column is headed with. The card's own title says only the left half,
 * because the summary control sits beside it.
 */
const summed = (of: string) =>
  formatMessage(defaultMessages, 'label.summary.of', {
    field: of,
    fn: defaultMessages['label.summary.fn.SUM'],
  });

/** Picks an item out of one of the tray's two "+ Add …" menus. */
async function add(menu: string, item: string) {
  fireEvent.click(screen.getByRole('button', { name: menu }));
  fireEvent.click(await screen.findByRole('menuitem', { name: item }));
}

/** Opens the "+ Add metric" menu and answers the items it holds. */
async function metricMenu(): Promise<HTMLElement> {
  fireEvent.click(
    screen.getByRole('button', {
      name: defaultMessages['label.analysis.add-metric'],
    }),
  );
  return await screen.findByRole('menu');
}

const cardNames = () =>
  [...document.querySelectorAll('[data-slot="card-name"]')].map(
    name => name.textContent,
  );

const metricCards = () => [
  ...document.querySelectorAll<HTMLElement>('[data-slot="metric-card"]'),
];

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

/** The aggregation the source was asked for last. */
function lastQuery(source: ViewSource) {
  const calls = vi.mocked(source.aggregate).mock.calls;
  return calls[calls.length - 1]?.[0];
}

/** The metric a query lists last, which is the one just added. */
function lastMetric(source: ViewSource) {
  const metrics = lastQuery(source)?.metrics ?? [];
  return metrics[metrics.length - 1];
}

/** The result table's headers, as the reader sees them. */
const headers = () =>
  [...document.querySelectorAll('table thead th')].map(cell =>
    cell.textContent?.trim(),
  );

/** The options a select is offering, by the words on them. */
async function optionsOf(control: HTMLElement): Promise<(string | null)[]> {
  const user = userEvent.setup();
  await user.click(control);
  const found = (await screen.findAllByRole('option')).map(
    option => option.textContent,
  );
  fireEvent.keyDown(await screen.findByRole('listbox'), { key: 'Escape' });
  return found;
}

describe('a metric written as a formula', () => {
  /**
   * Both are the definition's to allow: a backend that cannot evaluate an
   * expression should not be offered the two items that write one. They are
   * absent rather than disabled — there is nothing the user could do.
   */
  it('is offered only where the capability declares expressions', async () => {
    await open({}, writingDefinition(false));

    let menu = await metricMenu();
    expect(
      within(menu).queryByRole('menuitem', {
        name: defaultMessages['label.analysis.add-formula'],
      }),
    ).toBeNull();
    expect(
      within(menu).queryByRole('menuitem', {
        name: defaultMessages['label.analysis.add-derived'],
      }),
    ).toBeNull();

    cleanup();
    await open();
    menu = await metricMenu();
    expect(
      within(menu).getByRole('menuitem', {
        name: defaultMessages['label.analysis.add-formula'],
      }),
    ).toBeDefined();
    expect(
      within(menu).getByRole('menuitem', {
        name: defaultMessages['label.analysis.add-derived'],
      }),
    ).toBeDefined();
  });

  /**
   * A formula's first shape is the first two measurable fields subtracted
   * and summed — a card that is there to be changed — and it is named by
   * what it says, because no field stands behind it.
   */
  it('starts as two fields subtracted, and is named by what it says', async () => {
    const { engine } = await open();

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-formula'],
    );

    await waitFor(() => expect(cardNames()).toContain('Amount − Cost'));
    expect(draft(engine).metrics[3]).toMatchObject({
      type: 'NUMERIC',
      function: 'SUM',
      expression: {
        type: 'BINARY',
        operator: 'SUBTRACT',
        left: { type: 'FIELD', field: 'amount' },
        right: { type: 'FIELD', field: 'cost' },
      },
    });

    // The three controls one operation over two operands is made of, each
    // named after the card it sits on.
    const card = metricCards()[3];
    expect(
      within(card).getByLabelText(
        label('label.analysis.operand-left', 'Amount − Cost'),
      ),
    ).toBeDefined();
    expect(
      within(card).getByLabelText(
        label('label.analysis.operator', 'Amount − Cost'),
      ),
    ).toBeDefined();
    expect(
      within(card).getByLabelText(
        label('label.analysis.operand-right', 'Amount − Cost'),
      ),
    ).toBeDefined();
    expect(
      within(card).getByLabelText(
        label('label.analysis.function-of', 'Amount − Cost'),
      ),
    ).toBeDefined();
  });

  /**
   * An operand is a field or a number. Picking 「A number」 grows the box
   * that holds it, and what the config gets is a `CONSTANT` — the operand's
   * two shapes are one control, not two.
   */
  it('takes a number in place of a field, and writes it as a constant', async () => {
    const user = userEvent.setup();
    const { engine } = await open();

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-formula'],
    );
    await waitFor(() => expect(cardNames()).toContain('Amount − Cost'));

    await user.click(
      screen.getByLabelText(
        label('label.analysis.operand-right', 'Amount − Cost'),
      ),
    );
    await user.click(
      await screen.findByRole('option', {
        name: defaultMessages['label.analysis.operand-number'],
      }),
    );

    await waitFor(() =>
      expect(draft(engine).metrics[3]).toMatchObject({
        expression: { right: { type: 'CONSTANT', value: 0 } },
      }),
    );
    const box = screen.getByLabelText(
      label('label.analysis.operand-value', 'Amount − 0'),
    );
    fireEvent.change(box, { target: { value: '12' } });
    await waitFor(() =>
      expect(draft(engine).metrics[3]).toMatchObject({
        expression: { right: { type: 'CONSTANT', value: 12 } },
      }),
    );
  });

  /** The operation between them, and how the number is summarised after it. */
  it('writes the operation and the summary the card is set to', async () => {
    const user = userEvent.setup();
    const { engine } = await open();

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-formula'],
    );
    await waitFor(() => expect(cardNames()).toContain('Amount − Cost'));

    await user.click(
      screen.getByLabelText(label('label.analysis.operator', 'Amount − Cost')),
    );
    await user.click(await screen.findByRole('option', { name: '÷' }));
    await waitFor(() =>
      expect(draft(engine).metrics[3]).toMatchObject({
        expression: { operator: 'DIVIDE' },
      }),
    );

    await user.click(
      screen.getByLabelText(
        label('label.analysis.function-of', 'Amount ÷ Cost'),
      ),
    );
    await user.click(
      await screen.findByRole('option', {
        name: defaultMessages['label.summary.fn.AVG'],
      }),
    );
    await waitFor(() =>
      expect(draft(engine).metrics[3]).toMatchObject({ function: 'AVG' }),
    );
  });

  /**
   * The whole point: the query carries the expression, and the column it
   * answers is titled by the two parts a metric header is made of — what
   * the formula says, and how it was summarised.
   */
  it('sends the expression and titles the column by what it says', async () => {
    const { source } = await open();

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-formula'],
    );
    await waitFor(() => expect(cardNames()).toContain('Amount − Cost'));
    fireEvent.click(applyButton());

    await waitFor(() =>
      expect(lastMetric(source)).toMatchObject({
        type: 'NUMERIC',
        function: 'SUM',
        expression: {
          type: 'BINARY',
          operator: 'SUBTRACT',
          left: { type: 'FIELD', field: 'amount' },
          right: { type: 'FIELD', field: 'cost' },
        },
      }),
    );
    await waitFor(() =>
      expect(headers()).toContain(
        formatMessage(defaultMessages, 'label.summary.of', {
          field: 'Amount − Cost',
          fn: defaultMessages['label.summary.fn.SUM'],
        }),
      ),
    );
  });
});

/**
 * A formula a config holds that is deeper than one operation. The card
 * edits one operation over two operands and offers no nesting, so the
 * operand that is itself an operation has no control to sit in: it is
 * shown as its own words and left alone, with one line saying why. The
 * card used to draw nothing there, which read as a formula over a single
 * operand — a defect, not a limit.
 */
describe('a formula deeper than the card edits', () => {
  it('shows the nested operand as text, and says it cannot be edited', async () => {
    await open({
      metrics: [
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'margin',
          type: 'NUMERIC',
          function: 'SUM',
          expression: {
            type: 'BINARY',
            operator: 'SUBTRACT',
            left: {
              type: 'BINARY',
              operator: 'MULTIPLY',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'CONSTANT', value: 2 },
            },
            right: { type: 'FIELD', field: 'cost' },
          },
        },
      ],
    });

    const card = await waitFor(() => {
      const found = metricCards()[1];
      if (!found) throw new Error('the formula card is not on the tray');
      return found;
    });

    // The left operand as its author would say it, in place of the select.
    expect(
      within(card).getByText('Amount × 2', {
        selector: '[data-slot="operand-text"]',
      }),
    ).toBeDefined();
    expect(
      within(card).getByText(
        defaultMessages['label.analysis.expression-unreadable'],
      ),
    ).toBeDefined();

    // The half that *is* one operand still edits: the operation, the right
    // side and the summary are all there.
    expect(
      within(card).getByLabelText(
        label('label.analysis.operand-right', '(Amount × 2) − Cost'),
      ),
    ).toBeDefined();
  });
});

describe('a metric derived from other metrics', () => {
  /**
   * Wow's rule, said by the control rather than by an error: a derived
   * metric reads metrics **declared before it**, and never a sample value.
   * So the operand lists hold exactly those, and the card carries no funnel
   * — a metric that reads other metrics has no records of its own to select.
   */
  it('reads only the metrics before it, and holds no conditions of its own', async () => {
    const { engine } = await open();

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-derived'],
    );

    await waitFor(() =>
      expect(cardNames()).toContain('Record count ÷ Sum of Amount'),
    );
    expect(draft(engine).metrics[3]).toMatchObject({
      type: 'DERIVED',
      expression: {
        type: 'BINARY',
        operator: 'DIVIDE',
        left: { type: 'METRIC_REF', metric: 'orders' },
        right: { type: 'METRIC_REF', metric: 'amount' },
      },
    });

    const card = metricCards()[3];
    expect(
      within(card).queryByRole('button', {
        name: label(
          'label.analysis.condition-of',
          'Record count ÷ Sum of Amount',
        ),
      }),
    ).toBeNull();

    expect(
      await optionsOf(
        within(card).getByLabelText(
          label('label.analysis.operand-left', 'Record count ÷ Sum of Amount'),
        ),
      ),
    ).toEqual([
      defaultMessages['label.analysis.row-count'],
      summed('Amount'),
      defaultMessages['label.analysis.operand-number'],
    ]);
  });

  /**
   * The left operand takes a number too, and the card renames itself as it
   * is written: a derived metric's name is what it says, so 「Record count ÷
   * Amount」 becomes 「2 ÷ Amount」 the moment the operand does.
   */
  it('takes a number on the left as well, and says so on the card', async () => {
    const user = userEvent.setup();
    const { engine } = await open();

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-derived'],
    );
    await waitFor(() =>
      expect(cardNames()).toContain('Record count ÷ Sum of Amount'),
    );

    await user.click(
      screen.getByLabelText(
        label('label.analysis.operand-left', 'Record count ÷ Sum of Amount'),
      ),
    );
    await user.click(
      await screen.findByRole('option', {
        name: defaultMessages['label.analysis.operand-number'],
      }),
    );
    await waitFor(() =>
      expect(draft(engine).metrics[3]).toMatchObject({
        expression: { left: { type: 'CONSTANT', value: 0 } },
      }),
    );
    await waitFor(() => expect(cardNames()).toContain('0 ÷ Sum of Amount'));

    fireEvent.change(
      screen.getByLabelText(
        label('label.analysis.operand-value', '0 ÷ Sum of Amount'),
      ),
      { target: { value: '2' } },
    );
    await waitFor(() => expect(cardNames()).toContain('2 ÷ Sum of Amount'));
  });

  /**
   * Its column stands alone: there is no function behind a derived metric
   * and no field either, so its name is the whole of its header — unlike a
   * formula, which is a number summarised and says so.
   */
  it('titles its column with its own words and nothing appended', async () => {
    // Named metrics, so what the card says and what the header says are the
    // same sentence: a derived metric reads other metrics by the names they
    // carry, and the kernel that titles the column has no catalogue of its
    // own to fall back on.
    const { source } = await open({
      metrics: [
        { alias: 'orders', type: 'COUNT', label: 'Orders' },
        {
          alias: 'amount',
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
          label: 'Takings',
        },
      ],
    });

    await add(
      defaultMessages['label.analysis.add-metric'],
      defaultMessages['label.analysis.add-derived'],
    );
    await waitFor(() => expect(cardNames()).toContain('Orders ÷ Takings'));
    fireEvent.click(applyButton());

    await waitFor(() =>
      expect(lastMetric(source)).toMatchObject({
        type: 'DERIVED',
      }),
    );
    // No summary appended, unlike a formula's 「Sum of Amount − Cost」:
    // nothing was summarised, so there is nothing to say about it.
    await waitFor(() => expect(headers()).toContain('Orders ÷ Takings'));
  });
});

describe('ordering the groups', () => {
  /**
   * The record view's own sort editor, over the aliases as if they were
   * fields (D20: one control for one thing). Every dimension and metric may
   * order the groups, first by one and then by the next — the tray used to
   * offer a single entry, which could not say which of two comes first.
   */
  it('orders by several aliases, in the priority the editor lists them', async () => {
    const user = userEvent.setup();
    // The sort lists a metric the way the result column heads it — 「Sum of
    // Amount」 — so two summaries of one field are two entries a reader can
    // tell apart; the sample value carries a name of its own on top of that.
    const { engine, source } = await open({
      metrics: [
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'amount',
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          alias: 'sample',
          type: 'ANY',
          field: 'amount',
          label: 'One of the amounts',
        },
      ],
    });

    await user.click(
      document.querySelector<HTMLElement>(
        '[data-slot="analysis-sort"] [data-control="sort"]',
      )!,
    );
    const editor = await screen.findByRole('dialog');

    await user.click(
      within(editor).getByRole('button', {
        name: defaultMessages['label.sort.add'],
      }),
    );
    await user.click(
      await screen.findByRole('menuitem', { name: summed('Amount') }),
    );
    await waitFor(() =>
      expect(draft(engine).sort).toEqual([
        { alias: 'amount', direction: 'ASC' },
      ]),
    );

    await user.click(
      within(editor).getByRole('button', {
        name: defaultMessages['label.sort.add'],
      }),
    );
    await user.click(
      await screen.findByRole('menuitem', {
        name: defaultMessages['label.analysis.row-count'],
      }),
    );
    await waitFor(() =>
      expect(draft(engine).sort).toEqual([
        { alias: 'amount', direction: 'ASC' },
        { alias: 'orders', direction: 'ASC' },
      ]),
    );

    // Both entries are on screen, in that order, each with its place.
    expect(
      [...editor.querySelectorAll('[data-slot="sort-entry"]')].map(entry =>
        entry.getAttribute('data-field'),
      ),
    ).toEqual(['amount', 'orders']);

    await user.keyboard('{Escape}');
    fireEvent.click(applyButton());
    await waitFor(() =>
      expect(lastQuery(source)?.sort).toEqual([
        { field: 'amount', direction: 'ASC' },
        { field: 'orders', direction: 'ASC' },
      ]),
    );
  });
});
