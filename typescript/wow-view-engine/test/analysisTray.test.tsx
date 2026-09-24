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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
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
} from '../src/ui/index.js';
import type { ViewMessages } from '../src/ui/index.js';
import { SPACE } from '../src/ui/layout.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import {
  addConditions,
  analysisToggle,
  openTray,
} from './fixtures/workbench.js';
import { tracked } from './fixtures/writes.js';

afterEach(cleanup);

/**
 * The analysis view's editor is a tray folded under the title bar's
 * "Analysis" button, exactly where the record view's "Filter" folds (D20).
 * These are its own tests: the fold, the four slots inside it, and what each
 * card of a slot offers. What the controller does with the edits is
 * `test/analysisUi.test.tsx`.
 */

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/** A capability that declares one of everything, so every card is reachable. */
function richDefinition() {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
      { name: 'amount', label: 'Amount', kind: 'number' },
      { name: 'customer', label: 'Customer', kind: 'string' },
      { name: 'note', label: 'Note', kind: 'string' },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          // Two ways to cut it, so the card carries the type select; the
          // first is what a fresh dimension starts as.
          field: 'createdAt',
          groups: [
            AggregationGroupType.DATE_HISTOGRAM,
            AggregationGroupType.TERMS,
          ],
          functions: [],
          dateUnits: [AggregationDateUnit.MONTH, AggregationDateUnit.DAY],
        },
        {
          field: 'amount',
          groups: [AggregationGroupType.HISTOGRAM, AggregationGroupType.TERMS],
          functions: [AggregationFunction.SUM, AggregationFunction.AVG],
          distinctCount: true,
          percentile: true,
          any: true,
        },
        {
          field: 'customer',
          groups: [],
          functions: [],
          distinctCount: true,
        },
        { field: 'note', groups: [], functions: [], any: true },
      ],
    },
  });
}

function tray(): HTMLElement | null {
  return document.querySelector<HTMLElement>('[data-slot="analysis-tray"]');
}

interface Options {
  definition?: DataViewDefinition;
  config?: Partial<AnalysisViewConfig>;
  messages?: ViewMessages;
  source?: ViewSource;
  /** Whether to press the toggle open; a saved view starts folded. */
  fold?: boolean;
  /** The user's auto-run preference; unsaid is on, as it is for a user. */
  autoRun?: boolean;
}

/** A saved analysis view on screen, its tray opened unless asked otherwise. */
async function open({
  definition = richDefinition(),
  config,
  messages,
  source = testSource(),
  fold = true,
  autoRun,
}: Options = {}) {
  const store = tracked(
    new MemoryViewStore({
      instances: [
        { ...analysisView, config: analysisConfig({ ...(config ?? {}) }) },
      ],
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
    }),
  );
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
      messages={messages}
      kinds={['analysis']}
    />,
  );
  await waitFor(() => expect(analysisToggle()).toBeDefined());
  if (fold) await openTray();
  return { engine, store, source };
}

/** Picks an item out of one of the tray's two "+ Add …" menus. */
async function add(menu: string, item: string) {
  fireEvent.click(screen.getByRole('button', { name: menu }));
  fireEvent.click(await screen.findByRole('menuitem', { name: item }));
}

/** The config being edited, which is an analysis config here by construction. */
function draft(engine: ViewEngine): AnalysisViewConfig {
  return engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;
}

const APPLY = defaultMessages['label.filter.apply'];

/**
 * How a metric is *referred to*: what names the card among the others says
 * the summary as well as the field, in the words the result column is
 * headed with, so two summaries of one field are two different names.
 */
const summarised = (field: string, fn: string) =>
  formatMessage(defaultMessages, 'label.summary.of', {
    field,
    fn: defaultMessages[`label.summary.fn.${fn}` as const],
  });

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: APPLY });
}

