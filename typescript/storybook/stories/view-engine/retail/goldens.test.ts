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
import { describe, expect, it } from 'vitest';
import {
  BUILTIN_FIELD_KINDS,
  validateAnalysis,
  validateDefinition,
  validateRecord,
  type AnalysisViewConfig,
  type DashboardViewConfig,
  type DataViewDefinition,
  type RecordViewConfig,
} from '@ahoo-wang/wow-view-engine';
import {
  RETAIL_BOARD_DEFINITIONS,
  retailBoards,
  retailInstances,
} from './boards.js';
import { BATH_TOWEL_SKU_ID } from './catalog.js';
import { retailData } from './source.js';
import { generateRetail, shanghai, RETAIL_NOW } from './generate.js';
import {
  ANALYSIS_GOLDEN,
  DAILY_GOLDEN,
  OVERDUE_LIVE_ORDERS,
  OVERDUE_ORDERS,
  REPORT_DAY,
} from './goldens.js';

const DAY = 86_400_000;
const data = retailData();
const orders = data.orders.map(({ firstEventTime, state }) => ({
  firstEventTime,
  ...state,
}));

const yuan = (value: number) =>
  `¥${value.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
const percent = (value: number) =>
  `${(Math.round(value * 1000) / 10).toString()}%`;
const change = (now: number, before: number) => {
  const ratio = Math.round(((now - before) / before) * 1000) / 10;
  return `${ratio > 0 ? '+' : ''}${ratio}%`;
};

/** The daily numbers of one Shanghai day, in the daily report's own terms. */
function day(date: string) {
  const from = shanghai(date);
  const placed = orders.filter(
    o => o.firstEventTime >= from && o.firstEventTime < from + DAY,
  );
  const due = orders.filter(
    o =>
      o.timing.shipDueAt !== null &&
      o.timing.shipDueAt >= from &&
      o.timing.shipDueAt < from + DAY &&
      o.cancelReason === null,
  );
  const sum = (list: typeof orders, pick: (o: (typeof orders)[0]) => number) =>
    list.reduce((total, o) => total + pick(o), 0);
  const gmv = sum(placed, o => o.amounts.payableAmount);
  return {
    gmv,
    paid: sum(placed, o => o.amounts.paidAmount),
    orders: placed.length,
    newBuyers: placed.filter(o => o.buyer.isNewBuyer).length,
    aov: gmv / placed.length,
    conversion:
      placed.filter(o => o.timing.paidAt !== null).length / placed.length,
    onTime: due.filter(o => !o.shipSlaBreached).length / due.length,
    refunded: data.afterSales
      .filter(
        ({ state }) =>
          state.refundedAt !== null &&
          state.refundedAt >= from &&
          state.refundedAt < from + DAY,
      )
      .reduce((total, { state }) => total + state.refundedAmount, 0),
  };
}

describe('the retail boards’ golden numbers (docs/scenarios.md 6.3)', () => {
  it('reads 2026-09-21 on the daily report the way the data set counts it', () => {
    const today = day(REPORT_DAY);
    const before = day('2026-09-20');
    const cards = DAILY_GOLDEN.cards;
    expect(yuan(today.gmv)).toBe(cards.GMV);
    expect(yuan(today.paid)).toBe(cards.实付金额);
    expect(String(today.orders)).toBe(cards['订单数（单）']);
    expect(String(today.newBuyers)).toBe(cards['新客数（人）']);
    expect(yuan(today.aov)).toBe(cards['客单价 · 昨日较近 30 天']);
    expect(percent(today.conversion)).toBe(
      cards['支付转化率 · 昨日较近 30 天'],
    );
    expect(yuan(today.refunded)).toBe(cards.售后退款);
    expect(percent(today.onTime)).toBe(cards['发货及时率 · 昨日较近 30 天']);
    // A7: under the 95% target.
    expect(today.onTime).toBeLessThan(0.95);

    const changes = DAILY_GOLDEN.changes;
    expect(change(today.gmv, before.gmv)).toBe(changes.GMV);
    expect(change(today.paid, before.paid)).toBe(changes.实付金额);
    expect(change(today.orders, before.orders)).toBe(changes['订单数（单）']);
    expect(change(today.newBuyers, before.newBuyers)).toBe(
      changes['新客数（人）'],
    );
    expect(change(today.refunded, before.refunded)).toBe(changes.售后退款);
  });

  it('reads the analysis workbench’s ratio trend and element drill the way the data set counts them (D38)', () => {
    // 客单价 per day is that day's sums divided, the daily report's card.
    const today = day(REPORT_DAY);
    const before = day('2026-09-20');
    expect(yuan(today.aov)).toBe(ANALYSIS_GOLDEN.aov.value);
    expect(change(today.aov, before.aov)).toBe(ANALYSIS_GOLDEN.aov.change);
    // The records behind the towel's bar: in the last three months (from
    // the same moment three months back), an order with a line of it.
    const from = shanghai('2026-06-22') + 10 * 3_600_000;
    const towel = orders.filter(
      o =>
        o.firstEventTime >= from &&
        o.firstEventTime <= RETAIL_NOW &&
        o.items.some(item => item.skuId === BATH_TOWEL_SKU_ID),
    );
    expect(towel.length).toBe(ANALYSIS_GOLDEN.towelOrders);
  });

  it('lists the orders paid over 48 hours ago and still unshipped, oldest payment first', () => {
    // The order workbench's 「发货超时」: waiting to ship, breached, not presale.
    const overdue = orders
      .filter(
        o =>
          ['PAID', 'PARTIALLY_SHIPPED'].includes(o.status) &&
          o.shipSlaBreached &&
          !o.tags.includes('PRESALE'),
      )
      .sort((a, b) => a.timing.paidAt! - b.timing.paidAt!);
    expect(overdue.map(o => o.orderNo)).toEqual(OVERDUE_ORDERS);
    expect(
      overdue.filter(o => o.channel === 'LIVE').map(o => o.orderNo),
    ).toEqual(OVERDUE_LIVE_ORDERS);
    for (const order of overdue) {
      expect(order.warehouse).toBe('EAST');
      expect(order.timing.paidAt!).toBeLessThan(RETAIL_NOW - 48 * 3_600_000);
    }
  });

  it('is the data set the generator makes for the default seed', () => {
    expect(data.orders.length).toBe(generateRetail().orders.length);
  });
});

describe('the definitions, views and boards the retail boards stand on', () => {
  const kinds = new Map(BUILTIN_FIELD_KINDS.map(kind => [kind.id, kind]));
  const byId = new Map(RETAIL_BOARD_DEFINITIONS.map(d => [d.id, d]));
  const admit = (definitionId: string, config: unknown) => {
    const definition = byId.get(definitionId) as DataViewDefinition;
    const view = config as AnalysisViewConfig | RecordViewConfig;
    return view.kind === 'analysis'
      ? validateAnalysis(definition, view, kinds)
      : validateRecord(definition, view, kinds);
  };

  it('admits every definition, the order definition with the product name it searches', () => {
    for (const definition of RETAIL_BOARD_DEFINITIONS)
      expect(validateDefinition(definition, kinds)).toEqual([]);
  });

  it('admits every view the boards’ engine holds and every analysis a board owns', () => {
    for (const view of retailInstances)
      if (view.config.kind !== 'dashboard')
        expect([view.id, admit(view.definitionId, view.config)]).toEqual([
          view.id,
          [],
        ]);
    for (const board of retailBoards)
      for (const panel of (board.config as DashboardViewConfig).panels)
        if (panel.kind === 'view' && panel.owned)
          expect([
            board.id,
            panel.id,
            admit(panel.owned.definitionId, panel.owned.config),
          ]).toEqual([board.id, panel.id, []]);
  });
});
