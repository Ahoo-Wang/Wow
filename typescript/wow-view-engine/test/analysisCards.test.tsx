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
} from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_MISSING_KEY,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FilterTree,
  type ViewInstance,
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
 * The second layer of a tray card (D20 屏 B): the menu it carries, the
 * display name that menu opens, and the two choices Wow keeps behind a
 * dimension's bucketing — a group of its own for the records missing the
 * value, and the empty periods of a time dimension filled in. Plus the
 * granularity a new time dimension starts at (K4), which is what the
 * applied range recommends rather than whatever the field lists first.
 *
 * The tray itself — the fold, the slots, the first control of each card —
 * is `test/analysisTray.test.tsx`; what the controller does with an edit is
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

/**
 * One of everything, so every item of a card's menu is reachable:
 * `warehouse` is single-valued text and may carry the sentinel bucket,
 * `amount` is a number and may not, and `createdAt` offers three
 * granularities so a recommendation has something to choose between.
 */
function richDefinition(): DataViewDefinition {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string' },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
      { name: 'amount', label: 'Amount', kind: 'number' },
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
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [
            AggregationDateUnit.MONTH,
            AggregationDateUnit.WEEK,
            AggregationDateUnit.DAY,
          ],
        },
        {
          field: 'amount',
          groups: [AggregationGroupType.HISTOGRAM, AggregationGroupType.TERMS],
          functions: [AggregationFunction.SUM],
        },
      ],
    },
  });
}

const tray = () =>
  document.querySelector<HTMLElement>('[data-slot="analysis-tray"]');

