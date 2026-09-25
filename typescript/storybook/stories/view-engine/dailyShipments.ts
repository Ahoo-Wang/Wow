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

/* --------------------------------------------------------------------------
 * 长时间轴：一年的日数据，与一万天的日数据。
 *
 * 批 A「读得清长时间轴」要问的都是点多的图：一年 365 天缩到一周还读得出每一天、
 * 两个仓库的线在图例里藏起一条、悬停写「较上一期」、一万天的折线画得够快。订单
 * 与运单都只有几十行、三十来天，问不出这些。
 *
 * 行是**算出来**的：每一格只由天数与仓库决定，所以同一天永远是同一个数，回归
 * 可以按值断言。一年那份每天每仓一单，金额随星期与季节起伏；一万天那份每天一单。
 * ------------------------------------------------------------------------ */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  SortDirection,
} from '@ahoo-wang/wow-client';
import {
  fitChartSlots,
  type AnalysisViewConfig,
  type ChartType,
  type DataViewDefinition,
  type RecordData,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { analysisConfig } from './fixtures.js';
import { rowSource } from './rowSource.js';

const DAY_MS = 86_400_000;

/** The last day every series here ends on, at noon UTC so no zone moves it. */
const LAST_DAY = Date.UTC(2026, 8, 23, 12);

const WAREHOUSES = [
  { value: 'CN-EAST', label: '华东仓' },
  { value: 'CN-SOUTH', label: '华南仓' },
];

/** 发货记录：仓库、创建时间与金额，按日、按仓库分析。 */
export const shipmentsDefinition: DataViewDefinition = {
  id: 'shipments',
  title: '发货',
  recordNoun: '发货单',
  kind: 'data',
  source: 'shipments',
  fields: [
    { name: 'id', label: '单号', kind: 'string', sortable: true },
    {
      name: 'warehouse',
      label: '发货仓',
      kind: 'enum',
      options: WAREHOUSES,
    },
    {
      name: 'createdAt',
      label: '创建时间',
      kind: 'datetime',
      temporal: { type: 'date' },
      sortable: true,
    },
    {
      name: 'amount',
      label: '金额',
      kind: 'number',
      numberFormat: { style: 'currency', currency: 'CNY' },
    },
  ],
  // The records behind a day: what 「查看这些记录」 opens (批 C).
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
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
        dateUnits: [AggregationDateUnit.DAY],
      },
      {
        field: 'amount',
        groups: [],
        functions: [AggregationFunction.SUM],
      },
    ],
  },
};

/** A day's amount: a weekly rhythm over a slow season, never below 100. */
function amountOn(day: number, warehouse: number): number {
  const weekly = [0.7, 1, 1.1, 1.05, 1.2, 0.9, 0.6][day % 7];
  const season = 1 + 0.35 * Math.sin((day / 365) * 2 * Math.PI);
  return Math.round((warehouse === 0 ? 1200 : 800) * weekly * season);
}

/** `days` days ending on `LAST_DAY`, one shipment a day from each warehouse. */
function shipments(days: number, warehouses: number): RecordData[] {
  return Array.from({ length: days }, (_, day) =>
    WAREHOUSES.slice(0, warehouses).map((warehouse, index) => ({
      id: `S${day}-${index}`,
      warehouse: warehouse.value,
      createdAt: new Date(LAST_DAY - (days - 1 - day) * DAY_MS).toISOString(),
      amount: amountOn(day, index),
    })),
  ).flat();
}

/** 一年：365 天，两个仓库各一单。 */
export const YEAR_OF_SHIPMENTS = shipments(365, 2);

/** 一个月：30 天，两个仓库各一单——框选几天读得出每一根柱（批 C）。 */
export const MONTH_OF_SHIPMENTS = shipments(30, 2);

/**
 * How many days the long scene runs: Wow's own `AGGREGATION_LIMITS.MAX_LIMIT`,
 * the most a result holds. The engine asks no more than a default server's
 * HTTP guard admits (`maxAnalysisRows`, 1,000; D42), so the scene's engine is
 * a host that raised it (`SHIPMENT_LIMITS`) — the story source has no guard.
 */
