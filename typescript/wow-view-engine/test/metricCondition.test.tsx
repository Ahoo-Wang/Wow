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
  FilterOperator,
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
 * A metric's own conditions (D20 屏 H): the funnel on the card, the block of
 * the range's own pills under it, the 「只算 …」 line at rest, and the copy
 * of a metric made to carry a second condition.
 *
 * The tray itself is `test/analysisTray.test.tsx` and the card's menu is
 * `test/analysisCards.test.tsx`; what a filter in metric position may say is
 * `test/analysisValidate.test.ts` and what it compiles to is
 * `test/analysisCompile.test.ts`. This suite is the gesture: from the funnel
 * to the query the source is asked for.
 */

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/**
 * One field of every answer the condition block has to give: two scalar
 * fields it offers, a closed set to pick a value from, and two fields it
 * must never offer — an array holds many values at once and a search field
 * names no value of a record at all, so neither can decide, per record,
 * whether that record counts.
 */
function conditionDefinition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      {
        name: 'status',
        label: 'Status',
        kind: 'enum',
        options: [
          { value: 'PAID', label: 'Paid' },
          { value: 'PENDING', label: 'Pending' },
        ],
      },
      { name: 'amount', label: 'Amount', kind: 'number' },
      {
        name: 'tags',
        label: 'Tags',
        kind: 'array',
        options: [{ value: 'rush', label: 'Rush' }],
      },
      { name: 'text', label: 'Anything', kind: 'search' },
    ],
    analysis: {
      count: true,
      expressions: true,
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
        },
      ],
    },
  });
}

/** The record count and one summed field: a card with a funnel, twice. */
function twoMetrics(): AnalysisViewConfig['metrics'] {
  return [
    { alias: 'orders', type: 'COUNT' },
    {
      alias: 'amount',
      type: 'NUMERIC',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'amount' },
    },
  ];
}

