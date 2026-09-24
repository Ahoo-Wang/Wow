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
 * What a result says about itself: a summary row that had to narrow its
 * scope, and a grouping with more groups below the last row.
 *
 * Both are numbers that mean something other than what a reader takes them
 * to mean, so both are `warning` Issues carried on the projected result —
 * where they outlive the next keystroke — rather than on `state.issues`,
 * which is admission of the draft and is recomputed from it.
 *
 * The projection rule behind the second one — one row more asked for, that
 * row dropped again — is `test/analysisProject.test.ts`「the probe row read
 * back」. This file is what the runtime and the screen make of its answer.
 */

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  resultIssues,
  type AnalysisViewConfig,
  type DataViewConfig,
  type ProjectedView,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  DataWorkbench,
  EmbeddedView,
  defaultMessages,
  zhCN,
} from '../src/ui/index.js';
import {
  analysisConfig,
  ordersDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const SUMMED = recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] });

/** The sentence a catalogue gives a finding, with its values filled in. */
function said(key: keyof typeof defaultMessages, limit?: number): string {
  const sentence = defaultMessages[key];
  return limit === undefined
    ? sentence
    : sentence.replace('{limit}', String(limit));
}

/** One aggregation row per warehouse, as many as the caller asks for. */
function warehouses(count: number): RecordData[] {
  return Array.from({ length: count }, (_unused, index) => ({
    warehouse: `W-${index}`,
    orders: count - index,
  }));
}

/** A source whose aggregation never answers; the page query still does. */
function noAggregate(): ViewSource {
  return testSource({
    aggregate: vi.fn(() => Promise.reject(new Error('offline'))),
  });
}

/**
 * A source holding `count` warehouses that answers no more rows than the
 * query asked for. The limit has to be honoured for the probe row to mean
 * anything: a source that answers `count` whatever it was asked either
 * invents a cut that did not happen or hides one that did.
 */
function grouped(count: number): ViewSource {
  return testSource({
    aggregate: vi.fn((query: { limit?: number }) =>
      Promise.resolve(warehouses(count).slice(0, query.limit ?? count)),
    ),
  });
}

function engineOver(config: DataViewConfig, source: ViewSource): ViewEngine {
  const instance: ViewInstance = {
    id: 'orders-1',
    definitionId: 'orders',
    title: 'Mine',
    scope: 'personal',
    revision: '1',
    config,
  };
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [instance] }),
    resolveSource: () => source,
  });
}

