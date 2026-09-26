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
 * 导览（`Intro.mdx`）里读到的每个数和每个名字，从哪里来。
 *
 * 导览原来把答案手写在 MDX 里：面板改了名、种子一动、图型加了一种，它就和
 * 屏幕对不上了（2026-09-26 审查 P1-7：「（未上报）」其实叫「（空）」，把超时
 * 率 62% 读成了及时率，「11 种图」「18 个问题」早就过时）。现在它只写句子，
 * 数与名都从这里读：
 *
 * - 名字读定义本身：视图与面板的标题、枚举的标签、图型的清单、视图的个数；
 * - 数是那张分析真的查出来的：把保存的配置用引擎的 `compileAnalysis` 编成
 *   Wow 查询，交给场景用的同一个数据源作答，时钟与时区也是场景的那一份。
 *
 * 所以答案与点进去看到的是同一个查询算出来的。句子里「是谁」「在哪一天」
 * 这类判断由 `guide.test.ts` 守着：数据一变，句子不再成立就失败，而不是悄悄
 * 说错。
 * ------------------------------------------------------------------------ */

import {
  builtinFieldKinds,
  CHART_TYPES,
  DEFAULT_MISSING_KEY,
  compileAnalysis,
  compileFilter,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FilterTree,
  type RecordData,
  type RecordViewConfig,
} from '@ahoo-wang/wow-view-engine';
import {
  FULFILMENT,
  OPS_DAILY,
  RETAIL_BOARD_DEFINITIONS,
  SALES_REVIEW,
  retailBoards,
} from './boards.js';
import { WAREHOUSES } from './catalog.js';
import { RETAIL_NOW, shanghai } from './generate.js';
import { DAILY_GOLDEN, REPORT_DAY } from './goldens.js';
import {
  RETAIL_ZONE,
  retailData,
  retailSource,
  type RetailSourceKey,
} from './source.js';
import { ANALYSIS_VIEWS, ORDER_SYSTEM_VIEWS, RETAIL_ORDERS } from './views.js';

const DAY_MS = 86_400_000;

/** The scene's clock and zone: every relative range reads the pinned now. */
const CONTEXT = { now: new Date(RETAIL_NOW), timeZone: RETAIL_ZONE };

function definitionOf(id: string): DataViewDefinition {
  const found = RETAIL_BOARD_DEFINITIONS.find(
    (definition): definition is DataViewDefinition =>
      definition.id === id && 'fields' in definition,
  );
  if (!found) throw new Error(`No retail data definition ${id}.`);
  return found;
}

/** An enum value as the definition labels it — what a chart or cell reads. */
export function optionLabel(
  definitionId: string,
  field: string,
  value: unknown,
): string {
  const options = definitionOf(definitionId).fields.find(
    candidate => candidate.name === field,
  )?.options;
  const option = Array.isArray(options)
    ? options.find(candidate => candidate.value === value)
    : undefined;
  if (!option) throw new Error(`${field} labels no ${String(value)}.`);
  return option.label;
}

/** A saved analysis's own title. */
export function viewTitle(id: string): string {
  const view = ANALYSIS_VIEWS.find(candidate => candidate.id === id);
  if (!view) throw new Error(`No retail analysis ${id}.`);
  return view.title;
}

/** A board's own title. */
export function boardTitle(id: string): string {
  const board = retailBoards.find(candidate => candidate.id === id);
  if (!board) throw new Error(`No retail board ${id}.`);
  return board.title;
}

/** The title a panel carries on its board. */
export function panelTitle(boardId: string, panelId: string): string {
  return panelOf(boardId, panelId).title ?? '';
}

function panelOf(boardId: string, panelId: string) {
  const board = retailBoards.find(candidate => candidate.id === boardId);
  const config = board?.config;
  const panel =
    config?.kind === 'dashboard'
      ? config.panels.find(candidate => candidate.id === panelId)
      : undefined;
  if (!panel) throw new Error(`${boardId} has no panel ${panelId}.`);
  return panel;
}

/** What one analysis answers, compiled and run as the scene runs it. */
async function answer(
  definitionId: string,
  config: AnalysisViewConfig,
): Promise<RecordData[]> {
  const definition = definitionOf(definitionId);
  const query = compileAnalysis(definition, config, builtinFieldKinds, CONTEXT);
  return retailSource(definition.source as RetailSourceKey).aggregate(query);
}

function savedAnalysis(id: string) {
  const view = ANALYSIS_VIEWS.find(candidate => candidate.id === id);
  if (view?.config.kind !== 'analysis')
    throw new Error(`No retail analysis ${id}.`);
  return { definitionId: view.definitionId, config: view.config };
}

const num = (row: RecordData | undefined, alias: string): number =>
  Number(row?.[alias] ?? Number.NaN);