/** A saved analysis view on screen, its tray opened. */
async function open(config: Partial<AnalysisViewConfig> = {}) {
  const store = new MemoryViewStore({
    instances: [{ ...analysisView, config: analysisConfig(config) }],
  });
  const engine = new ViewEngine({
    definitions: [richDefinition()],
    store,
    resolveSource: () => testSource(),
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
  return { engine };
}

/** The config being edited, an analysis config here by construction. */
function draft(engine: ViewEngine): AnalysisViewConfig {
  return engine.openRuntimes()[0].getSnapshot().draft as AnalysisViewConfig;
}

/** Picks an item out of one of the tray's two "+ Add …" menus. */
async function add(menu: string, item: string) {
  fireEvent.click(screen.getByRole('button', { name: menu }));
  fireEvent.click(await screen.findByRole('menuitem', { name: item }));
}

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

const label = (key: keyof typeof defaultMessages, name: string) =>
  formatMessage(defaultMessages, key, { name });

/**
 * One card's menu, by the name the card is known by. A checkbox item keeps
 * the menu open — a setting is toggled where it is read — so this answers
 * the one already open rather than pressing the trigger a second time.
 */
async function cardMenu(name: string): Promise<HTMLElement> {
  const trigger = screen.getByRole('button', {
    name: label('label.analysis.card-menu', name),
  });
  if (trigger.getAttribute('aria-expanded') !== 'true')
    fireEvent.click(trigger);
  return await screen.findByRole('menu');
}

/** Presses Escape on whatever menu is open, so the next gesture starts clean. */
function closeMenu() {
  const menu = document.querySelector<HTMLElement>('[role="menu"]');
  if (menu) fireEvent.keyDown(menu, { key: 'Escape' });
}

/** Opens the rename box of the card called `name`, and answers the box. */
async function renameBox(name: string): Promise<HTMLElement> {
  fireEvent.click(
    within(await cardMenu(name)).getByRole('menuitem', {
      name: defaultMessages['label.analysis.rename'],
    }),
  );
  return await screen.findByLabelText(
    label('label.analysis.display-name', name),
  );
}

/** Types into an open rename box and commits it with Enter. */
function commit(box: HTMLElement, text: string) {
  fireEvent.change(box, { target: { value: text } });
  fireEvent.keyDown(box, { key: 'Enter' });
}

const dimensionCard = (field: string) =>
  document.querySelector<HTMLElement>(
    `[data-slot="dimension-card"][data-field="${field}"]`,
  )!;

/**
 * A stored group or metric as the plain JSON it is, so a test can ask
 * whether a key is there at all rather than whether it reads `undefined`.
 */
const json = (value: object) => value as unknown as Record<string, unknown>;

const cardNames = () =>
  [...document.querySelectorAll('[data-slot="card-name"]')].map(
    name => name.textContent,
  );

describe('a tray card’s menu', () => {
  /**
   * A card holds the two or three controls the question is made of, and
   * everything rarer behind one trigger — a card with six controls on it
   * reads as a form, not as a sentence. The trigger is named after the card
   * rather than "More", so a screen full of cards has no two identical
   * buttons on it.
   */
  it('is named after its card and opens the display name', async () => {
    await open();

    const menu = await cardMenu('Warehouse');

    expect(
      dimensionCard('warehouse').querySelector('[data-slot="card-menu"]'),
    ).not.toBeNull();
    expect(
      within(menu).getByRole('menuitem', {
        name: defaultMessages['label.analysis.rename'],
      }),
    ).toBeDefined();
    // The metric card has the rename and the copy with a condition, and
    // nothing else: the bucket choices are a dimension's, because only a
    // dimension buckets anything.
    closeMenu();
    expect(
      within(await cardMenu('Record count'))
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      defaultMessages['label.analysis.rename'],
      formatMessage(defaultMessages, 'label.analysis.copy-with-condition', {
        name: 'Record count',
      }),
    ]);
  });
});

describe('a display name', () => {
  /**
   * D20 显示名. The name is the view's own word for the column — the field's
   * label and the summary are what a query can compose, and neither of them
   * is 「门店」. An emptied box takes the name back rather than storing a
   * blank one, which admission would refuse (`analysis.label.blank`).
   */
  it('names a dimension, and an emptied box takes the name back', async () => {
    const { engine } = await open();

    commit(await renameBox('Warehouse'), '门店');

    await waitFor(() =>
      expect(draft(engine).groups[0]).toMatchObject({ label: '门店' }),
    );
    expect(cardNames()).toContain('门店');
    // The card is known by its new name now, menu and all.
    commit(await renameBox('门店'), '   ');
    await waitFor(() =>
      expect('label' in draft(engine).groups[0]!).toBe(false),
    );
    expect(cardNames()).toContain('Warehouse');
  });

  /**
   * Escape is the way out of an edit, and the way out has to leave nothing
   * behind: the box hands focus back to the menu it was opened from, and a
   * focus move is a blur — which used to commit the very text Escape threw
   * away, and to bring it back the next time the box was opened.
   */
  it('drops the edit on Escape, and starts fresh the next time', async () => {
    const { engine } = await open();

    const box = await renameBox('Warehouse');
    fireEvent.change(box, { target: { value: '门店' } });
    fireEvent.keyDown(box, { key: 'Escape' });

    await waitFor(() => expect(cardNames()).toContain('Warehouse'));
    expect('label' in draft(engine).groups[0]!).toBe(false);
    // Focus goes back to the trigger rather than to the page.
    expect(document.activeElement).toBe(
      screen.getByRole('button', {
        name: label('label.analysis.card-menu', 'Warehouse'),
      }),
    );
    expect((await renameBox('Warehouse')) as HTMLInputElement).toHaveProperty(
      'value',
      '',
    );
  });

  /** A metric is named the same way, through the same menu. */
  it('names a metric the same way', async () => {
    const { engine } = await open();

    commit(await renameBox('Record count'), '单数');

    await waitFor(() =>
      expect(draft(engine).metrics[0]).toMatchObject({ label: '单数' }),
    );
    expect(cardNames()).toContain('单数');
  });

  /**
   * A name is part of the question rather than part of how it is looked at,
   * so it waits for Apply like every other edit in the tray (D17-6).
   */
  it('marks Apply, because a name runs rather than redraws', async () => {
    await open();
    expect(applyButton().hasAttribute('data-pending')).toBe(false);

    commit(await renameBox('Warehouse'), '门店');

    await waitFor(() =>
      expect(applyButton().hasAttribute('data-pending')).toBe(true),
    );
    expect(
      applyButton().querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();
  });

  /**
   * A named column is titled by its name alone (`columnTitle`): 「门店」, not
   * 「门店 的 合计」 — the analyst already said what the column is, and
   * appending the summary to it says it twice. Every place a column is
   * named follows: the header, the result's reading, and a chart's legend
   * and figure name, which read through the same titler.
   */
  it('titles the header and the reading once it has run', async () => {
    await open({
      groups: [
        {
          alias: 'warehouse',
          field: 'warehouse',
          type: 'TERMS',
          label: '门店',
        },
      ],
      metrics: [{ alias: 'orders', type: 'COUNT', label: '单数' }],
    });

    await waitFor(() =>
      expect(screen.getByRole('columnheader', { name: '门店' })).toBeDefined(),
    );
    expect(screen.getByRole('columnheader', { name: '单数' })).toBeDefined();
    expect(
      document.querySelector('[data-slot="analysis-reading"]')?.textContent,
    ).toBe('By 门店 · 单数');
  });

  /** The chart's legend and figure name read through the same titler. */
  it('names a chart’s series by the name too', async () => {
    await open({
      layout: 'chart',
      groups: [
        {
          alias: 'warehouse',
          field: 'warehouse',
          type: 'TERMS',
          label: '门店',
        },
      ],
      metrics: [{ alias: 'orders', type: 'COUNT', label: '单数' }],
    });

    const reading = await waitFor(() => {
      const found = document.querySelector('[data-slot="chart-reading"]');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(
      within(reading as HTMLElement)
        .getAllByRole('columnheader')
        .map(head => head.textContent),
    ).toEqual(['门店', '单数']);
  });
});

describe('the sentinel bucket', () => {
  /**
   * Wow allows `missingKey` on a single-valued text field only, so the item
   * exists exactly where the setting does — a checkbox that reports a
   * refusal on Apply would be a control that lies about what it can do.
   */
  it('is offered for a dimension by value on a text field and nowhere else', async () => {
    await open();
    const item = defaultMessages['label.analysis.missing-bucket'];

    expect(
      within(await cardMenu('Warehouse')).getByRole('menuitemcheckbox', {
        name: item,
      }),
    ).toBeDefined();
    closeMenu();

    // A number cut by value is a `TERMS` too, and Wow refuses the bucket on
    // it all the same.
    await add('Add dimension', 'Amount');
    await screen.findByRole('button', { name: 'Remove dimension Amount' });
    expect(
      within(await cardMenu('Amount')).queryByRole('menuitemcheckbox', {
        name: item,
      }),
    ).toBeNull();
  });

  /**
   * On is the key Wow buckets the missing records under; off is the key
   * *gone*, not set to `undefined` — a config is plain JSON, and a member
   * carrying `undefined` is a config no fresh one would ever be.
   */
  it('toggles on to the sentinel key and off to no key at all', async () => {
    const { engine } = await open();
    const group = () => json(draft(engine).groups[0]!);
    const item = async () =>
      within(await cardMenu('Warehouse')).getByRole('menuitemcheckbox', {
        name: defaultMessages['label.analysis.missing-bucket'],
      });

    // The saved config was written without it, so it starts unchecked.
    expect((await item()).getAttribute('aria-checked')).toBe('false');
    fireEvent.click(await item());
    await waitFor(() => expect(group().missingKey).toBe(DEFAULT_MISSING_KEY));

    expect((await item()).getAttribute('aria-checked')).toBe('true');
    fireEvent.click(await item());
    await waitFor(() => expect('missingKey' in group()).toBe(false));
  });

  /**
   * A dimension never drops records without saying so, so a new one by
   * value starts with the bucket wherever the field can carry it
   * (`groupOfType`). A number field cannot, so it starts without.
   */
  it('is where a new dimension by value starts, when the field can carry it', async () => {
    const { engine } = await open({ groups: [] });

    await add('Add dimension', 'Warehouse');
    await waitFor(() =>
      expect(draft(engine).groups[0]).toMatchObject({
        type: 'TERMS',
        missingKey: DEFAULT_MISSING_KEY,
      }),
    );

    await add('Add dimension', 'Amount');
    await waitFor(() => expect(draft(engine).groups).toHaveLength(2));
    expect('missingKey' in (draft(engine).groups[1] as object)).toBe(false);
  });
});

describe('filling in empty periods', () => {
  /**
   * A month with no orders is a gap in the line, and a line that skips it
   * lies about the shape. Wow fills it on request — but only for one
   * dimension: filled out across a second it multiplies, and Wow refuses
   * the query. So beside a second dimension the item is disabled and says
   * why, rather than disappearing (a control that vanishes teaches nothing).
   */
  it('fills a time dimension, and is refused beside a second one', async () => {
    const { engine } = await open({
      groups: [
        {
          alias: 'created',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'MONTH',
        },
      ],
    });
    const group = () => json(draft(engine).groups[0]!);
    const item = async (name: string) =>
      within(await cardMenu(name)).getByRole('menuitemcheckbox', {
        name: defaultMessages['label.analysis.dense'],
      });

    fireEvent.click(await item('Created'));
    await waitFor(() => expect(group().dense).toBe(true));
    expect((await item('Created')).getAttribute('aria-checked')).toBe('true');

    fireEvent.click(await item('Created'));
    await waitFor(() => expect('dense' in group()).toBe(false));
    closeMenu();

    // A second dimension takes the choice away, in words.
    await add('Add dimension', 'Warehouse');
    await screen.findByRole('button', { name: 'Remove dimension Warehouse' });
    const alone = within(await cardMenu('Created')).getByRole(
      'menuitemcheckbox',
      { name: defaultMessages['label.analysis.dense-alone'] },
    );
    expect(alone.getAttribute('aria-disabled')).toBe('true');
  });
});

describe('the granularity a new time dimension starts at', () => {
  /**
   * K4. A year of orders cut by the hour is eight thousand buckets nobody
   * asked for and a week cut by the month is one, so a fresh time dimension
   * reads the span the analysis is already over and starts at the coarsest
   * offered unit that still cuts it into a readable number of buckets. It
   * is a starting point and nothing more: the granularity select is right
   * there, and a hand-picked unit always wins.
   */
  it('reads the applied range, and falls back to the field’s first unit', async () => {
    const between: FilterTree = {
      op: 'and',
      children: [
        {
          field: 'createdAt',
          operator: 'BETWEEN',
          value: { type: 'absolute', from: '2026-01-01', to: '2026-02-28' },
        },
      ],
    };
    const { engine } = await open({ filter: between });

    // Two months: weeks (about nine of them), not months (two).
    await add('Add dimension', 'Created');
    await waitFor(() =>
      expect(draft(engine).groups[1]).toMatchObject({
        type: 'DATE_HISTOGRAM',
        unit: 'WEEK',
      }),
    );

    cleanup();
    const plain = await open();
    await add('Add dimension', 'Created');
    await waitFor(() =>
      expect(draft(plain.engine).groups[1]).toMatchObject({ unit: 'MONTH' }),
    );
  });
});

describe('the fields a dimension may be added on', () => {
  /**
   * Cutting by one field twice is a question nobody asks — the second cut
   * makes exactly the groups the first one did — so a field already grouped
   * on leaves the menu, as it leaves the follow-up menu's split for the
   * same reason.
   */
  it('stops offering a field already grouped on', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: 'Add dimension' }));
    const menu = await screen.findByRole('menu');

    expect(
      within(menu).queryByRole('menuitem', { name: 'Warehouse' }),
    ).toBeNull();
    expect(
      within(menu).getByRole('menuitem', { name: 'Created' }),
    ).toBeDefined();
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Created' }));

    // And the one just added goes the same way.
    await waitFor(() =>
      expect(
        tray()!.querySelectorAll('[data-slot="dimension-card"]'),
      ).toHaveLength(2),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Add dimension' }));
    expect(
      within(await screen.findByRole('menu')).queryByRole('menuitem', {
        name: 'Created',
      }),
    ).toBeNull();
  });
});