/** What the runtime records about an execution, beside the view itself. */
describe('the findings a result carries', () => {
  async function ran(
    config: DataViewConfig,
    source: ViewSource,
  ): Promise<ProjectedView> {
    const runtime = await engineOver(config, source).open('orders-1');
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );
    const data = runtime.getSnapshot().result?.data;
    if (!data) throw new Error('expected a result');
    return data;
  }

  it('reports the summary scope it had to give up', async () => {
    const data = await ran(SUMMED, noAggregate());

    expect(data.kind === 'record' && data.summaries?.scope).toBe('page');
    expect(data.issues).toEqual([
      {
        code: 'runtime.summary.page-only',
        severity: 'warning',
        path: ['summaries'],
      },
    ]);
  });

  it('reports nothing when the totals query answered', async () => {
    const data = await ran(SUMMED, testSource());

    expect(data.kind === 'record' && data.summaries?.scope).toBe('total');
    expect(data.issues).toEqual([]);
  });

  it('reports nothing when the config asked for no summaries', async () => {
    // The aggregation would fail if it ran; nothing asked it to.
    const data = await ran(recordConfig(), noAggregate());

    expect(data.kind === 'record' && data.summaries).toBeNull();
    expect(data.issues).toEqual([]);
  });

  /**
   * Every row shown is its own true number, and the view asked for its top
   * two: the groups below are worth saying, as a note — nothing is wrong.
   */
  it('reports an analysis with more groups than it showed', async () => {
    const data = await ran(analysisConfig({ limit: 2 }), grouped(4));

    expect(data.issues).toEqual([
      {
        code: 'analysis.result.more-groups',
        severity: 'note',
        path: ['limit'],
        // The limit the reader set, not the one the query carried: the probe
        // row is the engine's question and never the reader's.
        params: { limit: 2 },
      },
    ]);
    expect(data.kind === 'analysis' && data.view.rows).toHaveLength(2);
  });

  /**
   * A ranking — the groups ordered by a metric and cut at N — left the rest
   * out on purpose, so the note would only tell the author what they asked
   * for (2026-09-23 audit). The probe cannot know intent; a sort by a metric
   * first is where the config says it. Sorted by a dimension, or not at
   * all, the first N are whichever the order puts first, and it is said.
   */
  it('says nothing about a ranking, and still does about a plain cut', async () => {
    const ranking = await ran(
      analysisConfig({
        limit: 2,
        sort: [{ alias: 'orders', direction: 'DESC' }],
      }),
      grouped(4),
    );
    expect(ranking.issues).toEqual([]);

    const byName = await ran(
      analysisConfig({
        limit: 2,
        sort: [{ alias: 'warehouse', direction: 'ASC' }],
      }),
      grouped(4),
    );
    expect(byName.issues.map(found => found.code)).toEqual([
      'analysis.result.more-groups',
    ]);
  });

  /** A pie's shares are over what is shown, ranking or not. */
  it('still warns about a ranked pie', async () => {
    const data = await ran(
      analysisConfig({
        limit: 2,
        sort: [{ alias: 'orders', direction: 'DESC' }],
        layout: 'chart',
        chart: { type: 'pie', pie: { category: 'warehouse', value: 'orders' } },
      }),
      grouped(4),
    );
    expect(data.issues.map(found => found.severity)).toEqual(['warning']);
  });

  /**
   * A pie's slices are shares of the groups shown, so a cut-short pie reads
   * as the whole when it is not: that is a warning.
   */
  it('warns when the groups left out skew a pie’s shares', async () => {
    const data = await ran(
      analysisConfig({
        limit: 2,
        layout: 'chart',
        chart: { type: 'pie', pie: { category: 'warehouse', value: 'orders' } },
      }),
      grouped(4),
    );
    expect(data.issues.map(found => found.severity)).toEqual(['warning']);
  });

  /**
   * Two warehouses under a limit of two used to raise the warning on the
   * strength of the count alone. The probe row settles it: the query asked
   * for three and got two, so there is no third group and nothing to say.
   */
  it('reports nothing about a grouping that exactly fits', async () => {
    expect(
      (await ran(analysisConfig({ limit: 2 }), grouped(2))).issues,
    ).toEqual([]);
  });

  it('reports nothing about an analysis that came back short', async () => {
    expect(
      (await ran(analysisConfig({ limit: 100 }), grouped(2))).issues,
    ).toEqual([]);
  });

  /**
   * The one case no probe can reach: the configured limit already sits on the
   * capability's ceiling, so there is no row left to ask for and "came back
   * exactly full" is all there is — said as a maybe, as it always was.
   */
  it('still says "may" where the limit sits on the ceiling', async () => {
    const engine = new ViewEngine({
      definitions: [
        ordersDefinition({
          analysis: {
            ...ordersDefinition().analysis!,
            limits: { maxLimit: 2 },
          },
        }),
      ],
      store: new MemoryViewStore({
        instances: [
          {
            id: 'orders-1',
            definitionId: 'orders',
            title: 'Mine',
            scope: 'personal',
            revision: '1',
            config: analysisConfig({ limit: 2 }),
          },
        ],
      }),
      resolveSource: () => grouped(4),
    });
    const runtime = await engine.open('orders-1');
    await waitFor(() =>
      expect(runtime.getSnapshot().query.status).toBe('success'),
    );

    expect(runtime.getSnapshot().result?.data?.issues).toEqual([
      {
        code: 'analysis.result.at-limit',
        severity: 'note',
        path: ['limit'],
        params: { limit: 2 },
      },
    ]);
  });

  /**
   * An analysis totals query failing costs the totals row and nothing else:
   * the grouped rows still answer their own question in full, so no number
   * on screen means anything other than what it says.
   */
  it('keeps quiet when only the analysis totals query failed', async () => {
    let call = 0;
    const source = testSource({
      aggregate: vi.fn(() =>
        (call += 1) === 1
          ? Promise.resolve(warehouses(2))
          : Promise.reject(new Error('offline')),
      ),
    });
    const data = await ran(
      analysisConfig({ limit: 100, table: { columns: [], totals: true } }),
      source,
    );

    expect(data.kind === 'analysis' && data.view.totals).toBeUndefined();
    expect(data.issues).toEqual([]);
  });

  it('reads as nothing while no result has come back', () => {
    expect(resultIssues(null)).toEqual([]);
    expect(resultIssues(undefined)).toEqual([]);
  });
});

