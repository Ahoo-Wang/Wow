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
 * What a group is called where its column's header is not beside it (the
 * retail scenes, batch 3, scenarios.md 6.4 items 5 and 7).
 *
 * - The bucket of records with no value is keyed by a fixed sentinel,
 *   `(empty)`, which travels to Wow and back; the reader sees it in the
 *   surface's words, 「（空）」 — while a key the analyst wrote themselves
 *   (「未上报城市」) is their name for it and stays as written.
 * - A split by a yes/no field names each series by its field as well:
 *   「新客：是」, not a legend of 「是」「否」 that says nothing of what.
 */

import { AggregationGroupType } from '@ahoo-wang/wow-client';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  DEFAULT_MISSING_KEY,
  projectAnalysis,
  type AnalysisViewConfig,
  type ChartData,
  type DataViewDefinition,
  type RecordData,
} from '../src/index.js';
import { AnalysisChart, ViewSurface, zhCN } from '../src/ui/index.js';
import { analysisFile } from '../src/ui/analysis/exportOffer.js';
import { cartesianPlan } from '../src/ui/charts/cartesianPlan.js';
import {
  defaultMessages,
  formatIssue,
  formatMessage,
  type ViewMessages,
} from '../src/ui/messages.js';
import type { MessageFormatters } from '../src/ui/MessagesProvider.js';
import {
  analysisConfig,
  namedOrdersDefinition,
  ordersDefinition,
} from './fixtures.js';

afterEach(cleanup);

function formatters(catalogue: ViewMessages): MessageFormatters {
  return {
    label: (key, params, fallback) => {
      const found = formatMessage(catalogue, key, params);
      return found === key && fallback !== undefined ? fallback : found;
    },
    issue: found => formatIssue(catalogue, found),
    issues: found => found.map(each => formatIssue(catalogue, each)).join(' '),
  };
}

function definition(): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [...base.fields, { name: 'isNew', label: '新客', kind: 'boolean' }],
    analysis: {
      ...base.analysis!,
      fields: [
        ...base.analysis!.fields,
        {
          field: 'isNew',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
      ],
    },
  });
}

function chartOf(
  config: AnalysisViewConfig,
  rows: RecordData[],
  messages: ViewMessages = zhCN,
) {
  const view = projectAnalysis(definition(), config, rows);
  render(
    <ViewSurface messages={messages}>
      <AnalysisChart
        data={view.chart!}
        spec={config.chart}
        columns={view.columns}
      />
    </ViewSurface>,
  );
  return view;
}

const reading = () =>
  document.querySelector('[data-slot="chart-reading"]')!.textContent!;
const legend = () =>
  [...document.querySelectorAll('[data-slot="chart-legend-item"]')].map(
    item => item.textContent,
  );

describe('the bucket of records with no value', () => {
  const byWarehouse = (missingKey: string) =>
    analysisConfig({
      groups: [
        { alias: 'warehouse', field: 'warehouse', type: 'TERMS', missingKey },
      ],
      layout: 'chart',
    });
  const rows = (key: string) => [
    { warehouse: 'W-1', orders: 5 },
    { warehouse: key, orders: 3 },
  ];

  it('reads as 「（空）」 on the chart, in its reading and in the file', () => {
    const view = chartOf(
      byWarehouse(DEFAULT_MISSING_KEY),
      rows(DEFAULT_MISSING_KEY),
    );
    expect(reading()).toContain('（空）');
    expect(reading()).not.toContain(DEFAULT_MISSING_KEY);
    expect(analysisFile(view, formatters(zhCN), {}).rows[1]?.[0]).toBe(
      '（空）',
    );
    expect(
      analysisFile(view, formatters(defaultMessages), {}).rows[1]?.[0],
    ).toBe(defaultMessages['label.analysis.missing-group']);
  });

  it('keeps the name the analyst gave it', () => {
    const view = chartOf(byWarehouse('未上报城市'), rows('未上报城市'));
    expect(reading()).toContain('未上报城市');
    expect(analysisFile(view, formatters(zhCN), {}).rows[1]?.[0]).toBe(
      '未上报城市',
    );
  });

  it('is not a value that merely spells the sentinel on a dimension without one', () => {
    // No bucket was asked for, so `(empty)` is some record's own value.
    const view = chartOf(
      analysisConfig({ layout: 'chart' }),
      rows(DEFAULT_MISSING_KEY),
    );
    expect(reading()).toContain(DEFAULT_MISSING_KEY);
    expect(analysisFile(view, formatters(zhCN), {}).rows[1]?.[0]).toBe(
      DEFAULT_MISSING_KEY,
    );
  });
});