/** A share as the guide says it: a whole percent, or one decimal under 1%. */
export function percent(value: number): string {
  const digits = Math.abs(value) < 0.01 ? 1 : 0;
  return `${(value * 100).toFixed(digits)}%`;
}

/** A local calendar day in the scene's zone, as 「3 月 8 日」. */
export function monthDay(at: number): string {
  const [, month, day] = new Intl.DateTimeFormat('en-CA', {
    timeZone: RETAIL_ZONE,
  })
    .format(at)
    .split('-')
    .map(Number);
  return `${month} 月 ${day} 日`;
}

// ---------------------------------------------------------------- 计数

/** The counts the guide cites that need no data, read off what they count. */
export const GUIDE_COUNTS = {
  /** Chart types the engine draws (`CHART_TYPES`). */
  chartTypes: CHART_TYPES.length,
  /** Questions the analysts saved on the analysis workbench. */
  analysisQuestions: ANALYSIS_VIEWS.length,
} as const;

/** How big the data set is; generating it is what takes the time. */
function sizes() {
  const data = retailData();
  return {
    orders: data.orders.length,
    afterSales: data.afterSales.length,
    members: data.members.length,
    waybills: data.waybills.length,
    events: data.events.length,
  };
}

/**
 * 「2 万张」「1700 张」: a count rounded to what a reader keeps in mind, with
 * its measure word — after 万 with no space, after digits with one.
 */
export function about(count: number, measure: string): string {
  if (count >= 10_000) return `${Math.round(count / 1_000) / 10} 万${measure}`;
  const magnitude = 10 ** Math.max(0, Math.floor(Math.log10(count)) - 1);
  return `${Math.round(count / magnitude) * magnitude} ${measure}`;
}

// ---------------------------------------------------------------- 七处异常

/** A1: the product whose refund rate jumped, on 「退款率最高的商品」. */
export async function refundOutlier() {
  const id = 'a07-refund-outliers';
  const { definitionId, config } = savedAnalysis(id);
  const rows = await answer(definitionId, config);
  const [top, ...rest] = rows;
  return {
    view: viewTitle(id),
    panel: panelTitle(SALES_REVIEW, 'refund-skus'),
    title: String(top?.title),
    rate: num(top, 'refundRate'),
    /** The highest refund rate among the others the analysis lists. */
    othersAtMost: Math.max(...rest.map(row => num(row, 'refundRate'))),
  };
}

/** A2: the carrier that slowed in 广东 in the typhoon weeks. */
export async function typhoonCarrier() {
  const panel = panelOf(FULFILMENT, 'carrier-weeks');
  if (
    panel.kind !== 'view' ||
    !panel.owned ||
    panel.owned.config.kind !== 'analysis'
  )
    throw new Error('carrier-weeks owns no analysis.');
  const { definitionId, config } = panel.owned;
  // The scene 「履约 · 广东省」 sets the board's 省份 to 广东省, which reaches
  // this panel as a condition on the waybill's province.
  const inGuangdong: AnalysisViewConfig = {
    ...config,
    filter: {
      op: 'and',
      children: [
        config.filter,
        { field: 'state.province', operator: 'IN', value: ['广东省'] },
      ],
    } as FilterTree,
  };
  const rows = await answer(definitionId, inGuangdong);
  // 「突然变慢」 is against a carrier's own usual week, not against the
  // others: EMS is slow every week, which is not news.
  const byCarrier = new Map<string, RecordData[]>();
  for (const row of rows) {
    const carrier = String(row.carrier);
    byCarrier.set(carrier, [...(byCarrier.get(carrier) ?? []), row]);
  }
  const jumps = [...byCarrier].map(([carrier, weeks]) => {
    const hours = weeks.map(row => num(row, 'hours')).sort((a, b) => a - b);
    const peak = weeks.reduce((worst, row) =>
      num(row, 'hours') > num(worst, 'hours') ? row : worst,
    );
    const usual = hours[Math.floor(hours.length / 2)] ?? Number.NaN;
    return { carrier, peak, usual, jump: num(peak, 'hours') / usual };
  });
  const [worst, next] = jumps.sort((left, right) => right.jump - left.jump);
  if (!worst) throw new Error('carrier-weeks answered nothing in 广东省.');
  return {
    panel: panel.title ?? '',
    carrier: optionLabel(definitionId, 'state.carrier', worst.carrier),
    hours: num(worst.peak, 'hours'),
    usualHours: worst.usual,
    week: Number(worst.peak.week),
    /** How many times its usual week the runner-up's worst week was. */
    nextJump: next?.jump ?? 0,
    jump: worst.jump,
  };
}