/** The label and the sentence a reader actually gets. */
describe('what the screen says about a downgraded total', () => {
  function footer(): HTMLElement {
    const found = document.querySelector<HTMLElement>('tfoot');
    if (!found) throw new Error('no summary footer');
    return found;
  }

  /** The scope each summary row carries, top to bottom. */
  function scopes(): (string | undefined)[] {
    return [...footer().querySelectorAll<HTMLTableRowElement>('tr')].map(
      row => row.dataset.scope,
    );
  }

  it('labels the row "this page" and says why in the strip', async () => {
    render(
      <DataWorkbench
        engine={engineOver(SUMMED, noAggregate())}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    expect(
      await screen.findByText(said('runtime.summary.page-only')),
    ).toBeDefined();
    // Only the page is left: the row that would have covered everything the
    // conditions match has no number to show, and none is invented for it.
    expect(scopes()).toEqual(['page']);
    expect(footer().textContent).toContain(said('label.summary.scope.page'));
    expect(footer().textContent).not.toContain(
      said('label.summary.scope.total'),
    );
  });

  it('labels it "total" and says nothing when the query answered', async () => {
    render(
      <DataWorkbench
        engine={engineOver(SUMMED, testSource())}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    // The two rows the source holds are one page, so the page is all of it
    // and the one row is the total (D26 Q40) — labelled so, with nothing to
    // correct in the strip.
    await waitFor(() => expect(scopes()).toEqual(['total']));
    expect(footer().textContent).toContain(said('label.summary.scope.total'));
    expect(screen.queryByText(said('runtime.summary.page-only'))).toBeNull();
  });

  /**
   * An embed has no editor and no toolbar, so its strip is the only thing
   * on the page that can correct the word on the row.
   */
  it('says it in an embedded view too, in the host wording', async () => {
    render(
      <EmbeddedView
        engine={engineOver(SUMMED, noAggregate())}
        instanceId="orders-1"
        messages={zhCN}
      />,
    );

    expect(
      await screen.findByText(zhCN['runtime.summary.page-only']),
    ).toBeDefined();
  });
});

/** A truncated grouping is worst as a pie, so both layouts have to say so. */
describe('what the screen says about an analysis cut short', () => {
  /** The sentence the strip says, with the reader's own limit in it. */
  const MORE = said('analysis.result.more-groups', 2);

  /** The same result under the two layouts one analysis can be drawn in. */
  function cutShort(layout: 'table' | 'chart'): AnalysisViewConfig {
    return analysisConfig({
      limit: 2,
      layout,
      chart: {
        type: 'pie',
        pie: { category: 'warehouse', value: 'orders' },
      },
    });
  }

  function show(config: AnalysisViewConfig, source: ViewSource) {
    render(
      <DataWorkbench
        engine={engineOver(config, source)}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
  }

  it('says it over the table', async () => {
    show(cutShort('table'), grouped(4));

    expect(await screen.findByText(MORE)).toBeDefined();
    expect(screen.getByRole('table')).toBeDefined();
  });

  /**
   * The sentence is about these rows, so it is drawn just above them, in
   * the result — not in the status line over the whole view, a screen away
   * from the table it described (2026-09-23 audit) — and said once.
   */
  it('says it next to the rows, and not in the status line', async () => {
    show(cutShort('table'), grouped(4));

    const sentence = await screen.findByText(MORE);
    const beside = document.querySelector<HTMLElement>(
      '[data-slot="analysis-cut-short"]',
    )!;
    expect(beside.contains(sentence)).toBe(true);
    expect(
      document.querySelector('[data-slot="analysis-result"]')!.contains(beside),
    ).toBe(true);
    expect(
      document.querySelector('[data-slot="status-line"]')!.textContent,
    ).not.toContain(MORE);
    expect(screen.getAllByText(MORE)).toHaveLength(1);
  });

  it('says it over the chart', async () => {
    show(cutShort('chart'), grouped(4));

    expect(await screen.findByText(MORE)).toBeDefined();
    // The chart layout draws no analysis table; the table beside it is the
    // `sr-only` reading of the very numbers the pie is cut from.
    expect(document.querySelector('[data-slot="analysis-table"]')).toBeNull();
  });

  it('keeps quiet when the grouping came back short of the limit', async () => {
    show(analysisConfig({ limit: 100 }), grouped(2));

    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(
      screen.queryByText(said('analysis.result.more-groups', 100)),
    ).toBeNull();
  });

  /**
   * The old heuristic warned here, and it was wrong: four warehouses under a
   * limit of four are four warehouses. The probe row is what tells the two
   * apart, and this is the case it was added for.
   */
  it('keeps quiet when the grouping exactly fills the limit', async () => {
    show(analysisConfig({ limit: 4 }), grouped(4));

    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(
      screen.queryByText(said('analysis.result.more-groups', 4)),
    ).toBeNull();
  });

  /**
   * The finding belongs to the numbers, not to the draft, so editing the
   * config does not take it off the screen while those numbers are still on
   * it. Put in `state.issues`, it would have gone on the first keystroke.
   */
  it('stays on screen while the draft is edited under it', async () => {
    const engine = engineOver(cutShort('table'), grouped(4));
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    await screen.findByText(MORE);

    act(() => engine.openRuntimes()[0].edit({ limit: 50 }));

    await waitFor(() => expect(screen.getByText(MORE)).toBeDefined());
  });
});

/** Both catalogues name every finding, and none was left in English. */
describe('the wording of the findings', () => {
  it.each([
    'runtime.summary.page-only',
    'analysis.result.more-groups',
    'analysis.result.at-limit',
  ] as const)('%s is worded in both languages', key => {
    expect(defaultMessages[key]).toBeTruthy();
    expect(zhCN[key]).toBeTruthy();
    expect(zhCN[key]).not.toBe(defaultMessages[key]);
  });
});
