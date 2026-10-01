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
} from '@ahoo-wang/wow-client';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
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
import {
  analysisConfig,
  ordersDefinition,
  testSource,
  resourcesOf,
} from './fixtures.js';
import { settle } from './fixtures/ui.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/**
 * The expansion slot (D20 屏 G): the chain of arrays an analysis counts
 * inside, one card a level, each with its own gate on the entries it lets
 * through, and a footer saying what is being counted.
 *
 * What `withElements` keeps and drops is `test/expand.test.ts`; what the
 * chain compiles to is `test/analysisCompile.test.ts` and what it is
 * admitted against is `test/analysisValidate.test.ts`. This suite is the
 * slot: when it exists, what a press of it does to the question on screen,
 * and what leaves with a level.
 */

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

/** Orders holding items holding batches: a declared two-level chain. */
function chainedDefinition(): DataViewDefinition {
  return ordersDefinition({
    recordNoun: 'Orders',
    fields: [
      { name: 'id', label: 'Order', kind: 'string', sortable: true },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'amount', label: 'Amount', kind: 'number' },
      {
        name: 'items',
        label: 'Items',
        kind: 'array',
        elements: [
          { name: 'sku', label: 'SKU', kind: 'string' },
          { name: 'qty', label: 'Qty', kind: 'number' },
          // An element search (N4): Wow takes none in an expansion's gate.
          {
            name: 'q',
            label: 'Search items',
            kind: 'search',
            searchFields: ['sku'],
          },
          {
            name: 'batches',
            label: 'Batches',
            kind: 'array',
            elements: [{ name: 'lot', label: 'Lot', kind: 'string' }],
          },
        ],
      },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        { field: 'amount', groups: [], functions: [AggregationFunction.SUM] },
      ],
      elements: [
        {
          path: 'items',
          aggregations: [
            {
              field: 'sku',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
            { field: 'qty', groups: [], functions: [AggregationFunction.SUM] },
          ],
        },
        {
          path: 'batches',
          aggregations: [
            {
              field: 'lot',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
      ],
    },
  });
}

/** The same dataset with nothing to expand: no chain, so no slot. */
function flatDefinition(): DataViewDefinition {
  const chained = chainedDefinition();
  return ordersDefinition({
    fields: chained.fields,
    analysis: {
      count: true,
      fields: chained.analysis!.fields,
    },
  });
}

/** A saved analysis view on screen, its tray opened. */
async function open({
  definition = chainedDefinition(),
  config = {},
}: {
  definition?: DataViewDefinition;
  config?: Partial<AnalysisViewConfig>;
} = {}) {
  const source = testSource();
  const store = new MemoryViewStore({
    instances: [{ ...analysisView, config: analysisConfig(config) }],
  });
  const engine = new ViewEngine({
    resources: resourcesOf([definition], () => source),
    store,
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

const slot = () =>
  document.querySelector<HTMLElement>('[data-slot="analysis-slot-elements"]');

const levels = () =>
  [...document.querySelectorAll<HTMLElement>('[data-slot="element-card"]')].map(
    card => card.getAttribute('data-path'),
  );

const unit = () =>
  document.querySelector<HTMLElement>('[data-slot="counting-unit"]')
    ?.textContent;

const block = () =>
  document.querySelector<HTMLElement>('[data-slot="card-conditions"]');

/** The one button that walks the chain one step further, if there is one. */
const expandInto = () =>
  document.querySelector<HTMLElement>('[data-slot="expand-into"]');

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

/** Presses 「Expand into {name}」 and waits for the level to arrive. */
async function expand(name: string): Promise<void> {
  const depth = levels().length;
  fireEvent.click(
    screen.getByRole('button', {
      name: label('label.analysis.expand-into', { name }),
    }),
  );
  await waitFor(() => expect(levels()).toHaveLength(depth + 1));
}

/** Ticking a field is what adds its condition; Done is the way out. */
async function addCondition(scope: string, field: string): Promise<void> {
  fireEvent.click(
    screen.getByRole('button', {
      name: `${scope} ${defaultMessages['label.filter.add-condition']}`,
    }),
  );
  const picker = await screen.findByRole('dialog', {
    name: defaultMessages['label.filter.pick-fields'],
  });
  fireEvent.click(within(picker).getByRole('checkbox', { name: field }));
  fireEvent.click(
    within(picker).getByRole('button', {
      name: defaultMessages['label.filter.pick-done'],
    }),
  );
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
}

/** How many aggregations the source has been asked for so far. */
const asked = (source: ViewSource) =>
  vi.mocked(source.aggregate).mock.calls.length;

describe('the expansion slot', () => {
  /**
   * The chain is the capability's, so a dataset that declares none has no
   * slot at all — an empty 「展开」 heading over a dataset with nothing to
   * expand is a promise the definition never made. Where there is a chain,
   * it is the tray's first row (D71), because it decides which fields every
   * row after it may name.
   */
  it('exists only where the capability declares a chain', async () => {
    await open({ definition: flatDefinition() });
    expect(slot()).toBeNull();

    cleanup();
    await open();

    expect(slot()).not.toBeNull();
    expect(
      [
        ...document.querySelectorAll('section[data-slot^="analysis-slot-"]'),
      ].map(found => found.getAttribute('data-slot')),
    ).toEqual([
      'analysis-slot-elements',
      'analysis-slot-metrics',
      'analysis-slot-dimensions',
      'analysis-slot-result',
      'analysis-slot-range',
    ]);
    expect(
      screen.getByRole('region', {
        name: defaultMessages['label.analysis.slot.elements'],
      }),
    ).toBeDefined();
    // Nothing expanded: the records are what is counted, and the slot says
    // so under the one step there is to take.
    expect(levels()).toEqual([]);
    // By the definition's record noun, not its title (2026-09-23 audit: a
    // console titled 「事件流分析台」 was counting consoles).
    expect(unit()).toBe(label('label.analysis.unit', { name: 'Orders' }));
    expect(
      document
        .querySelector('[data-slot="counting-unit"]')
        ?.hasAttribute('data-emphasis'),
    ).toBe(false);
    // The add button shows the array's name, 「+ 商品」, and is named by the
    // step it takes.
    expect(expandInto()?.textContent).toBe('Items');
    // Not chosen yet, so dashed (review of #3781); a level card is not.
    expect(expandInto()?.getAttribute('data-affordance')).toBe('add');
    expect(expandInto()?.getAttribute('aria-label')).toBe(
      label('label.analysis.expand-into', { name: 'Items' }),
    );
  });

  /**
   * Expanding re-scopes the question: after 订单 → 明细项 a dimension over
   * the order's warehouse asks about the wrong thing and Wow refuses it, so
   * it leaves with the step. The count survives — an item is countable as
   * an order is — and the footer says what a number now counts, because
   * after an expansion it is no longer a record.
   */
  it('takes a step along the chain, and re-scopes the question', async () => {
    const { engine } = await open();

    await expand('Items');

    expect(levels()).toEqual(['items']);
    expect(draft(engine).elements).toEqual([{ path: 'items' }]);
    // The warehouse is the order's; inside an item it names nothing.
    expect(draft(engine).groups).toEqual([]);
    expect(draft(engine).metrics).toMatchObject([{ type: 'COUNT' }]);
    expect(
      document.querySelectorAll('[data-slot="dimension-card"]'),
    ).toHaveLength(0);
    expect(unit()).toBe(label('label.analysis.unit', { name: 'Items' }));
    // Said louder once something is expanded: it is no longer the record.
    expect(
      document
        .querySelector('[data-slot="counting-unit"]')
        ?.getAttribute('data-emphasis'),
    ).toBe('strong');
    // One line, one step at a time: the next thing to expand is the next
    // thing the capability declares, and nothing else.
    expect(expandInto()?.textContent).toBe('Batches');

    await expand('Batches');

    expect(levels()).toEqual(['items', 'batches']);
    expect(unit()).toBe(label('label.analysis.unit', { name: 'Batches' }));
    // The chain is walked out, so there is nothing left to offer.
    expect(expandInto()).toBeNull();
  });

  /**
   * A level's gate decides which entries are expanded at all, so it is
   * written in that level's own names: the item's fields, never the order's.
   * An unfinished one lets every entry through, so the query waits for it
   * and the strip says which field is waiting — reported under this level,
   * which is how the pill under this card is the one that wears the mark.
   */
  it('gates a level over that level’s own fields', async () => {
    const { source } = await open();
    await expand('Items');

    fireEvent.click(
      screen.getByRole('button', {
        name: label('label.analysis.element-condition-of', { name: 'Items' }),
      }),
    );
    const opened = await screen.findByRole('group', {
      name: label('label.analysis.element-condition-of', { name: 'Items' }),
    });
    expect(opened.getAttribute('data-slot')).toBe('card-conditions');
    expect(opened.textContent).toContain(
      defaultMessages['label.analysis.element-condition-title'],
    );

    fireEvent.click(
      screen.getByRole('button', {
        name: `Items ${defaultMessages['label.filter.add-condition']}`,
      }),
    );
    const picker = await screen.findByRole('dialog', {
      name: defaultMessages['label.filter.pick-fields'],
    });
    for (const field of ['SKU', 'Qty'])
      expect(
        within(picker).getByRole('checkbox', { name: field }),
      ).toBeDefined();
    for (const field of ['Warehouse', 'Search items'])
      expect(
        within(picker).queryByRole('checkbox', { name: field }),
      ).toBeNull();
    fireEvent.click(within(picker).getByRole('checkbox', { name: 'SKU' }));
    fireEvent.click(
      within(picker).getByRole('button', {
        name: defaultMessages['label.filter.pick-done'],
      }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    const pill = await waitFor(() => {
      const found = within(block()!).getByRole('group', {
        name: label('label.filter.condition-of', { field: 'SKU' }),
      });
      expect(found.hasAttribute('data-invalid')).toBe(true);
      return found;
    });
    expect(pill.getAttribute('data-slot')).toBe('filter-condition');

    const before = asked(source);
    fireEvent.click(applyButton());

    // The condition's field is said by its label, never by its path.
    const strip = (await screen.findByRole('alert')).textContent;
    expect(strip).toContain(
      label('analysis.elementFilter.incomplete', { field: 'SKU' }),
    );
    expect(strip).not.toContain('items.sku');
    expect(asked(source)).toBe(before);
  });

  /**
   * The chain sends its levels and their gates with the query, each named
   * as Wow reads it: the level's path relative to the one outside it, and
   * the gate over the entry's own fields rather than over the document's.
   */
  it('sends the chain and each gate with the query', async () => {
    const { source } = await open();
    await expand('Items');
    fireEvent.click(
      screen.getByRole('button', {
        name: label('label.analysis.element-condition-of', { name: 'Items' }),
      }),
    );
    await addCondition('Items', 'SKU');
    fireEvent.change(
      within(block()!).getByLabelText(
        label('label.filter.value-of', { field: 'SKU' }),
      ),
      { target: { value: 'A-1' } },
    );

    const before = asked(source);
    fireEvent.click(applyButton());

    await waitFor(() => expect(asked(source)).toBeGreaterThan(before));
    const calls = vi.mocked(source.aggregate).mock.calls;
    const query = calls[calls.length - 1]![0];
    expect(query.elements).toMatchObject([
      { path: 'items', filter: { op: FilterOperator.EQ, field: 'sku' } },
    ]);
  });

  /**
   * A level taken out takes every level inside it: 批次 only exists inside
   * 明细项, so collapsing the item leaves nothing to hold it. The counting
   * unit goes back to the records, and the slot offers the first step again.
   */
  /**
   * A definition that names no record noun counts 「records」 — the
   * catalogue's own word — and never its title, which names the dataset.
   */
  it('counts records by the catalogue’s word where the definition names none', async () => {
    const unnamed = chainedDefinition();
    delete unnamed.recordNoun;
    await open({ definition: { ...unnamed, title: 'Event console' } });
    expect(unit()).toBe(
      label('label.analysis.unit', {
        name: defaultMessages['label.analysis.records'],
      }),
    );
  });

  it('collapses a level, and everything inside it', async () => {
    const { engine } = await open();
    await expand('Items');
    await expand('Batches');

    fireEvent.click(
      screen.getAllByRole('button', {
        name: label('label.analysis.collapse', { name: 'Items' }),
      })[0]!,
    );

    await waitFor(() => expect(levels()).toEqual([]));
    expect(draft(engine).elements).toEqual([]);
    expect(unit()).toBe(label('label.analysis.unit', { name: 'Orders' }));
    expect(expandInto()?.textContent).toBe('Items');
    // Nothing is left to measure the order by, so the metrics start again
    // from the first thing the unit can count.
    expect(draft(engine).metrics).toMatchObject([{ type: 'COUNT' }]);
  });
});

describe('the keyboard after a step along the chain', () => {
  /**
   * A step to the chain's last level takes 「Expand into …」 with it, and
   * the keyboard on it fell to `<body>` (second-round review, A11Y-4). It
   * lands on the card the step made; a step with a level after it keeps
   * the button, and the keyboard stays on it.
   */
  it('lands on the new card when the button goes with the step', async () => {
    await open();
    const first = screen.getByRole('button', {
      name: label('label.analysis.expand-into', { name: 'Items' }),
    });
    first.focus();
    await expand('Items');
    expect(document.activeElement).toBe(expandInto());

    expandInto()!.focus();
    await expand('Batches');
    expect(expandInto()).toBeNull();
    const cards = document.querySelectorAll('[data-slot="element-card"]');
    await waitFor(() =>
      expect(cards[cards.length - 1]!.contains(document.activeElement)).toBe(
        true,
      ),
    );
  });
});

describe('what an expansion takes away (D71)', () => {
  const notice = () =>
    document.querySelector<HTMLElement>('[data-slot="dropped-notice"]');
  const voice = () =>
    document.querySelector<HTMLElement>('[data-slot="dropped-voice"]');

  /**
   * A step into the chain takes every dimension and metric outside the new
   * unit with it (D20: they follow rather than fail); nothing asks first,
   * so the tray says what went — counted and named as their columns were
   * headed — and says it to a reader too.
   */
  it('says what went, by name, on screen and aloud', async () => {
    await open({
      config: {
        metrics: [
          { type: 'COUNT', alias: 'n' },
          {
            type: 'NUMERIC',
            alias: 'gmv',
            label: 'GMV',
            function: AggregationFunction.SUM,
            expression: { type: 'FIELD', field: 'amount' },
          },
          {
            type: 'NUMERIC',
            alias: 'total',
            function: AggregationFunction.SUM,
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
      },
    });
    expect(notice()).toBeNull();

    await expand('Items');

    const sentence = label('label.analysis.dropped.expand', {
      name: 'Items',
      what: label('label.analysis.dropped.both', {
        first: label('label.analysis.dropped.dimension', {
          names: 'Warehouse',
        }),
        second: label('label.analysis.dropped.metrics', {
          count: '2',
          names: `GMV, ${label('label.summary.of', {
            field: 'Amount',
            fn: defaultMessages['label.summary.fn.SUM'],
          })}`,
        }),
      }),
    });
    expect(notice()?.textContent).toContain(sentence);
    await waitFor(() => expect(voice()?.textContent).toBe(sentence));
    // No confirmation: the step is made.
    expect(levels()).toEqual(['items']);
  });

  /** 撤销 puts the question back as it was before the step. */
  it('undoes the step, and the config is back as it was', async () => {
    const { engine } = await open();
    const before = draft(engine);

    await expand('Items');
    expect(draft(engine).groups).toEqual([]);

    fireEvent.click(
      within(notice()!).getByRole('button', {
        name: defaultMessages['label.analysis.dropped.undo'],
      }),
    );

    await waitFor(() => expect(levels()).toEqual([]));
    const after = draft(engine);
    expect(after.groups).toEqual(before.groups);
    expect(after.metrics).toEqual(before.metrics);
    expect(after.elements ?? []).toEqual(before.elements ?? []);
    expect(after.chart).toEqual(before.chart);
    expect(after.sort).toEqual(before.sort);
    expect(notice()).toBeNull();
    // Said, and still said once the notice has gone: the render that takes
    // the notice away does not empty the region in the same breath.
    const undone = defaultMessages['label.analysis.dropped.undone'];
    await waitFor(() => expect(voice()?.textContent).toBe(undone));
    await settle();
    expect(voice()?.textContent).toBe(undone);
  });

  /**
   * Both buttons take the notice away, and the keyboard on it fell to
   * `<body>`: the landing was looked up from the notice once it had gone
   * (second-round review, A11Y-3). It stays in the chain's row — on
   * 「Expand into Items」 after 撤销, on the card the step made after ✕.
   */
  it('keeps the keyboard in the chain’s row when the notice goes', async () => {
    await open();
    await expand('Items');
    const undo = within(notice()!).getByRole('button', {
      name: defaultMessages['label.analysis.dropped.undo'],
    });
    undo.focus();
    fireEvent.click(undo);
    await waitFor(() => expect(levels()).toEqual([]));
    await waitFor(() => expect(document.activeElement).toBe(expandInto()));

    await expand('Items');
    const dismiss = within(notice()!).getByRole('button', {
      name: defaultMessages['label.analysis.dropped.dismiss'],
    });
    dismiss.focus();
    fireEvent.click(dismiss);
    await waitFor(() => expect(notice()).toBeNull());
    const card = document.querySelector('[data-slot="element-card"]')!;
    await waitFor(() =>
      expect(card.contains(document.activeElement)).toBe(true),
    );
  });

  /** The notice is about the edit that made the draft; the next edit ends it. */
  it('goes with the next edit, and says nothing when nothing went', async () => {
    await open({ config: { groups: [] } });

    await expand('Items');
    // Only a count, which an item has too: nothing went.
    expect(notice()).toBeNull();

    cleanup();
    await open();
    await expand('Items');
    expect(notice()).not.toBeNull();

    await expand('Batches');
    await waitFor(() => expect(notice()).toBeNull());
  });
});