/** A saved analysis view on screen, its tray opened. */
async function open(config: Partial<AnalysisViewConfig> = {}) {
  const source = testSource();
  const store = new MemoryViewStore({
    instances: [
      {
        ...analysisView,
        config: analysisConfig({ metrics: twoMetrics(), ...config }),
      },
    ],
  });
  const engine = new ViewEngine({
    definitions: [conditionDefinition()],
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

const label = (
  key: keyof typeof defaultMessages,
  params: Record<string, string>,
) => formatMessage(defaultMessages, key, params);

/**
 * How the amount metric is *referred to*: the card's title says 「Amount」,
 * because the summary control sits beside it, and every accessible name on
 * the card says the whole of it — the sentence the result column is headed
 * with — so two summaries of one field are two different names.
 */
const AMOUNT = formatMessage(defaultMessages, 'label.summary.of', {
  field: 'Amount',
  fn: defaultMessages['label.summary.fn.SUM'],
});

/** The funnel of the card called `name`. */
const funnel = (name: string) =>
  screen.getByRole('button', {
    name: label('label.analysis.condition-of', { name }),
  });

/** The block open under a card, whichever card it belongs to. */
const block = () =>
  document.querySelector<HTMLElement>('[data-slot="card-conditions"]');

const metricCards = () => [
  ...document.querySelectorAll<HTMLElement>('[data-slot="metric-card"]'),
];

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

/**
 * The field picker of one block. Every group on the screen carries an "Add
 * in this group" of its own, so the block's is reached by the name of what
 * the conditions belong to.
 */
async function openPicker(scope: string): Promise<HTMLElement> {
  fireEvent.click(
    screen.getByRole('button', {
      name: `${scope} ${defaultMessages['label.filter.add-here']}`,
    }),
  );
  return await screen.findByRole('dialog', {
    name: defaultMessages['label.filter.pick-fields'],
  });
}

/** Ticking a field is what adds its condition; Done is the way out. */
async function addCondition(scope: string, field: string): Promise<void> {
  const picker = await openPicker(scope);
  fireEvent.click(within(picker).getByRole('checkbox', { name: field }));
  fireEvent.click(
    within(picker).getByRole('button', {
      name: defaultMessages['label.filter.pick-done'],
    }),
  );
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
}

/** Picks one candidate out of a condition's value control, and closes it. */
async function chooseValue(field: string, option: string): Promise<void> {
  const user = userEvent.setup();
  await user.click(
    within(block()!).getByRole('combobox', {
      name: label('label.filter.value-of', { field }),
    }),
  );
  const picked = await screen.findByRole('option', { name: option });
  await user.click(picked);
  // A list that takes several values stays open; the next gesture is not
  // inside it.
  fireEvent.keyDown(picked, { key: 'Escape' });
}

/** How many aggregations the source has been asked for so far. */
const asked = (source: ViewSource) =>
  vi.mocked(source.aggregate).mock.calls.length;

describe('a metric’s own conditions', () => {
  /**
   * The way in is a funnel on the card itself rather than an item of the
   * card's menu or a dialog: a condition belongs to the metric it narrows,
   * so it grows where the metric is. Pressing it on a metric that carries
   * none writes an empty condition — the key being there is what says "this
   * metric is being narrowed", and validation then says it is unfinished
   * rather than letting an empty one widen the number silently.
   */
  it('opens from the funnel, and starts the condition empty', async () => {
    const { engine } = await open();

    const button = funnel(AMOUNT);
    expect(button.getAttribute('aria-pressed')).toBe('false');
    expect(button.hasAttribute('data-held')).toBe(false);

    fireEvent.click(button);

    await waitFor(() =>
      expect(draft(engine).metrics[1]).toMatchObject({
        filter: { op: 'and', children: [] },
      }),
    );
    const opened = await screen.findByRole('group', {
      name: label('label.analysis.condition-of', { name: AMOUNT }),
    });
    expect(opened.getAttribute('data-slot')).toBe('card-conditions');
    expect(opened.textContent).toContain(
      defaultMessages['label.analysis.condition-title'],
    );
    // The card the block belongs to is the one whose funnel was pressed.
    expect(metricCards()[1]!.contains(opened)).toBe(true);
    expect(funnel(AMOUNT).getAttribute('aria-pressed')).toBe('true');
    expect(funnel(AMOUNT).hasAttribute('data-held')).toBe(true);
  });

  /**
   * A metric filter decides, per record, whether that record counts, so it
   * has one record's value to work with. An array field holds several at
   * once and a search field names none, and Wow refuses both in metric
   * position — so they are not on the list at all, rather than on it and
   * refused on Apply.
   */
  it('offers the scope’s scalar fields, and nothing without one value', async () => {
    await open();
    fireEvent.click(funnel(AMOUNT));
    await screen.findByRole('group', {
      name: label('label.analysis.condition-of', { name: AMOUNT }),
    });

    const picker = await openPicker(AMOUNT);

    for (const field of ['Order', 'Warehouse', 'Status', 'Amount'])
      expect(
        within(picker).getByRole('checkbox', { name: field }),
      ).toBeDefined();
    for (const field of ['Tags', 'Anything'])
      expect(
        within(picker).queryByRole('checkbox', { name: field }),
      ).toBeNull();
    expect(within(picker).getAllByRole('checkbox')).toHaveLength(4);
  });

  /**
   * The point of the whole gesture: the query carries the condition on the
   * metric that owns it and on no other, so two cards over the same field
   * come back as two different numbers from one query.
   */
  it('sends the metric’s own filter with the query', async () => {
    const { source } = await open();
    fireEvent.click(funnel(AMOUNT));
    await addCondition(AMOUNT, 'Status');
    await chooseValue('Status', 'Paid');

    const before = asked(source);
    fireEvent.click(applyButton());

    await waitFor(() => expect(asked(source)).toBeGreaterThan(before));
    const calls = vi.mocked(source.aggregate).mock.calls;
    const query = calls[calls.length - 1]![0];
    expect(query.metrics).toMatchObject([
      { alias: 'orders' },
      {
        alias: 'amount',
        filter: { op: FilterOperator.IN, field: 'status', values: ['PAID'] },
      },
    ]);
    expect('filter' in query.metrics[0]).toBe(false);
  });

  /**
   * An unfinished condition is the dangerous one: it compiles to "match
   * everything" and the number quietly covers every record. So the query
   * waits for it, the pill says which condition it is, and the strip above
   * the editor says it in words — the finding is about a tree the range's
   * own pills cannot carry, so it has nowhere else to be said.
   */
  it('waits for an unfinished condition, and says where it is', async () => {
    const { source } = await open();
    fireEvent.click(funnel(AMOUNT));

    await addCondition(AMOUNT, 'Status');

    const pill = await waitFor(() => {
      const found = within(block()!).getByRole('group', {
        name: label('label.filter.condition-of', { field: 'Status' }),
      });
      expect(found.hasAttribute('data-invalid')).toBe(true);
      return found;
    });
    expect(pill.getAttribute('data-slot')).toBe('filter-condition');

    const before = asked(source);
    fireEvent.click(applyButton());

    const strip = await screen.findByRole('alert');
    expect(strip.textContent).toContain(
      label('analysis.metricFilter.incomplete', { field: 'status' }),
    );
    expect(asked(source)).toBe(before);
  });

  /**
   * "Count every record again" is the way back out, and it takes the key
   * away rather than leaving an empty tree behind: a config is plain JSON,
   * and a metric carrying `filter: undefined` is a config no fresh one
   * would ever be. The block closes with it — there is nothing left to
   * edit.
   */
  it('takes the condition away altogether, and closes', async () => {
    const { engine } = await open();
    fireEvent.click(funnel(AMOUNT));
    await addCondition(AMOUNT, 'Status');

    fireEvent.click(
      within(block()!).getByRole('button', {
        name: defaultMessages['label.analysis.condition-remove'],
      }),
    );

    await waitFor(() => expect(block()).toBeNull());
    expect('filter' in (draft(engine).metrics[1] as object)).toBe(false);
    expect(funnel(AMOUNT).hasAttribute('data-held')).toBe(false);
  });

  /**
   * At rest the card says what it counts, in the same words the applied bar
   * uses. Without it the scope of a number lives behind an icon, and a
   * screen of cards reading 「金额 合计」 twice says nothing about why the
   * two are different.
   */
  it('says what it counts, on the card, at rest', async () => {
    await open({
      metrics: [
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'amount',
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
          filter: {
            op: 'and',
            children: [{ field: 'status', operator: 'IN', value: ['PAID'] }],
          },
        },
      ],
    });

    const line = await waitFor(() => {
      const found = metricCards()[1]!.querySelector<HTMLElement>(
        '[data-slot="metric-condition-line"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(line.textContent).toContain('Status');
    expect(line.textContent).toContain('Paid');
    // Named by what it counts (D20 显示名): the one value its condition
    // keeps follows the summary, on every control that names the card.
    expect(funnel(`${AMOUNT} · Paid`).hasAttribute('data-held')).toBe(true);
    // The count next to it carries none, so it wears no line.
    expect(
      metricCards()[0]!.querySelector('[data-slot="metric-condition-line"]'),
    ).toBeNull();
    // One value names the metric already, so nothing asks for a name.
    expect(document.querySelector('[data-slot="metric-name-it"]')).toBeNull();
  });

  /**
   * The result says it too (audit P0-3): a header of 「Sum of Amount」 over
   * a sum of the paid orders read a warehouse with none paid as one with no
   * sales. The header names the one value; its description — the tooltip,
   * and what a reader hears — says the whole condition, as the card does.
   */
  it('heads its result column with what it counts', async () => {
    await open({
      metrics: [
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'amount',
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
          filter: {
            op: 'and',
            children: [{ field: 'status', operator: 'IN', value: ['PAID'] }],
          },
        },
      ],
    });

    const header = await screen.findByRole('columnheader', {
      name: new RegExp(`^${AMOUNT} · Paid`),
    });
    const described = header.querySelector('[aria-describedby]');
    const ids = described?.getAttribute('aria-describedby')?.split(' ') ?? [];
    const sentences = ids.map(id => document.getElementById(id)?.textContent);
    expect(sentences).toContain(
      label('label.analysis.only-where', {
        conditions: 'Status is any of Paid',
      }),
    );
    // The record count beside it counts every record and says nothing more.
    expect(
      screen
        .getByRole('columnheader', {
          name: new RegExp(`^${defaultMessages['label.analysis.row-count']}`),
        })
        .hasAttribute('data-note'),
    ).toBe(false);
  });

  /**
   * A condition with no one value to name the metric by leaves it
   * 「Sum of Amount · conditioned」, which says there is a condition and not
   * which. D20 asks the analyst to name it, so the card asks — beside the
   * condition, where the reason is — and the name, once given, wins.
   */
  it('asks for a name when no one value names it', async () => {
    const { engine } = await open({
      metrics: [
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'amount',
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
          filter: {
            op: 'and',
            children: [
              { field: 'status', operator: 'IN', value: ['PAID', 'PENDING'] },
            ],
          },
        },
      ],
    });
    const conditioned = label('label.analysis.metric-conditioned', {
      metric: AMOUNT,
    });
    expect(funnel(conditioned)).toBeDefined();

    const ask = await waitFor(() => {
      const found = metricCards()[1]!.querySelector<HTMLElement>(
        '[data-slot="metric-name-it"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(ask.textContent).toBe(defaultMessages['label.analysis.name-it']);
    fireEvent.click(ask);

    const box = await screen.findByLabelText(
      label('label.analysis.display-name', { name: conditioned }),
    );
    fireEvent.change(box, { target: { value: 'Settled' } });
    fireEvent.keyDown(box, { key: 'Enter' });

    await waitFor(() =>
      expect(draft(engine).metrics[1]).toMatchObject({ label: 'Settled' }),
    );
    expect(funnel('Settled')).toBeDefined();
    expect(document.querySelector('[data-slot="metric-name-it"]')).toBeNull();
  });

  /**
   * Two numbers over the same field under two conditions is the question
   * the funnel exists for, and a second card is how it is asked. The menu
   * item names the metric the way the result column does (「Sum of
   * Amount」), since it is a reference and no summary control is beside it.
   * The copy
   * takes a free alias — the alias is what the query and the chart name —
   * and no display name: two cards called the same thing is the very
   * ambiguity a name is there to resolve.
   */
  it('copies a metric with an empty condition to fill in', async () => {
    const { engine } = await open();

    fireEvent.click(
      screen.getByRole('button', {
        name: label('label.analysis.card-menu', { name: AMOUNT }),
      }),
    );
    fireEvent.click(
      await screen.findByRole('menuitem', {
        name: label('label.analysis.copy-with-condition', { name: AMOUNT }),
      }),
    );

    await waitFor(() => expect(draft(engine).metrics).toHaveLength(3));
    const copy = draft(engine).metrics[2]!;
    expect(copy).toMatchObject({
      type: 'NUMERIC',
      alias: 'amount_1',
      function: 'SUM',
      filter: { op: 'and', children: [] },
    });
    expect('label' in (copy as object)).toBe(false);
    // Right after the metric it copies, not at the end of the slot.
    expect(draft(engine).metrics.map(metric => metric.alias)).toEqual([
      'orders',
      'amount',
      'amount_1',
    ]);
    // The menu item promised a condition, so the copy's block is open —
    // a second identical-looking card with nothing open is not one.
    await waitFor(() => expect(block()).not.toBeNull());
    expect(metricCards()[2]!.contains(block())).toBe(true);
    const funnels = document.querySelectorAll<HTMLElement>(
      '[data-slot="metric-condition-toggle"]',
    );
    expect(funnels[2]!.hasAttribute('data-held')).toBe(true);
    expect(funnels[2]!.getAttribute('aria-pressed')).toBe('true');
    // And only the copy's: one block at a time, on the card it belongs to.
    expect(funnels[1]!.getAttribute('aria-pressed')).toBe('false');
  });

  /**
   * A derived metric is arithmetic over other metrics rather than a walk
   * over records, so the protocol gives it no filter at all: the funnel is
   * absent, not disabled — a control that could never do anything is not a
   * control.
   */
  it('gives a derived metric no funnel at all', async () => {
    await open({
      metrics: [
        { alias: 'orders', type: 'COUNT' },
        {
          alias: 'amount',
          type: 'NUMERIC',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'amount' },
        },
        {
          alias: 'avg',
          type: 'DERIVED',
          label: 'Per order',
          expression: {
            type: 'BINARY',
            operator: 'DIVIDE',
            left: { type: 'METRIC_REF', metric: 'amount' },
            right: { type: 'METRIC_REF', metric: 'orders' },
          },
        },
      ],
    });

    const derived = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="metric-card"][data-metric="DERIVED"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    expect(
      derived.querySelector('[data-slot="metric-condition-toggle"]'),
    ).toBeNull();
    // Nor is it offered a copy: a copy exists to carry a condition.
    fireEvent.click(
      within(derived).getByRole('button', {
        name: label('label.analysis.card-menu', { name: 'Per order' }),
      }),
    );
    expect(
      within(await screen.findByRole('menu'))
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([defaultMessages['label.analysis.rename']]);
  });
});
