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
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AUTO_APPLY_DELAY_MS,
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type ViewInstance,
  type ViewPreferences,
  type ViewSource,
} from '../src/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import {
  analysisConfig,
  mine,
  ordersDefinition,
  testEnvironment,
  testSource,
  type TestEnvironment,
} from './fixtures.js';
import { addConditions, editorToggle, openTray } from './fixtures/workbench.js';

afterEach(cleanup);

/**
 * 「改了就跑」 (D20; todo 批 7) on the screen it belongs to: the tray's
 * switch, the preference behind it, and what an edit does while it is on.
 *
 * What is due and what holds it is pinned on the runtime itself in
 * `test/autoApply.test.ts`; this suite is about the three things only a
 * rendered workbench can answer — that the switch is where the analyst
 * reads it and says what it does, that an edit really reaches a query
 * without anyone pressing Apply, and that the rows waiting for that query
 * are faded rather than taken away.
 */

/**
 * Two groupable fields, so a dimension can be added to a question that
 * already has one, and one measurable field to summarise.
 */
function twoDimensionDefinition(): DataViewDefinition {
  return ordersDefinition({
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'status',
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

const analysisView: ViewInstance = {
  id: 'orders-2',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

function storedPreferences(autoRun: boolean): ViewPreferences {
  return { order: [], defaultInstanceId: null, autoRun, revision: 'p1' };
}

interface Opened {
  engine: ViewEngine;
  store: MemoryViewStore;
  clock: TestEnvironment;
  source: ViewSource;
  /** How many aggregation queries this view has sent so far. */
  runs(): number;
}

/** The saved analysis on screen with its tray open, on the test's clock. */
async function openAnalysis(preferences?: ViewPreferences): Promise<Opened> {
  const store = new MemoryViewStore({
    instances: [analysisView],
    ...(preferences ? { preferences: { orders: preferences } } : {}),
  });
  // One source for the whole view, because the test counts its calls: a
  // factory that built a fresh spy per query would count every run as the
  // first.
  const source = testSource();
  const clock = testEnvironment();
  const engine = new ViewEngine({
    definitions: [twoDimensionDefinition()],
    store,
    resolveSource: () => source,
    environment: clock.environment,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId={analysisView.id}
      kinds={['analysis']}
    />,
  );
  await openTray();
  const runs = () => vi.mocked(source.aggregate).mock.calls.length;
  // The saved question runs once on open; every count below is a delta on it.
  await waitFor(() => expect(runs()).toBe(1));
  return { engine, store, clock, source, runs };
}

const autoRunSwitch = () =>
  within(
    document.querySelector<HTMLElement>('[data-slot="auto-run"]')!,
  ).getByRole('checkbox');

const result = () =>
  document.querySelector<HTMLElement>('[data-slot="analysis-result"]');

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

/** Adds a dimension the way the analyst adds one: the tray's own menu. */
async function addDimension(field: string): Promise<void> {
  fireEvent.click(
    screen.getByRole('button', {
      name: defaultMessages['label.analysis.add-group'],
    }),
  );
  fireEvent.click(await screen.findByRole('menuitem', { name: field }));
}

/** Lets the auto-apply moment come and go. */
function elapse(clock: TestEnvironment, ms = AUTO_APPLY_DELAY_MS): void {
  act(() => clock.advance(ms));
}

describe('改了就跑: the tray’s switch', () => {
  /**
   * It is on when nothing was ever said, because reading the answer is what
   * the analyst came for, and it says what it does rather than naming the
   * mechanism — the label is a sentence about the question, not about a
   * timer.
   */
  it('is checked by default, and named', async () => {
    await openAnalysis();

    const box = autoRunSwitch();
    expect(box.getAttribute('aria-checked')).toBe('true');
    expect(
      screen.getByRole('checkbox', {
        name: defaultMessages['label.analysis.auto-run'],
      }),
    ).toBe(box);
  });

  /**
   * Switching it off is a write of this user's preference for this
   * definition — beside the order and the default, nothing anyone else
   * sees — and from then on Apply is the one way to run.
   */
  it('writes the preference off, and the edits then wait for Apply', async () => {
    const { store, clock, runs } = await openAnalysis();

    fireEvent.click(autoRunSwitch());

    await waitFor(async () =>
      expect((await store.getPreferences('orders')).autoRun).toBe(false),
    );
    await waitFor(() =>
      expect(autoRunSwitch().getAttribute('aria-checked')).toBe('false'),
    );

    await addDimension('Status');
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);
    expect(runs()).toBe(1);

    fireEvent.click(applyButton());
    await waitFor(() => expect(runs()).toBe(2));
  });

  /**
   * A stored `false` reaches both the switch and the runtime: a box that
   * reads "off" over a view that still runs on its own would be a lie told
   * once per edit.
   */
  it('opens unchecked when the preference says so', async () => {
    const { engine, clock, runs } = await openAnalysis(
      storedPreferences(false),
    );

    await waitFor(() =>
      expect(autoRunSwitch().getAttribute('aria-checked')).toBe('false'),
    );
    expect(engine.openRuntimes()[0]!.getSnapshot().autoApply).toBe(false);

    await addDimension('Status');
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);

    expect(runs()).toBe(1);
    expect(applyButton().hasAttribute('data-pending')).toBe(true);
  });

  /**
   * A record view has no tray and no switch: its edits — a sort, a page
   * size — are applied where they are made, and there is no question
   * waiting on a moment of quiet.
   */
  it('is not on a record view', async () => {
    const store = new MemoryViewStore({ instances: [mine] });
    const engine = new ViewEngine({
      definitions: [twoDimensionDefinition()],
      store,
      resolveSource: () => testSource(),
      environment: testEnvironment().environment,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={mine.id}
        kinds={['record']}
      />,
    );

    fireEvent.click(await waitFor(editorToggle));
    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="filter-panel"]'),
      ).not.toBeNull(),
    );
    expect(document.querySelector('[data-slot="auto-run"]')).toBeNull();
  });
});

describe('改了就跑: an analysis runs as it is edited', () => {
  /**
   * The edit reaches a query with nobody pressing anything, and it reaches
   * exactly one: the moment of quiet is what merges a burst of edits, and a
   * query per keystroke is the reason a tray ever needed an Apply.
   */
  it('runs the edited question once, a moment after the last edit', async () => {
    const { clock, runs } = await openAnalysis();

    await addDimension('Status');
    elapse(clock, AUTO_APPLY_DELAY_MS - 1);
    expect(runs()).toBe(1);

    elapse(clock, 1);

    await waitFor(() => expect(runs()).toBe(2));
    await waitFor(() =>
      expect(applyButton().hasAttribute('data-pending')).toBe(false),
    );
  });

  /**
   * The rows on screen answer the question as it was a moment ago, so they
   * are faded rather than cleared: the next answer is a few hundred
   * milliseconds away, and a blank in between reads as a failure.
   */
  it('fades the rows while the next answer is on its way', async () => {
    const { clock, runs } = await openAnalysis();
    expect(result()!.hasAttribute('data-stale')).toBe(false);

    await addDimension('Status');

    await waitFor(() =>
      expect(result()!.hasAttribute('data-stale')).toBe(true),
    );
    // Faded, not emptied: the last answer is still readable while it waits.
    expect(screen.getAllByRole('table').length).toBeGreaterThan(0);

    elapse(clock);

    await waitFor(() => expect(runs()).toBe(2));
    await waitFor(() =>
      expect(result()!.hasAttribute('data-stale')).toBe(false),
    );
  });

  /**
   * A changed range waits for Apply (D20), and while it waits nothing else
   * runs on its own either: Apply runs the whole draft, and a half-applied
   * draft would have the rows answering two questions at once. The dot stays
   * up, because there is still something to press.
   */
  it('leaves a changed range to Apply', async () => {
    const { clock, runs } = await openAnalysis();

    await addConditions(['Warehouse']);
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);

    expect(runs()).toBe(1);
    expect(applyButton().hasAttribute('data-pending')).toBe(true);
    expect(
      applyButton().querySelector('[data-slot="pending-dot"]'),
    ).not.toBeNull();
    expect(result()!.hasAttribute('data-stale')).toBe(false);

    fireEvent.click(applyButton());

    await waitFor(() => expect(runs()).toBe(2));
  });
});