/** A3: the live-stream day whose stacked coupons stand apart. */
export async function stackedCoupons() {
  const id = 'a14-discount-refund';
  const { definitionId, config } = savedAnalysis(id);
  const rows = await answer(definitionId, config);
  const byDiscount = [...rows].sort(
    (left, right) => num(right, 'discountRate') - num(left, 'discountRate'),
  );
  const [day, next] = byDiscount;
  return {
    view: viewTitle(id),
    day: Number(day?.day),
    discountRate: num(day, 'discountRate'),
    othersAtMost: num(next, 'discountRate'),
  };
}

/** A4: the payment method that failed at Double 11's midnight. */
export async function midnightFailure() {
  const id = 'a10-double11-midnight';
  const { definitionId, config } = savedAnalysis(id);
  const [worst] = await answer(definitionId, config);
  return {
    view: viewTitle(id),
    method: optionLabel(definitionId, 'state.payment.method', worst?.method),
    timeoutRate: num(worst, 'timeoutRate'),
  };
}

/** A5: the orders with no city, on 「城市等级 × 渠道」. */
export async function missingCity() {
  const id = 'a06-tier-channel';
  const { definitionId, config } = savedAnalysis(id);
  const rows = await answer(definitionId, config);
  // The empty group's key is the view's `missingKey`; the screen reads it as
  // the engine's missing-group label, 「（空）」.
  const missing = rows.filter(row => row.tier === DEFAULT_MISSING_KEY);
  const count = missing.reduce((total, row) => total + num(row, 'orders'), 0);
  const all = rows.reduce((total, row) => total + num(row, 'orders'), 0);
  return {
    view: viewTitle(id),
    panel: panelTitle(SALES_REVIEW, 'tiers'),
    count,
    share: count / all,
    channels: [
      ...new Set(
        missing.map(row =>
          optionLabel(definitionId, 'state.channel', row.channel),
        ),
      ),
    ],
  };
}

/** A6: the Spring Festival weeks on 「每周发货超时率」. */
export async function springFestival() {
  const id = 'a12-weekly-sla';
  const { definitionId, config } = savedAnalysis(id);
  const rows = await answer(definitionId, config);
  const redLine =
    config.chart.cartesian?.referenceLines?.find(line => line.axis === 'left')
      ?.value ?? Number.NaN;
  const lastYear = rows.filter(
    row => Number(row.week) >= RETAIL_NOW - 365 * DAY_MS,
  );
  const peak = lastYear.reduce((worst, row) =>
    num(row, 'lateRate') > num(worst, 'lateRate') ? row : worst,
  );
  const peakWeek = Number(peak.week);
  // The first week after the peak back under the red line.
  const back = rows.find(
    row => Number(row.week) > peakWeek && num(row, 'lateRate') <= redLine,
  );
  return {
    view: viewTitle(id),
    panel: panelTitle(FULFILMENT, 'breach-rate'),
    week: peakWeek,
    lateRate: num(peak, 'lateRate'),
    redLine,
    backWeek: Number(back?.week),
  };
}

/** A7: yesterday's warehouse trouble, on the daily report. */
export async function sortingLine() {
  const system = ORDER_SYSTEM_VIEWS.find(view => view.id === 'ship-overdue');
  if (system?.config.kind !== 'record')
    throw new Error('No overdue order view.');
  const definition = definitionOf(RETAIL_ORDERS);
  const config: RecordViewConfig = system.config;
  const filter = compileFilter(
    definition.fields,
    config.filter,
    builtinFieldKinds,
    CONTEXT,
  );
  const { list, total } = await retailSource(
    definition.source as RetailSourceKey,
  ).paged({ filter, pagination: { index: 1, size: 1_000 } });
  const warehouses = [
    ...new Set(list.map(row => (row.state as { warehouse: string }).warehouse)),
  ].map(id => WAREHOUSES.find(warehouse => warehouse.id === id)?.name ?? id);
  return {
    board: boardTitle(OPS_DAILY),
    onTimeCard: panelTitle(OPS_DAILY, 'on-time'),
    overdueView: panelTitle(OPS_DAILY, 'overdue'),
    reportDay: monthDay(shanghai(REPORT_DAY)),
    onTime: DAILY_GOLDEN.onTime.value,
    target: DAILY_GOLDEN.onTime.target,
    overdue: total,
    warehouses,
  };
}

/** Every answer, fetched together; the guide shows them once all are in. */
export async function guideAnswers() {
  const [a1, a2, a3, a4, a5, a6, a7] = await Promise.all([
    refundOutlier(),
    typhoonCarrier(),
    stackedCoupons(),
    midnightFailure(),
    missingCity(),
    springFestival(),
    sortingLine(),
  ]);
  return { a1, a2, a3, a4, a5, a6, a7, sizes: sizes() };
}

export type GuideAnswers = Awaited<ReturnType<typeof guideAnswers>>;