const LONG_RUN = 10_000;

/** The engine limits of the long scene: every day it runs asked for. */
export const SHIPMENT_LIMITS = { maxAnalysisRows: LONG_RUN };

export type ShipmentScene =
  'year' | 'year-bars' | 'month' | 'ten-thousand-days';

/**
 * 按日数金额：`year` 按仓库拆成两条线；`year-bars` 同一年画成堆叠的柱；
 * `ten-thousand-days` 一条一万个点的线。
 */
export function shipmentsConfig(
  scene: ShipmentScene,
  type: ChartType = scene === 'year-bars' || scene === 'month' ? 'bar' : 'line',
): AnalysisViewConfig {
  const split = scene !== 'ten-thousand-days';
  const groups: AnalysisViewConfig['groups'] = [
    {
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      alias: 'day',
      unit: 'DAY',
    },
    ...(split
      ? [{ type: 'TERMS' as const, field: 'warehouse', alias: 'warehouse' }]
      : []),
  ];
  const metrics = [
    {
      alias: 'amount',
      type: 'NUMERIC',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'amount' },
    },
  ] satisfies AnalysisViewConfig['metrics'];
  const fitted = fitChartSlots({ type }, groups, metrics);
  return analysisConfig({
    layout: 'chart',
    groups,
    metrics,
    sort: [{ alias: 'day', direction: SortDirection.ASC }],
    // A year is 730 rows; only the long scene asks for its ten thousand.
    limit: split ? 1_000 : LONG_RUN,
    table: { columns: [] },
    chart:
      (scene === 'year-bars' || scene === 'month') && fitted.cartesian
        ? {
            ...fitted,
            cartesian: {
              ...fitted.cartesian,
              series: fitted.cartesian.series.map(series => ({
                ...series,
                stack: 'all',
              })),
            },
          }
        : fitted,
  });
}

/** 发货上的一个分析视图。 */
export function shipmentsView(config: AnalysisViewConfig): ViewInstance {
  return {
    id: 'shipments-analysis',
    definitionId: shipmentsDefinition.id,
    title: '每日发货金额',
    scope: 'shared',
    revision: '1',
    config,
  };
}

/**
 * 一个场景的行背后的数据源。一年那份由 `rowSource` 真的按查询过滤、分桶、汇总；
 * 一万天那份直接答按日的桶——一万行在页面里逐行按时区切日要好几秒，而这个
 * 场景量的是图画得多快，不是故事的数据源算得多快。它只答它认得的那一个问题
 * （按 `day` 分桶、汇总 `amount`），别的一律拒绝，免得答出似是而非的数。
 */
export function shipmentsSource(scene: ShipmentScene): ViewSource {
  if (scene === 'ten-thousand-days') return longRunSource();
  return rowSource(scene === 'month' ? MONTH_OF_SHIPMENTS : YEAR_OF_SHIPMENTS);
}

function longRunSource(): ViewSource {
  const refuse = async (): Promise<never> => {
    throw new Error('The long-run story source answers its one question only.');
  };
  return {
    paged: refuse,
    cursor: refuse,
    aggregate: async query => {
      const [group, ...rest] = query.groupBy ?? [];
      if (
        rest.length > 0 ||
        group?.type !== AggregationGroupType.DATE_HISTOGRAM ||
        group.alias !== 'day' ||
        group.unit !== AggregationDateUnit.DAY
      )
        return refuse();
      // Each day's start on the page's own clock — the zone the engine asks
      // in — as the service keys a day bucket.
      const last = new Date(LAST_DAY);
      return Array.from(
        { length: Math.min(LONG_RUN, query.limit ?? LONG_RUN) },
        (_, day) => ({
          day: new Date(
            last.getFullYear(),
            last.getMonth(),
            last.getDate() - (LONG_RUN - 1 - day),
          ).getTime(),
          amount: amountOn(day, 0),
        }),
      );
    },
  };
}
