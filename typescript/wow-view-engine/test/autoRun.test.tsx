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
} from '@ahoo-wang/wow-client';
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
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type ViewInstance,
  type ViewPreferences,
  type ViewSource,
} from '../src/index.js';
import { AUTO_APPLY_DELAY_MS } from '../src/runtime/autoApply.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import {
  analysisConfig,
  deferred,
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

/**
 * The saved analysis on screen with its tray open, on the test's clock.
 * `answer`, when given, is what the store's preferences read resolves with,
 * so a test can hold that answer back while the view is already open.
 */
async function openAnalysis(
  preferences?: ViewPreferences,
  answer?: Promise<ViewPreferences>,
): Promise<Opened> {
  const store = new MemoryViewStore({
    instances: [analysisView],
    ...(preferences ? { preferences: { orders: preferences } } : {}),
  });
  if (answer) store.getPreferences = () => answer;
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
   * The label is the analyst's word for the setting (「自动运行」, 2026-09-23
   * audit, rather than the colloquial 「改了就跑」), and the line under it
   * says what the word alone overpromises: the range is not part of it. On
   * the screen and to a reader both — it is the box's description.
   */
  it('says under its name what it runs and what it leaves to Apply', async () => {
    await openAnalysis();

    const hint = defaultMessages['label.analysis.auto-run-hint'];
    const described = document.getElementById(
      autoRunSwitch().getAttribute('aria-describedby')!,
    );
    expect(described?.textContent).toBe(hint);
    expect(
      document.querySelector<HTMLElement>('[data-slot="auto-run-hint"]'),
    ).toBe(described);
  });

  /**
   * Apply is primary only while something waits for it. With the switch on
   * and nothing edited it rests; switched off, Apply is the one way to run
   * again, and it is filled whatever the draft says.
   */
  it('rests Apply while it is on, and fills it again once it is off', async () => {
    await openAnalysis();
    expect(applyButton().getAttribute('data-emphasis')).toBe('quiet');

    fireEvent.click(autoRunSwitch());

    await waitFor(() =>
      expect(applyButton().getAttribute('data-emphasis')).toBe('primary'),
    );
    expect(applyButton().hasAttribute('data-pending')).toBe(false);
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
   * The view opens by its id without waiting for the preferences, so an
   * edit can come before they answer. The default `true` stands in for a
   * preference that is not there, never for one not yet read: a reader who
   * turned it off must not have that edit run on its own (a race a loaded CI
   * runner hit in `analysisTray.test.tsx`).
   */
  it('runs nothing on its own before the preference has answered', async () => {
    const answer = deferred<ViewPreferences>();
    const { engine, clock, runs } = await openAnalysis(
      undefined,
      answer.promise,
    );

    await addDimension('Status');
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);
    expect(runs()).toBe(1);
    expect(engine.openRuntimes()[0]!.getSnapshot().autoApply).toBe(false);

    await act(async () => answer.resolve(storedPreferences(false)));
    await waitFor(() =>
      expect(autoRunSwitch().getAttribute('aria-checked')).toBe('false'),
    );
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);

    expect(runs()).toBe(1);
    expect(applyButton().hasAttribute('data-pending')).toBe(true);
  });

  /** And an edit held that way runs once the answer turns it on. */
  it('runs an edit made before the preference answered once it says on', async () => {
    const answer = deferred<ViewPreferences>();
    const { engine, clock, runs } = await openAnalysis(
      undefined,
      answer.promise,
    );

    await addDimension('Status');
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);
    expect(runs()).toBe(1);

    await act(async () => answer.resolve(storedPreferences(true)));
    await waitFor(() =>
      expect(engine.openRuntimes()[0]!.getSnapshot().autoApply).toBe(true),
    );
    elapse(clock);

    await waitFor(() => expect(runs()).toBe(2));
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
    // And nothing asks for a press meanwhile: the run is already coming, so
    // Apply stays at rest with no dot — a dot there would blink up and away
    // on every edit.
    expect(applyButton().getAttribute('data-emphasis')).toBe('quiet');
    expect(applyButton().hasAttribute('data-pending')).toBe(false);

    elapse(clock);

    await waitFor(() => expect(runs()).toBe(2));
    await waitFor(() =>
      expect(result()!.hasAttribute('data-stale')).toBe(false),
    );
  });

  /**
   * A slow answer is the case the debounce alone missed (2026-09-23 audit):
   * the fade lasted the 300ms before the question was sent and went the
   * moment it was, so the old rows stood at full strength for however long
   * the source took, looking like the new ones. They stay faded for the
   * whole flight, and come back when the answer lands.
   */
  it('keeps the rows faded for as long as the new answer takes', async () => {
    const { clock, runs, source } = await openAnalysis();
    let answer: (rows: Record<string, unknown>[]) => void = () => undefined;
    vi.mocked(source.aggregate).mockImplementation(
      () =>
        new Promise(resolve => {
          answer = resolve;
        }),
    );

    await addDimension('Status');
    elapse(clock);
    await waitFor(() => expect(runs()).toBe(2));

    // Sent and not back: still faded, however long it takes.
    elapse(clock, 5_000);
    expect(result()!.hasAttribute('data-stale')).toBe(true);

    await act(async () =>
      answer([{ warehouse: 'CN', status: 'PAID', orders: 1 }]),
    );
    await waitFor(() =>
      expect(result()!.hasAttribute('data-stale')).toBe(false),
    );
  });

  /**
   * A refresh asks the same question again: its rows are the answer to what
   * is being asked, so they are not faded while it runs.
   */
  it('does not fade the rows through a refresh of the same question', async () => {
    const { runs, source } = await openAnalysis();
    vi.mocked(source.aggregate).mockImplementation(
      () => new Promise(() => undefined),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(runs()).toBe(2));

    expect(result()!.hasAttribute('data-stale')).toBe(false);
  });

  /**
   * A range condition added and not yet filled in holds every run: the
   * range waits for Apply, and Apply runs the whole draft, so nothing runs
   * on its own however the question changes — and it used to say nothing
   * about why (2026-09-23 audit). The switch's sentence says it instead,
   * for as long as it is true.
   */
  it('says why nothing runs while the range holds an unfinished condition', async () => {
    const { clock, runs } = await openAnalysis();
    const hint = () =>
      document.querySelector<HTMLElement>('[data-slot="auto-run-hint"]')!;
    expect(hint().textContent).toBe(
      defaultMessages['label.analysis.auto-run-hint'],
    );

    await addConditions(['Warehouse']);
    await addDimension('Status');
    elapse(clock, AUTO_APPLY_DELAY_MS * 2);

    expect(runs()).toBe(1);
    expect(hint().textContent).toBe(
      defaultMessages['label.analysis.auto-run-held'],
    );
    expect(hint().hasAttribute('data-held')).toBe(true);
    // Still the switch's description, so a reader on the switch hears it.
    expect(autoRunSwitch().getAttribute('aria-describedby')).toBe(hint().id);
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
    // Something waits for it, so it is the primary again.
    expect(applyButton().getAttribute('data-emphasis')).toBe('primary');
    expect(result()!.hasAttribute('data-stale')).toBe(false);

    fireEvent.click(applyButton());

    await waitFor(() => expect(runs()).toBe(2));
  });
});
