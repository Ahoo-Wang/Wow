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
 * scope, and a grouping that may have been cut short.
 *
 * Both are numbers that mean something other than what a reader takes them
 * to mean, so both are `warning` Issues carried on the projected result —
 * where they outlive the next keystroke — rather than on `state.issues`,
 * which is admission of the draft and is recomputed from it.
 */

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  projectAnalysis,
  resultIssues,
  type AnalysisViewConfig,
  type DataViewConfig,
  type ProjectedView,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  AnalysisWorkbench,
  EmbeddedView,
  RecordWorkbench,
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

/** A source that answers every aggregation with `count` grouped rows. */
function grouped(count: number): ViewSource {
  return testSource({
    aggregate: vi.fn(() => Promise.resolve(warehouses(count))),
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

/**
 * The rule lives in the projection: the row count against the config's own
 * limit is all that is known, so it is all that can be reported.
 */
describe('projectAnalysis row limit', () => {
  const definition = ordersDefinition();
  const project = (limit: number | undefined, rows: RecordData[]) =>
    projectAnalysis(
      definition,
      analysisConfig({ limit: limit as number }),
      rows,
    );

  it('marks a result that filled its limit exactly', () => {
    const view = project(3, warehouses(3));

    expect(view.atLimit).toBe(3);
    expect(view.rows).toHaveLength(3);
  });

  it('says nothing about a result that came back short of it', () => {
    expect(project(3, warehouses(2)).atLimit).toBeUndefined();
  });

  /** An empty grouping under a limit of one is short of it, not at it. */
  it('says nothing about an empty result', () => {
    expect(project(1, []).atLimit).toBeUndefined();
  });

  /** A limit of one that one row filled is as ambiguous as any other. */
  it('marks a limit of one the result filled', () => {
    expect(project(1, warehouses(1)).atLimit).toBe(1);
  });

  /**
   * An analysis with no groups asks one question and gets one row, so a limit
   * of one is met by every successful answer. Warning there would put "may
   * have been cut short" under every metric view that ever ran.
   */
  it('says nothing about an analysis that has no grouping to cut short', () => {
    const view = projectAnalysis(
      definition,
      analysisConfig({ groups: [], sort: [], limit: 1 }),
      [{ orders: 6 }],
    );

    expect(view.rows).toHaveLength(1);
    expect(view.atLimit).toBeUndefined();
  });

  /**
   * `validateAnalysis` refuses each of these, but the projection is exported
   * and a host may run it over a config nothing admitted. No usable limit
   * means nothing is known about what was left out — which is not the same
   * as knowing nothing was, so nothing is claimed either way.
   */
  it.each([
    ['no limit at all', undefined],
    ['a limit of zero', 0],
    ['a fractional limit', 2.5],
    ['an infinite limit', Number.POSITIVE_INFINITY],
  ] as const)('says nothing under %s', (_name, limit) => {
    expect(project(limit, warehouses(2)).atLimit).toBeUndefined();
  });

  /**
   * A source that answered past the limit never treated it as a ceiling, so
   * the count it answered with says nothing about what it left out.
   */
  it('says nothing when the source answered past the limit', () => {
    expect(project(2, warehouses(3)).atLimit).toBeUndefined();
  });
});

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

  it('reports an analysis that filled its limit', async () => {
    const data = await ran(analysisConfig({ limit: 2 }), grouped(2));

    expect(data.issues).toEqual([
      {
        code: 'analysis.result.at-limit',
        severity: 'warning',
        path: ['limit'],
        params: { limit: 2 },
      },
    ]);
  });

  it('reports nothing about an analysis that came back short', async () => {
    expect(
      (await ran(analysisConfig({ limit: 100 }), grouped(2))).issues,
    ).toEqual([]);
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
      <RecordWorkbench
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
      <RecordWorkbench
        engine={engineOver(SUMMED, testSource())}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    // Both scopes, so the reader can tell this page from all of it.
    await waitFor(() => expect(scopes()).toEqual(['page', 'total']));
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
      <AnalysisWorkbench
        engine={engineOver(config, source)}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
  }

  it('says it over the table', async () => {
    show(cutShort('table'), grouped(2));

    expect(
      await screen.findByText(said('analysis.result.at-limit', 2)),
    ).toBeDefined();
    expect(screen.getByRole('table')).toBeDefined();
  });

  it('says it over the chart', async () => {
    show(cutShort('chart'), grouped(2));

    expect(
      await screen.findByText(said('analysis.result.at-limit', 2)),
    ).toBeDefined();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('keeps quiet when the grouping came back short of the limit', async () => {
    show(analysisConfig({ limit: 100 }), grouped(2));

    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(
      screen.queryByText(said('analysis.result.at-limit', 100)),
    ).toBeNull();
  });

  /**
   * The finding belongs to the numbers, not to the draft, so editing the
   * config does not take it off the screen while those numbers are still on
   * it. Put in `state.issues`, it would have gone on the first keystroke.
   */
  it('stays on screen while the draft is edited under it', async () => {
    const engine = engineOver(cutShort('table'), grouped(2));
    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await screen.findByText(said('analysis.result.at-limit', 2));

    act(() => engine.openRuntimes()[0].edit({ limit: 50 }));

    await waitFor(() =>
      expect(
        screen.getByText(said('analysis.result.at-limit', 2)),
      ).toBeDefined(),
    );
  });
});

/** Both catalogues name both findings, and neither was left in English. */
describe('the wording of the two findings', () => {
  it.each(['runtime.summary.page-only', 'analysis.result.at-limit'] as const)(
    '%s is worded in both languages',
    key => {
      expect(defaultMessages[key]).toBeTruthy();
      expect(zhCN[key]).toBeTruthy();
      expect(zhCN[key]).not.toBe(defaultMessages[key]);
    },
  );
});