describe('the analysis tray', () => {
  /**
   * D20 settles Q2: the analysis editor sits where the record view's filter
   * panel sits — folded under a button in the title bar — rather than always
   * open above the result. The result is the point of the view, and a saved
   * view's author has already decided what it asks.
   */
  it('opens a saved view folded, and the toggle opens the tray', async () => {
    await open({ fold: false });

    expect(tray()).toBeNull();
    expect(analysisToggle().getAttribute('aria-expanded')).toBe('false');

    fireEvent.click(analysisToggle());

    await waitFor(() => expect(tray()).not.toBeNull());
    expect(analysisToggle().getAttribute('aria-expanded')).toBe('true');
  });

  /**
   * The analyst's order (D20): the range first, because it is what the
   * numbers are over, then what they are cut by beside what is measured.
   * The tray holds the question and nothing about how it is looked at — the
   * layout and the chart type are the result's, on its toolbar.
   */
  it('lays the slots out as range, then dimensions beside metrics, then the result', async () => {
    await open();
    const slots = tray()!.querySelectorAll('[data-slot^="analysis-slot-"]');

    expect([...slots].map(slot => slot.getAttribute('data-slot'))).toEqual([
      'analysis-slot-range',
      'analysis-slot-dimensions',
      'analysis-slot-metrics',
      'analysis-slot-result',
    ]);
    // Each is a named region, in the analyst's words.
    for (const name of ['Range', 'Dimensions', 'Metrics', 'Result'])
      expect(screen.getByRole('region', { name })).toBeDefined();
    // The range slot is the record view's condition panel, unchanged.
    expect(
      within(screen.getByRole('region', { name: 'Range' })).getByRole(
        'region',
        { name: 'Filter' },
      ),
    ).toBeDefined();
    // One card per dimension and per metric; the sort is the result's.
    expect(
      tray()!.querySelectorAll('[data-slot="dimension-card"]'),
    ).toHaveLength(1);
    expect(tray()!.querySelectorAll('[data-slot="metric-card"]')).toHaveLength(
      1,
    );
    expect(
      screen
        .getByRole('region', { name: 'Result' })
        .querySelector('[data-slot="analysis-sort"]'),
    ).not.toBeNull();
    expect(
      screen
        .getByRole('region', { name: 'Metrics' })
        .querySelector('[data-slot="analysis-sort"]'),
    ).toBeNull();
    // Nothing about how the result is looked at is in here.
    expect(
      within(tray()!).queryByRole('button', {
        name: defaultMessages['label.layout.chart'],
      }),
    ).toBeNull();
  });

  /**
   * The tray is one block of rows: the slots stand a row apart, the cards
   * inside a slot stand a group apart, and the two columns are two blocks.
   *
   * jsdom applies no stylesheet, so what is pinned here is the class the
   * call site passes; the pixels are measured in the browser project
   * (`stories/view-engine/AnalysisWorkbench.test.stories.tsx`,
   * `EditorRowSpacing`).
   */
  it('steps the tray’s rows by the ruler', async () => {
    await open();

    expect(tray()!.classList.contains(SPACE.ROWS)).toBe(true);
    // The two columns are two blocks of the tray, not two of its rows.
    const grid = tray()!.querySelector<HTMLElement>('.grid')!;
    expect(grid.classList.contains(SPACE.BLOCKS)).toBe(true);
    for (const name of ['range', 'dimensions', 'metrics'])
      expect(
        tray()!
          .querySelector(`[data-slot="analysis-slot-${name}"]`)!
          .classList.contains(SPACE.GROUPS),
      ).toBe(true);
  });

  /**
   * D17-3. The range's conditions and the question are one config, and one
   * `runtime.apply()` runs the lot, so the tray has one footer and one
   * button that runs anything — Apply. There is no second submit any more:
   * the aggregation editor's Run was the other way into the same execution,
   * and a screen with two ways in had to demote one of them.
   *
   * **Surviving class assertions.** Emphasis is a fill and nothing else —
   * there is no state on a button that says "this one is the primary" — so
   * the variant's class is the only witness jsdom has. What the fills come
   * to is measured in the browser.
   */
  it('carries one primary button on the screen, and it is Apply', async () => {
    await open({ autoRun: false });

    const primary = Array.from(
      document.querySelectorAll<HTMLElement>('[data-slot="button"]'),
    ).filter(button => button.classList.contains('bg-primary'));

    expect(primary.map(button => button.textContent?.trim())).toEqual([APPLY]);
    expect(applyButton().getAttribute('data-emphasis')).toBe('primary');
    expect(within(tray()!).queryByRole('button', { name: /Run/ })).toBeNull();
  });

  /**
   * With auto-run on, the question runs itself, so an Apply with nothing to
   * do rests as an outline button (2026-09-23 audit): a filled primary is
   * the loudest thing on the screen, and it was asking for a press that did
   * nothing new. The range still waits for Apply, so a condition edited
   * there lifts it back to primary, with the dot.
   */
  it('rests Apply while auto-run leaves it nothing to do, and lifts it when something waits', async () => {
    const user = userEvent.setup();
    await open();

    expect(applyButton().getAttribute('data-emphasis')).toBe('quiet');
    expect(applyButton().hasAttribute('data-pending')).toBe(false);
    // Nothing on the screen is filled: there is no press anything waits for.
    expect(
      Array.from(
        document.querySelectorAll<HTMLElement>('[data-slot="button"]'),
      ).filter(button => button.classList.contains('bg-primary')),
    ).toEqual([]);

    // A condition in the range is not the question: it waits for Apply.
    await addConditions(['Warehouse']);

    await waitFor(() =>
      expect(applyButton().getAttribute('data-emphasis')).toBe('primary'),
    );
    expect(applyButton().hasAttribute('data-pending')).toBe(true);

    await user.click(applyButton());
    await waitFor(() =>
      expect(applyButton().getAttribute('data-emphasis')).toBe('quiet'),
    );
  });

  /**
   * The tray's conditions are the range, and its two ways back say so: a
   * record view's 'Discard edits' under the tray read as undoing the whole
   * tray (the 2026-09-23 audit, P2-4).
   */
  it('says the range in the ways back from its conditions', async () => {
    await open();
    await addConditions(['Warehouse']);

    expect(
      await screen.findByRole('button', { name: 'Discard range edits' }),
    ).toBeDefined();
    expect(screen.getByRole('button', { name: 'Clear range' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Discard edits' })).toBeNull();
  });

  /**
   * D17-6: "changed, not applied" is read off the whole draft, and the one
   * button that runs it wears the dot — whichever slot the change is in. It
   * used to be the aggregation editor's own Run that carried the analysis
   * half, so a dimension added and a condition typed lit two different
   * buttons.
   */
  it('marks Apply while any slot holds something that has not run', async () => {
    // Apply is how the question runs here; with auto-run on it would run
    // itself, which `test/autoRun.test.tsx` covers.
    await open({ autoRun: false });
    expect(applyButton().hasAttribute('data-pending')).toBe(false);

    // A change in the question's half, which the filter knows nothing about.
    await add('Add metric', 'Amount');
    await waitFor(() =>
      expect(applyButton().hasAttribute('data-pending')).toBe(true),
    );
    expect(
      applyButton().querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();

    fireEvent.click(applyButton());
    await waitFor(() =>
      expect(applyButton().hasAttribute('data-pending')).toBe(false),
    );
  });

  /**
   * Folded away, the toggle is the one thing that can say the draft moved,
   * so it carries the dot and the count.
   *
   * The count is the filter editor's, which compares the **whole** config —
   * dimensions and metrics included — so the analysis half adds nothing on
   * top of it. It used to: `pendingCount + (analysis.pending ? 1 : 0)` said
   * "2 not applied" for one edited number, and a count nobody can match to
   * what they did is worse than no count.
   */
  it('wears the pending dot and one count on the toggle while folded away', async () => {
    // The row limit is part of the question, so with auto-run on it would
    // apply itself a moment later and take the dot with it.
    await open({ autoRun: false });

    // One member of the config edited, which is one thing not applied.
    fireEvent.change(screen.getByLabelText('Top N groups'), {
      target: { value: '25' },
    });
    fireEvent.click(analysisToggle());

    await waitFor(() => expect(tray()).toBeNull());
    expect(
      analysisToggle().querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();
    expect(analysisToggle().textContent).toContain(
      formatMessage(defaultMessages, 'label.editor.pending', { count: 1 }),
    );
  });

  /**
   * There is no simple/advanced *tray* (D20) — more capability grows out of
   * the slots as the definition declares it. The one simple/advanced left is
   * the grammar of the condition tree, and it is stated where the conditions
   * are: a ghost menu in the range slot's heading row. Without it an analysis
   * view could never reach OR, NOR or a nested group, because
   * `defaultAnalysisConfig` starts at simple.
   *
   * **Beside the heading, never inside it.** A heading names what follows;
   * a control inside one is read as part of that name — 「Range Conditions:
   * Simple」 — and a reader jumping from heading to heading lands on a menu
   * trigger it never asked for.
   */
  it('reaches the condition grammar from the range slot’s heading', async () => {
    await open();
    const range = () => screen.getByRole('region', { name: 'Range' });
    expect(
      within(range()).queryByRole('group', { name: 'All conditions' }),
    ).toBeNull();

    const mode = within(range()).getByText(
      defaultMessages['label.analysis.conditions-mode'].replace(
        '{mode}',
        defaultMessages['label.filter.simple'],
      ),
      { exact: false },
    );
    expect(mode.closest('h3')).toBeNull();
    expect(within(range()).getByRole('heading', { level: 3 }).textContent).toBe(
      'Range',
    );

    fireEvent.click(mode);
    fireEvent.click(
      await screen.findByRole('menuitemradio', {
        name: defaultMessages['label.filter.advanced'],
      }),
    );

    // Advanced draws the root as a group, which is what carries the operator.
    await waitFor(() =>
      expect(
        within(range()).getByRole('group', { name: 'All conditions' }),
      ).toBeDefined(),
    );
  });

  /**
   * F11: a config admission refuses runs nothing, so the finding is about
   * the tray — and the tray may be folded away. The strip carries the way
   * back to it.
   */
  it('opens the tray from a config that will not run', async () => {
    await open({ config: { limit: 0 }, fold: false });

    const strip = await screen.findByRole('alert');
    fireEvent.click(
      within(strip).getByRole('button', {
        name: defaultMessages['label.analysis.open-editor'],
      }),
    );

    await waitFor(() => expect(tray()).not.toBeNull());
  });

  /**
   * The editor used to label its controls with the identifier itself —
   * `terms`, `sum` — so a host that handed over `zhCN` still got an English
   * tray. Everything reads the catalogue now, the slots included, and the
   * names are the field's display name rather than its alias (D20).
   */
  it('translates the tray with zhCN', async () => {
    await open({ messages: zhCN });

    for (const name of ['范围', '维度', '指标'])
      expect(screen.getByRole('region', { name })).toBeDefined();
    expect(
      document.querySelector('[data-slot="dimension-card"]')!.textContent,
    ).toContain('按值');

    await add('添加维度', 'Created');
    expect(
      (await screen.findByLabelText('Created 的维度设置')).textContent,
    ).toContain('按时间粒度');

    await add('添加指标', 'Amount');
    expect(
      (await screen.findByLabelText('Amount 的汇总方式')).textContent,
    ).toContain('总和');
  });
});

describe('the tray’s dimension cards', () => {
  it('starts each dimension at the shape its capability allows', async () => {
    await open();

    await add('Add dimension', 'Created');
    expect(
      (await screen.findByLabelText('Dimension settings for Created'))
        .textContent,
    ).toContain('By time unit');

    await add('Add dimension', 'Amount');
    expect(
      (await screen.findByLabelText('Dimension settings for Amount'))
        .textContent,
    ).toContain('By number range');
  });

  /**
   * A field that can be cut one way only has nothing to choose, so the card
   * says the cut as a word rather than as a select of one option — a
   * control that cannot be changed teaches nothing and takes a tab stop.
   */
  it('says the one cut a field has as a word, not as a select', async () => {
    await open();
    const card = document.querySelector<HTMLElement>(
      '[data-slot="dimension-card"][data-field="warehouse"]',
    )!;

    expect(card.textContent).toContain('By value');
    expect(
      within(card).queryByLabelText('Dimension settings for Warehouse'),
    ).toBeNull();
  });

  /** A time dimension's second control is the bucket it cuts into. */
  it('takes a granularity for a time dimension', async () => {
    const user = userEvent.setup();
    const { engine } = await open();

    await add('Add dimension', 'Created');
    const granularity = await screen.findByLabelText('Granularity');
    // Only the units the capability declares, named by the catalogue.
    expect(granularity.textContent).toContain('By month');

    await user.click(granularity);
    await user.click(await screen.findByRole('option', { name: 'By day' }));

    await waitFor(() =>
      expect(draft(engine).groups[1]).toMatchObject({
        type: 'DATE_HISTOGRAM',
        unit: 'DAY',
      }),
    );
  });

  /** A number dimension's second control is how wide one band is. */
  it('takes a band width for a number dimension', async () => {
    const { engine } = await open();

    await add('Add dimension', 'Amount');
    fireEvent.change(await screen.findByLabelText('Band width'), {
      target: { value: '250' },
    });

    await waitFor(() =>
      expect(draft(engine).groups[1]).toMatchObject({
        type: 'HISTOGRAM',
        interval: 250,
      }),
    );
  });

  /**
   * "The first N groups" is only readable next to what orders them, so the
   * sort and the limit are one row of the result slot, each under a label
   * of its own — and a sort needs a dimension, so the slot waits for one.
   */
  it('takes the top N groups beside what orders them', async () => {
    const { engine, source } = await open();
    const result = () =>
      document.querySelector<HTMLElement>('[data-slot="analysis-slot-result"]');
    const order = result()!.querySelector<HTMLElement>(
      '[data-slot="analysis-order"]',
    )!;
    // Both sit in the one row, the sort first: the N is the first N of it.
    expect(
      [...order.children].map(child => child.getAttribute('data-slot')),
    ).toEqual(['analysis-sort', 'analysis-limit']);
    // Each is labelled on the screen, not only to a reader.
    expect(
      within(order).getByRole('group', {
        name: defaultMessages['label.sort.title'],
      }),
    ).toBeDefined();
    const box = within(order).getByLabelText<HTMLInputElement>('Top N groups');
    expect(document.querySelector(`label[for="${box.id}"]`)?.textContent).toBe(
      'Top N groups',
    );

    fireEvent.change(box, {
      target: { value: '25' },
    });
    fireEvent.click(applyButton());
    // 26, not 25: the query asks for one row more than the reader wants, so
    // the answer says whether a 26th group exists (`analysisProbeLimit`).
    await waitFor(() =>
      expect(
        vi
          .mocked(source.aggregate)
          .mock.calls.some(call => call[0].limit === 26),
      ).toBe(true),
    );

    // The last dimension leaving takes the slot with it.
    fireEvent.click(
      screen.getByRole('button', { name: 'Remove dimension Warehouse' }),
    );
    await waitFor(() => expect(result()).toBeNull());
    expect(draft(engine).sort).toEqual([]);
  });
});

describe('the tray’s metric cards', () => {
  /**
   * The six ways Wow can measure a field are one list on the card (D20
   * 汇总方式), so a field is added as the first way it offers and the select
   * holds the rest of them. A field that offers one way still gets the
   * select — it is the card's name for what the metric is, and it is the
   * only place the word appears.
   */
  it('picks the metric shape each field can support', async () => {
    const user = userEvent.setup();
    const { engine } = await open();
    const metrics = () => draft(engine).metrics;

    await add('Add metric', 'Amount');
    const summary = await screen.findByLabelText('Summary for Amount');
    expect(summary.textContent).toContain('Sum');
    await user.click(summary);
    expect(
      (await screen.findAllByRole('option')).map(option => option.textContent),
      // «Any value» is the one choice that promises nothing about the value
      // it returns, and says so where the choice is made.
    ).toEqual([
      'Sum',
      'Average',
      'Distinct count',
      'Percentile',
      'Any value (not stable)',
    ]);
    await user.keyboard('{Escape}');

    // A field that declares only a distinct count, and one that declares
    // only a sample value, start as exactly that.
    await add('Add metric', 'Customer');
    await screen.findByRole('button', {
      name: `Remove metric ${summarised('Customer', 'DISTINCT_COUNT')}`,
    });
    expect(metrics()[2]).toMatchObject({ type: 'DISTINCT_COUNT' });

    await add('Add metric', 'Note');
    await screen.findByRole('button', {
      name: `Remove metric ${summarised('Note', 'ANY')}`,
    });
    expect(metrics()[3]).toMatchObject({ type: 'ANY', field: 'note' });
    expect(engine.openRuntimes()[0].getSnapshot().issues).toEqual([]);
  });

  /**
   * «Any value» returns some value of the group and promises nothing about
   * which, so two runs of one analysis may disagree (D20 口径). The card at
   * rest says so in a sentence — the parenthesis on the menu item is only
   * read while the menu is open, and by then the choice is being made.
   */
  it('says on the card that an any-value metric is not stable', async () => {
    await open();
    const notes = () =>
      [...document.querySelectorAll('[data-slot="metric-note"]')].map(
        node => node.textContent,
      );

    // The record count is on the card already and carries no note.
    expect(notes()).toEqual([]);

    await add('Add metric', 'Note');
    await screen.findByRole('button', {
      name: `Remove metric ${summarised('Note', 'ANY')}`,
    });

    expect(notes()).toEqual([
      'Any value: it may differ from one run to the next.',
    ]);
  });

  /**
   * A second plain record count is the first one again — one column twice
   * under two aliases (the 2026-09-23 audit, P2-6) — so the menu offers the
   * count only while there is none. A count over some records is the card's
   * 「复制并加条件」.
   */
  it('offers the record count only while the analysis has none', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: 'Add metric' }));
    const count = await screen.findByRole('menuitem', {
      name: 'Record count',
    });
    expect(count.getAttribute('aria-disabled')).toBe('true');
    fireEvent.keyDown(count, { key: 'Escape' });

    // Taken out, it is offered again, and added it is named by what it
    // counts, not by the alias the query carries.
    await add('Add metric', 'Amount');
    fireEvent.click(
      await screen.findByRole('button', { name: 'Remove metric Record count' }),
    );
    await add('Add metric', 'Record count');
    expect(
      await screen.findAllByRole('button', {
        name: 'Remove metric Record count',
      }),
    ).toHaveLength(1);
  });

  /**
   * A change of summary is a change of metric *type* — a sum becomes a
   * distinct count becomes a sample value — so the card builds the whole
   * metric and swaps it in (`replaceMetric`). Patched over the old shape,
   * the `function` of the sum and the `expression` of the distinct count
   * stayed behind for admission to trip over.
   */
  it('swaps the whole metric when the summary changes', async () => {
    const user = userEvent.setup();
    const { engine } = await open();
    const metric = () =>
      draft(engine).metrics[1] as unknown as Record<string, unknown>;
    const choose = async (word: string) => {
      await user.click(await screen.findByLabelText('Summary for Amount'));
      await user.click(await screen.findByRole('option', { name: word }));
    };

    await add('Add metric', 'Amount');
    await waitFor(() => expect(metric().type).toBe('NUMERIC'));
    expect(metric().function).toBe('SUM');

    await choose('Distinct count');
    await waitFor(() => expect(metric().type).toBe('DISTINCT_COUNT'));
    expect(metric().function).toBeUndefined();

    await choose('Any value (not stable)');
    await waitFor(() => expect(metric().type).toBe('ANY'));
    expect(metric().expression).toBeUndefined();
    expect(metric().field).toBe('amount');
    // The alias the row was added under survives every swap: it names the
    // query, and the chart and the sort point at it.
    expect(metric().alias).toBe('amount_1');
    expect(engine.openRuntimes()[0].getSnapshot().issues).toEqual([]);
  });

  it('changes a metric summary and removes rows again', async () => {
    const user = userEvent.setup();
    await open();

    await add('Add metric', 'Amount');
    await user.click(await screen.findByLabelText('Summary for Amount'));
    await user.click(await screen.findByRole('option', { name: 'Average' }));
    await waitFor(() =>
      expect(screen.getByLabelText('Summary for Amount').textContent).toContain(
        'Average',
      ),
    );

    fireEvent.click(
      screen.getByRole('button', {
        name: `Remove metric ${summarised('Amount', 'AVG')}`,
      }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', {
          name: `Remove metric ${summarised('Amount', 'AVG')}`,
        }),
      ).toBeNull(),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Remove dimension Warehouse' }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove dimension Warehouse' }),
      ).toBeNull(),
    );
  });

  /**
   * Numbering by the row count reused a name the moment a row was removed:
   * two metrics called `amount_2`, which React saw as one key and validation
   * reported as a duplicate alias.
   *
   * The alias is no longer on screen — a control is named by the field's
   * display name (D20) — so the collision is asked for where it would land:
   * two metrics of one field are two rows, and applying them reports nothing
   * about a name used twice.
   */
  it('names a new row by the first free alias, not by the row count', async () => {
    await open();
    const removals = () =>
      screen.queryAllByRole('button', {
        name: `Remove metric ${summarised('Amount', 'SUM')}`,
      });

    await add('Add metric', 'Amount');
    await add('Add metric', 'Amount');
    await waitFor(() => expect(removals()).toHaveLength(2));

    fireEvent.click(removals()[0]);
    await waitFor(() => expect(removals()).toHaveLength(1));

    // The freed name comes back rather than colliding with the kept row's.
    await add('Add metric', 'Amount');
    await waitFor(() => expect(removals()).toHaveLength(2));

    fireEvent.click(applyButton());
    await waitFor(() => expect(screen.queryByText(/is used twice/)).toBeNull());
  });

  it('refuses to remove the only metric', async () => {
    await open();

    expect(
      (
        screen.getByRole('button', {
          name: 'Remove metric Record count',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  /** A percentile grows a third control, and Wow's interval is open. */
  it('takes the percentile a percentile metric is of', async () => {
    const user = userEvent.setup();
    const { engine } = await open();
    const metric = () => draft(engine).metrics[1];

    await add('Add metric', 'Amount');
    expect(screen.queryByLabelText('Percentile')).toBeNull();

    await user.click(await screen.findByLabelText('Summary for Amount'));
    await user.click(await screen.findByRole('option', { name: 'Percentile' }));
    await waitFor(() => expect(metric()).toMatchObject({ percentile: 95 }));

    fireEvent.change(screen.getByLabelText('Percentile'), {
      target: { value: '99' },
    });
    await waitFor(() => expect(metric()).toMatchObject({ percentile: 99 }));

    // 100 is not a percentile and 0 is none, so neither reaches the draft.
    fireEvent.change(screen.getByLabelText('Percentile'), {
      target: { value: '100' },
    });
    expect(metric()).toMatchObject({ percentile: 99 });
    expect(engine.openRuntimes()[0].getSnapshot().issues).toEqual([]);
  });
});

describe('the analysis result toolbar', () => {
  /**
   * The result's first row says what the numbers below it are, in one line
   * and in the analyst's words — read off the result that ran, never off the
   * draft being edited.
   */
  it('reads the result out as dimensions and metrics', async () => {
    await open({ fold: false });

    const reading = await waitFor(() =>
      document.querySelector<HTMLElement>('[data-slot="analysis-reading"]'),
    );
    expect(reading!.textContent).toBe('By Warehouse · Record count');
    expect(reading!.closest('[data-slot="result-toolbar"]')).not.toBeNull();
  });

  /**
   * How the result is looked at is the result's business, not the
   * question's (D20), so these apply at once rather than waiting for the
   * tray's Apply — the kernel shapes a chart only for what ran.
   */
  /**
   * The totals switch is a setting of the table, set with the table's other
   * settings in the visualization panel (`chartOptionsUi.test.tsx` runs it).
   * On the toolbar it was drawn by the table layout alone, so switching
   * 表格／图表 moved everything beside it (the user's 2026-09-23 review).
   */
  it('keeps the totals switch off the toolbar in either layout', async () => {
    await open({ fold: false });
    const toolbar = () =>
      document.querySelector<HTMLElement>('[data-slot="result-toolbar"]')!;
    expect(
      within(toolbar()).queryByRole('checkbox', { name: 'Totals row' }),
    ).toBeNull();

    cleanup();
    await open({ config: { layout: 'chart' } });
    expect(
      within(toolbar()).queryByRole('checkbox', { name: 'Totals row' }),
    ).toBeNull();
  });

  /**
   * The layout switch is two icons, as the record view's is: the pressed
   * segment says which layout is on, and each segment keeps its word as its
   * name and its tooltip — in the host's language.
   */
  it('draws the layout switch as named icons, in either language', async () => {
    const user = userEvent.setup();
    for (const messages of [defaultMessages, zhCN]) {
      await open({ messages, config: { layout: 'chart' } });
      const layout = within(
        document.querySelector<HTMLElement>('[data-slot="result-toolbar"]')!,
      ).getByRole('group', { name: messages['label.analysis.layout'] });
      const table = within(layout).getByRole('button', {
        name: messages['label.layout.table'],
        pressed: false,
      });
      const chart = within(layout).getByRole('button', {
        name: messages['label.layout.chart'],
        pressed: true,
      });
      for (const segment of [table, chart]) {
        expect(segment.textContent).toBe('');
        expect(segment.querySelector('svg')).not.toBeNull();
      }

      await user.hover(table);
      const tip = await waitFor(() => {
        const content = document.querySelector('[data-slot="tooltip-content"]');
        expect(content).not.toBeNull();
        return content!;
      });
      expect(tip.textContent).toBe(messages['label.layout.table']);
      await user.unhover(table);
      cleanup();
    }
  });

  it('keeps the way into the visualization beside the layout switch, not in the tray', async () => {
    await open({ config: { layout: 'chart' } });
    const toolbar = document.querySelector<HTMLElement>(
      '[data-slot="result-toolbar"]',
    )!;

    // How the result is looked at is the result's business (D20): the
    // picker of chart types opens from here, and the tray holds none of it.
    expect(
      within(toolbar).getByRole('button', {
        name: defaultMessages['label.analysis.visualize'],
      }),
    ).toBeDefined();
    expect(
      within(tray()!).queryByRole('button', {
        name: defaultMessages['label.analysis.visualize'],
      }),
    ).toBeNull();
  });
});