describe('a split by a yes/no field', () => {
  const split = analysisConfig({
    groups: [
      { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
      { alias: 'isNew', field: 'isNew', type: 'TERMS' },
    ],
    layout: 'chart',
    chart: {
      type: 'bar',
      cartesian: {
        x: 'warehouse',
        splitBy: 'isNew',
        series: [{ metric: 'orders' }],
      },
    },
  });
  // 是 the larger, so it heads a legend that lists a split by size.
  const rows = [
    { warehouse: 'W-0', isNew: true, orders: 3 },
    { warehouse: 'W-0', isNew: false, orders: 2 },
  ];

  it('names each series by its field too, in the legend and the reading', () => {
    chartOf(split, rows);
    expect(legend()).toEqual(['新客：是', '新客：否']);
    expect(reading()).toContain('新客：是');
    expect(reading()).toContain('新客：否');
  });

  it('names a pie’s slices the same way', () => {
    chartOf(
      analysisConfig({
        groups: [{ alias: 'isNew', field: 'isNew', type: 'TERMS' }],
        layout: 'chart',
        chart: { type: 'pie', pie: { category: 'isNew', value: 'orders' } },
      }),
      [
        { isNew: true, orders: 2 },
        { isNew: false, orders: 3 },
      ],
    );
    // Each legend entry carries its share after the name.
    expect(legend().map(entry => entry?.slice(0, 4))).toEqual([
      '新客：是',
      '新客：否',
    ]);
    expect(reading()).toContain('新客：否');
  });

  it('names the series the tooltip reads by it (the plan’s legend)', () => {
    const view = projectAnalysis(definition(), split, rows);
    const plan = cartesianPlan(
      view.chart as Extract<ChartData, { type: 'cartesian' }>,
      {
        spec: split.chart,
        label: (_alias, value) => String(value),
        column: alias => alias,
        seriesName: (alias, value) => `${alias}=${String(value)}`,
        animate: false,
        pickable: false,
      },
    );
    expect(plan.legend.map(entry => entry.name)).toEqual([
      'isNew=true',
      'isNew=false',
    ]);
  });

  it('says it in the surface’s language', () => {
    chartOf(split, rows, defaultMessages);
    expect(legend()).toEqual(['新客: Yes', '新客: No']);
  });

  it('leaves a split by any other field named by its values alone', () => {
    chartOf(
      analysisConfig({
        groups: [
          { alias: 'isNew', field: 'isNew', type: 'TERMS' },
          { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
        ],
        layout: 'chart',
        chart: {
          type: 'bar',
          cartesian: {
            x: 'isNew',
            splitBy: 'warehouse',
            series: [{ metric: 'orders' }],
          },
        },
      }),
      [...rows, { warehouse: 'W-1', isNew: true, orders: 1 }],
    );
    expect(legend()).toEqual(['W-0', 'W-1']);
  });
});

/**
 * A date dimension keeps no sentinel key, so its records with no date come
 * back as a bucket with no key at all (second review R2-P1-4): it is named
 * as the records with no value are, and it stands last wherever the rows
 * run in time — in the table as on the time axis.
 */
describe('the records with no date', () => {
  const month = Date.UTC(2026, 6, 1);
  const byMonth = (sort: AnalysisViewConfig['sort'] = []) =>
    analysisConfig({
      groups: [
        {
          alias: 'month',
          field: 'createdAt',
          type: 'DATE_HISTOGRAM',
          unit: 'MONTH',
          timeZone: 'UTC',
        },
      ],
      sort,
      layout: 'chart',
      chart: {
        type: 'bar',
        cartesian: { x: 'month', series: [{ metric: 'orders' }] },
      },
    });
  const rows = [
    { month, orders: 5 },
    { month: null, orders: 9 },
    { month: Date.UTC(2026, 7, 1), orders: 3 },
  ];

  it('stand last while the rows run in time, and where a metric orders them', () => {
    const project = (config: AnalysisViewConfig) =>
      projectAnalysis(namedOrdersDefinition(), config, rows).rows.map(
        row => row.month,
      );
    expect(project(byMonth())).toEqual([month, Date.UTC(2026, 7, 1), null]);
    expect(project(byMonth([{ alias: 'month', direction: 'DESC' }]))).toEqual([
      month,
      Date.UTC(2026, 7, 1),
      null,
    ]);
    // Ordered by a metric, the bucket stands where its number puts it.
    expect(project(byMonth([{ alias: 'orders', direction: 'DESC' }]))).toEqual([
      month,
      null,
      Date.UTC(2026, 7, 1),
    ]);
  });

  it('read as 「（空）」 on the axis, in the reading and in the sentence', () => {
    const view = projectAnalysis(namedOrdersDefinition(), byMonth(), rows);
    render(
      <ViewSurface messages={zhCN}>
        <AnalysisChart
          data={view.chart!}
          spec={byMonth().chart}
          columns={view.columns}
        />
      </ViewSurface>,
    );
    expect(reading()).toContain('（空）');
    const sentence = document.querySelector(
      '[data-slot="chart-sentence"]',
    )!.textContent!;
    expect(sentence).toContain('（空）');
    expect(sentence).not.toMatch(/到 ，/);
    // Two periods, from July to August: the undated bucket ends nothing.
    expect(sentence).toMatch(/共 2 期，从 July 2026 到 August 2026/);
  });
});
