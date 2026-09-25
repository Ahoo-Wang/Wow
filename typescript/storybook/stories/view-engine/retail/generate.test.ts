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
 * 零售数据集的规则（docs/scenarios.md 2.4 与 2.6）：一条规则一个测试。
 *
 * 数据由种子决定，所以这里断言的是**规则**，不是某个黄金值：一致性规则对
 * 每一行成立；分布护栏写成区间；每处埋下的异常都要在数据里找得到。
 * ------------------------------------------------------------------------ */

import { describe, expect, it } from 'vitest';
import { PROVINCES, SKU_BY_ID } from './catalog.js';
import {
  ANOMALIES,
  EVENT_WINDOW_DAYS,
  generateRetail,
  RETAIL_DEFAULTS,
  RETAIL_NOW,
  shanghai,
  shanghaiDayStart,
  SHIP_SLA_HOURS,
  type RetailOrderState,
  type RetailSnapshot,
} from './generate.js';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const data = generateRetail();
const orders = data.orders;
const now = RETAIL_NOW;

/** 金额按分比较：元值乘 100 必须是整数，不带浮点尾巴。 */
function cents(value: number): number {
  const scaled = Math.round(value * 100);
  if (Math.abs(value * 100 - scaled) > 1e-6) {
    throw new Error(`${value} has more than two decimals`);
  }
  return scaled;
}

function median(values: readonly number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = sorted.length >> 1;
  return sorted.length % 2
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
}

function groupBy<T>(
  values: readonly T[],
  key: (value: T) => string,
): Map<string, T[]> {
  const groups = new Map<string, T[]>();
  for (const value of values) {
    const group = groups.get(key(value));
    if (group) group.push(value);
    else groups.set(key(value), [value]);
  }
  return groups;
}

function sum(values: Iterable<number>): number {
  let total = 0;
  for (const value of values) total += value;
  return total;
}

function placedBetween(
  from: number,
  to: number,
  where: (order: RetailSnapshot<RetailOrderState>) => boolean = () => true,
): RetailSnapshot<RetailOrderState>[] {
  return orders.filter(
    order =>
      order.firstEventTime >= from && order.firstEventTime < to && where(order),
  );
}

/** 按时发货：付款后 48 小时内发出；到「现在」还没发、也没到期的不算。 */
function onTimeShare(list: readonly RetailOrderState[]): number {
  const due = list.filter(
    order => order.timing.paidAt !== null && order.cancelReason === null,
  );
  return due.filter(order => !order.shipSlaBreached).length / due.length;
}

describe('generateRetail: consistency (2.4)', () => {
  it('rule 1: line payments plus freight make the payable amount, and the discounts make up the rest', () => {
    for (const { state } of orders) {
      const amounts = state.amounts;
      let lines = 0;
      let list = 0;
      let item = 0;
      let shares = 0;
      for (const line of state.items) {
        const pay = cents(line.payAmount);
        expect(pay).toBe(
          cents(line.salePrice) * line.qty - cents(line.discountShare),
        );
        expect(pay).toBeGreaterThanOrEqual(0);
        lines += pay;
        list += cents(line.listPrice) * line.qty;
        item += (cents(line.listPrice) - cents(line.salePrice)) * line.qty;
        shares += cents(line.discountShare);
      }
      const payable = cents(amounts.payableAmount);
      expect(lines + cents(amounts.freight)).toBe(payable);
      expect(cents(amounts.listAmount)).toBe(list);
      expect(cents(amounts.itemDiscount)).toBe(item);
      const orderLevel =
        cents(amounts.fullReduction) +
        cents(amounts.shopCoupon) +
        cents(amounts.platformCoupon) +
        cents(amounts.pointsDeduct);
      expect(orderLevel).toBe(shares);
      expect(cents(amounts.itemDiscount) + orderLevel).toBe(
        list + cents(amounts.freight) - payable,
      );
    }
  });

  it('rule 2: a paid order has paid what it owes, never gets back more, and is closed exactly when refunded in full', () => {
    for (const { state } of orders) {
      const { paidAmount, payableAmount, refundedAmount } = state.amounts;
      const paid = state.timing.paidAt !== null;
      expect(cents(paidAmount)).toBe(paid ? cents(payableAmount) : 0);
      expect(cents(refundedAmount)).toBeLessThanOrEqual(cents(paidAmount));
      const fullyRefunded = paid && cents(refundedAmount) === cents(paidAmount);
      if (state.status === 'CANCELLED') {
        // 付款后取消的单原路全额退回；没付款的单什么也没退。
        expect(cents(refundedAmount)).toBe(cents(paidAmount));
      } else {
        expect(state.status === 'CLOSED').toBe(fullyRefunded);
      }
    }
  });

  it('rule 3: times only move forward, cancelling happens before shipping, and after shipping only rejection or after-sale remain', () => {
    const afterSalesByOrder = groupBy(
      data.afterSales,
      afterSale => afterSale.state.orderNo,
    );
    for (const order of orders) {
      const { timing, packages, status } = order.state;
      const chain = [
        order.firstEventTime,
        timing.paidAt,
        timing.shippedAt,
        timing.signedAt,
        timing.completedAt,
      ].filter((time): time is number => time !== null);
      expect(chain).toEqual([...chain].sort((a, b) => a - b));
      for (const time of chain) expect(time).toBeLessThanOrEqual(now);
      for (const pkg of packages) {
        expect(pkg.shippedAt).toBeGreaterThanOrEqual(timing.paidAt!);
        if (pkg.signedAt !== null)
          expect(pkg.signedAt).toBeGreaterThan(pkg.shippedAt);
      }
      if (timing.cancelledAt !== null) {
        expect(status).toBe('CANCELLED');
        expect(packages).toEqual([]);
        expect(timing.cancelledAt).toBeGreaterThanOrEqual(
          timing.paidAt ?? order.firstEventTime,
        );
      }
      for (const afterSale of afterSalesByOrder.get(order.aggregateId) ?? []) {
        expect(timing.shippedAt).not.toBeNull();
        expect(afterSale.state.requestedAt).toBeGreaterThanOrEqual(
          timing.shippedAt!,
        );
      }
    }
  });

  it('rule 4: every SKU is in the catalog as copied, every owner is a member, and the members add up to their orders', () => {
    const members = new Map(data.members.map(m => [m.aggregateId, m.state]));
    const expected = new Map<
      string,
      {
        first: RetailOrderState | undefined;
        parents: Set<string>;
        paid: number;
      }
    >();
    for (const order of orders) {
      const { state } = order;
      for (const line of state.items) {
        const sku = SKU_BY_ID.get(line.skuId);
        expect(sku, line.skuId).toBeDefined();
        expect({
          spuId: line.spuId,
          title: line.title,
          category1: line.category1,
          category2: line.category2,
          brand: line.brand,
          priceBand: line.priceBand,
          listPrice: line.listPrice,
        }).toEqual({
          spuId: sku!.spuId,
          title: sku!.title,
          category1: sku!.category1,
          category2: sku!.category2,
          brand: sku!.brand,
          priceBand: sku!.priceBand,
          listPrice: sku!.listPrice,
        });
      }
      const member = members.get(order.ownerId);
      expect(member, order.ownerId).toBeDefined();
      expect(state.buyer).toMatchObject({
        id: order.ownerId,
        nick: member!.nick,
        level: member!.level,
      });
      const tally = expected.get(order.ownerId) ?? {
        first: undefined,
        parents: new Set<string>(),
        paid: 0,
      };
      expected.set(order.ownerId, tally);
      const paidAt = state.payment.paidAt;
      if (paidAt === null) continue;
      tally.parents.add(state.parentOrderNo);
      tally.paid += cents(state.amounts.paidAmount);
      const first = tally.first;
      if (
        !first ||
        paidAt < first.payment.paidAt! ||
        (paidAt === first.payment.paidAt && state.orderNo < first.orderNo)
      ) {
        tally.first = state;
      }
    }
    expect([...expected.keys()].sort()).toEqual([...members.keys()].sort());
    for (const [id, member] of members) {
      const tally = expected.get(id)!;
      expect({
        firstOrderAt: member.firstOrderAt,
        orderCount: member.orderCount,
        totalPaid: cents(member.totalPaid),
      }).toEqual({
        firstOrderAt: tally.first?.payment.paidAt ?? null,
        orderCount: tally.parents.size,
        totalPaid: tally.paid,
      });
    }
    // 新客只标在每个买家第一张已付款的子单上。
    const firsts = new Set(
      [...expected.values()].flatMap(tally =>
        tally.first ? [tally.first.orderNo] : [],
      ),
    );
    for (const { state } of orders) {
      expect(state.buyer.isNewBuyer).toBe(firsts.has(state.orderNo));
    }
  });

  it('rule 5: waybills are exactly the packages, and every after-sale points at a real line and refunds no more than it paid', () => {
    const packages = orders.flatMap(order =>
      order.state.packages.map(pkg => ({
        waybillNo: pkg.waybillNo,
        packageNo: pkg.packageNo,
        orderNo: order.state.orderNo,
        carrier: pkg.carrier,
        shippedAt: pkg.shippedAt,
        signedAt: pkg.signedAt,
        lineCount: pkg.lineIds.length,
      })),
    );
    const waybills = data.waybills.map(({ aggregateId, state }) => {
      expect(aggregateId).toBe(state.waybillNo);
      return {
        waybillNo: state.waybillNo,
        packageNo: state.packageNo,
        orderNo: state.orderNo,
        carrier: state.carrier,
        shippedAt: state.shippedAt,
        signedAt: state.signedAt,
        lineCount: state.lineCount,
      };
    });
    const byNo = (a: { waybillNo: string }, b: { waybillNo: string }) =>
      a.waybillNo.localeCompare(b.waybillNo);
    expect(waybills.sort(byNo)).toEqual(packages.sort(byNo));
    expect(new Set(waybills.map(w => w.waybillNo)).size).toBe(waybills.length);

    const byOrderNo = new Map(orders.map(order => [order.aggregateId, order]));
    const refundedByLine = new Map<string, number>();
    for (const { state } of data.afterSales) {
      const order = byOrderNo.get(state.orderNo);
      expect(order, state.orderNo).toBeDefined();
      const line = order!.state.items.find(
        item => item.lineId === state.lineId,
      );
      expect(line, `${state.orderNo} ${state.lineId}`).toBeDefined();
      expect(state.skuId).toBe(line!.skuId);
      expect(cents(state.refundedAmount)).toBeLessThanOrEqual(
        cents(line!.payAmount),
      );
      const key = `${state.orderNo}/${state.lineId}`;
      refundedByLine.set(
        key,
        (refundedByLine.get(key) ?? 0) + cents(state.refundedAmount),
      );
    }
    // 没取消的单，每一行的已退就是它的售后实退之和。
    for (const { state } of orders) {
      if (state.status === 'CANCELLED') continue;
      for (const line of state.items) {
        expect(cents(line.refundedAmount)).toBe(
          refundedByLine.get(`${state.orderNo}/${line.lineId}`) ?? 0,
        );
        expect(cents(line.refundedAmount)).toBeLessThanOrEqual(
          cents(line.payAmount),
        );
      }
    }
  });

  it('rule 6: event streams run from version 1 without gaps, at the times the snapshot records, and the snapshot version counts them', () => {
    const streams = groupBy(data.events, event => event.aggregateId);
    const windowStart = now - EVENT_WINDOW_DAYS * DAY;
    for (const order of orders) {
      const list = streams.get(order.aggregateId);
      if (order.firstEventTime < windowStart) {
        expect(list).toBeUndefined();
        continue;
      }
      expect(list, order.aggregateId).toBeDefined();
      const sorted = [...list!].sort((a, b) => a.version - b.version);
      expect(sorted.map(event => event.version)).toEqual(
        sorted.map((_, index) => index + 1),
      );
      expect(order.version).toBe(sorted.length);
      const times = sorted.map(event => event.createTime);
      expect(times).toEqual([...times].sort((a, b) => a - b));
      expect(times[0]).toBe(order.firstEventTime);
      expect(times.at(-1)).toBe(order.eventTime);

      const at = (name: string): number[] =>
        sorted
          .filter(event => event.body.some(body => body.name === name))
          .map(event => event.createTime);
      const { timing, packages } = order.state;
      expect(at('order_created')).toEqual([order.firstEventTime]);
      expect(at('order_paid')).toEqual(
        timing.paidAt === null ? [] : [timing.paidAt],
      );
      expect(at('order_cancelled')).toEqual(
        timing.cancelledAt === null ? [] : [timing.cancelledAt],
      );
      expect(at('order_completed')).toEqual(
        timing.completedAt === null ? [] : [timing.completedAt],
      );
      expect(at('order_closed')).toEqual(
        timing.closedAt === null ? [] : [timing.closedAt],
      );
      expect(at('package_shipped')).toEqual(
        packages.map(pkg => pkg.shippedAt).sort((a, b) => a - b),
      );
      expect(at('package_signed')).toEqual(
        packages
          .flatMap(pkg => (pkg.signedAt === null ? [] : [pkg.signedAt]))
          .sort((a, b) => a - b),
      );
      for (const event of sorted) {
        expect(event.ownerId).toBe(order.ownerId);
        expect(event.createTime).toBeLessThanOrEqual(now);
      }
    }
  });

  describe('rule 7: distribution guard rails', () => {
    it('the top 20% of buyers bring 60%–72% of GMV', () => {
      const gmv = new Map<string, number>();
      for (const { ownerId, state } of orders) {
        gmv.set(
          ownerId,
          (gmv.get(ownerId) ?? 0) + cents(state.amounts.payableAmount),
        );
      }
      const perBuyer = [...gmv.values()].sort((a, b) => b - a);
      const top = perBuyer.slice(0, Math.ceil(perBuyer.length * 0.2));
      const share = sum(top) / sum(perBuyer);
      expect(share).toBeGreaterThanOrEqual(0.6);
      expect(share).toBeLessThanOrEqual(0.72);
    });

    it('every Double 11 day brings at least 5× the median daily GMV of the 30 days before it', () => {
      const daily = new Map<number, number>();
      for (const { firstEventTime, state } of orders) {
        const day = shanghaiDayStart(firstEventTime);
        daily.set(
          day,
          (daily.get(day) ?? 0) + cents(state.amounts.payableAmount),
        );
      }
      const double11s = [2024, 2025].map(year => shanghai(`${year}-11-11`));
      for (const day of double11s) {
        const before = Array.from(
          { length: 30 },
          (_, index) => daily.get(day - (index + 1) * DAY) ?? 0,
        );
        expect(daily.get(day)! / median(before)).toBeGreaterThanOrEqual(5);
      }
    });

    it('refunds come to 5%–8% of the amount paid', () => {
      const paid = sum(orders.map(o => cents(o.state.amounts.paidAmount)));
      const refunded = sum(
        orders.map(o => cents(o.state.amounts.refundedAmount)),
      );
      expect(refunded / paid).toBeGreaterThanOrEqual(0.05);
      expect(refunded / paid).toBeLessThanOrEqual(0.08);
    });
  });
});

describe('generateRetail: planted anomalies (2.6)', () => {
  it('A1: the bamboo bath towel 70×140 is refunded at about 4% before 2026-05-10 and about 28% after, mostly for quality', () => {
    const { a1 } = ANOMALIES;
    const refundRate = (from: number, to: number) => {
      let paid = 0;
      let refunded = 0;
      for (const order of placedBetween(from, to)) {
        if (order.state.status === 'CANCELLED') continue;
        for (const line of order.state.items) {
          if (line.skuId !== a1.skuId) continue;
          paid += cents(line.payAmount);
          refunded += cents(line.refundedAmount);
        }
      }
      return refunded / paid;
    };
    // 下单后要过一两周售后才办完，最近两周不算。
    const before = refundRate(RETAIL_DEFAULTS.from, a1.from);
    const after = refundRate(a1.from, now - 14 * DAY);
    expect(before).toBeLessThan(0.08);
    expect(after).toBeGreaterThan(0.2);
    expect(after).toBeLessThan(0.36);
    const reasons = data.afterSales.filter(
      ({ state }) =>
        state.skuId === a1.skuId &&
        state.requestedAt >= a1.from &&
        state.type === 'RETURN_REFUND',
    );
    const quality = reasons.filter(
      ({ state }) => state.reason === 'QUALITY_ISSUE',
    );
    expect(quality.length / reasons.length).toBeGreaterThan(0.6);
  });

  it('A2: ZTO takes about 120 hours to deliver in Guangdong and Guangxi during the typhoon, against about 52 otherwise', () => {
    const { a2 } = ANOMALIES;
    const hoursToSign = (typhoon: boolean) =>
      median(
        data.waybills
          .filter(
            ({ state }) =>
              state.carrier === a2.carrier &&
              a2.provinces.includes(state.province) &&
              state.shipToSignHours !== null &&
              (state.shippedAt >= a2.from && state.shippedAt < a2.to) ===
                typhoon,
          )
          .map(({ state }) => state.shipToSignHours!),
      );
    const typhoon = hoursToSign(true);
    const usual = hoursToSign(false);
    expect(typhoon).toBeGreaterThanOrEqual(90);
    expect(usual).toBeLessThan(65);
  });

  it('A3: on 2026-03-08 live-stream coupons stack to about 45% off, new buyers come in 4×, and 30% cancel or refund within 7 days', () => {
    const { a3 } = ANOMALIES;
    const live = (order: RetailSnapshot<RetailOrderState>) =>
      order.state.channel === a3.channel;
    const day = placedBetween(a3.from, a3.to, live);
    const list = sum(day.map(o => cents(o.state.amounts.listAmount)));
    const goods = sum(
      day.map(
        o =>
          cents(o.state.amounts.payableAmount) - cents(o.state.amounts.freight),
      ),
    );
    expect((list - goods) / list).toBeGreaterThan(0.4);

    const newBuyers = (from: number) =>
      placedBetween(from, from + DAY, o => live(o) && o.state.buyer.isNewBuyer)
        .length;
    const usual = median(
      Array.from({ length: 30 }, (_, i) => newBuyers(a3.from - (i + 1) * DAY)),
    );
    expect(newBuyers(a3.from)).toBeGreaterThanOrEqual(4 * Math.max(1, usual));

    const requested = new Map<string, number>();
    for (const { state } of data.afterSales) {
      const earliest = requested.get(state.orderNo);
      if (earliest === undefined || state.requestedAt < earliest)
        requested.set(state.orderNo, state.requestedAt);
    }
    const paid = day.filter(o => o.state.payment.paidAt !== null);
    const lost = paid.filter(o => {
      const limit = o.firstEventTime + 7 * DAY;
      const cancelled = o.state.timing.cancelledAt;
      const request = requested.get(o.aggregateId);
      return (
        (cancelled !== null && cancelled <= limit) ||
        (request !== undefined && request <= limit)
      );
    });
    expect(lost.length / paid.length).toBeGreaterThanOrEqual(0.2);
  });

  it('A4: at 00:20–01:10 on Double 11 2025 UnionPay fails and payment timeouts shoot up', () => {
    const { a4 } = ANOMALIES;
    const timedOut = (list: RetailSnapshot<RetailOrderState>[]) =>
      list.filter(o => o.state.cancelReason === 'PAYMENT_TIMEOUT').length /
      list.length;
    const unionPay = (o: RetailSnapshot<RetailOrderState>) =>
      o.state.payment.method === a4.method;
    const window = placedBetween(a4.from, a4.to);
    const rest = [
      ...placedBetween(shanghai('2025-11-11'), a4.from),
      ...placedBetween(a4.to, shanghai('2025-11-12')),
    ];
    expect(window.filter(unionPay).length).toBeGreaterThanOrEqual(5);
    expect(timedOut(window.filter(unionPay))).toBeGreaterThan(0.6);
    expect(timedOut(rest.filter(unionPay))).toBeLessThan(0.3);
    expect(timedOut(window)).toBeGreaterThan(2 * timedOut(rest));
  });

  it('A5: 0.4% of orders have no city, all from the old mini program', () => {
    const missing = orders.filter(o => o.state.address.city === null);
    const share = missing.length / orders.length;
    expect(share).toBeGreaterThan(0.002);
    expect(share).toBeLessThan(0.007);
    for (const { state } of missing) {
      expect(state.channel).toBe(ANOMALIES.a5.channel);
      expect(state.address.cityTier).toBeNull();
      expect(state.address.province).not.toBe('');
    }
  });

  it('A6: across the Spring Festival stop only about 60% ship on time, against over 90% the month before', () => {
    const { a6 } = ANOMALIES;
    const paidIn = (from: number, to: number) =>
      orders
        .map(o => o.state)
        .filter(
          s =>
            s.timing.paidAt !== null &&
            s.timing.paidAt >= from &&
            s.timing.paidAt < to,
        );
    const festival = onTimeShare(paidIn(a6.from, a6.to));
    expect(festival).toBeGreaterThan(0.45);
    expect(festival).toBeLessThan(0.72);
    expect(
      onTimeShare(paidIn(a6.from - 31 * DAY, a6.from - DAY)),
    ).toBeGreaterThan(0.9);
  });

  it('A7: the East China sorting line fault drops on-time shipping for orders due on 09-21 below the 95% target, and leaves orders paid over 48 hours ago unshipped', () => {
    const { a7 } = ANOMALIES;
    const dueBetween = (start: number, end: number) =>
      orders
        .map(o => o.state)
        .filter(
          s =>
            s.timing.shipDueAt !== null &&
            s.timing.shipDueAt >= start &&
            s.timing.shipDueAt < end,
        );
    const dueOn = (date: string) =>
      dueBetween(shanghai(date), shanghai(date) + DAY);
    // 「09-21 的发货及时率」：发货期限（付款 + 48 小时）落在 09-21 的单。
    const incident = onTimeShare(dueOn('2026-09-21'));
    expect(incident).toBeGreaterThan(0.6);
    expect(incident).toBeLessThan(0.9);
    // 之前的十天照常：一天只有三十来张单，合起来看。
    const usual = onTimeShare(
      dueBetween(shanghai('2026-09-08'), shanghai('2026-09-18')),
    );
    expect(usual).toBeGreaterThanOrEqual(0.93);
    const stuck = orders.filter(
      ({ state }) =>
        state.status === 'PAID' &&
        state.timing.paidAt! < now - SHIP_SLA_HOURS * HOUR,
    );
    // 华东仓每天约 10 张子单，故障前一天付款、卡在线上的只有这么多。
    expect(stuck.length).toBeGreaterThanOrEqual(5);
    for (const { state } of stuck) {
      expect(state.warehouse).toBe(a7.warehouse);
      expect(state.shipSlaBreached).toBe(true);
    }
  });
});

describe('generateRetail: determinism, scale and speed', () => {
  it('gives byte-identical output for the same seed, and different output for another', () => {
    const again = JSON.stringify(generateRetail());
    expect(again).toBe(JSON.stringify(data));
    const other = generateRetail({ seed: RETAIL_DEFAULTS.seed + 1 });
    expect(other.orders[0].state.orderNo).toBeDefined();
    expect(JSON.stringify(other.orders)).not.toBe(JSON.stringify(orders));
  });

  it('covers 2024-09-01 up to the pinned now with about 20 thousand sub-orders and 7 thousand members', () => {
    expect(orders.length).toBeGreaterThan(18_000);
    expect(orders.length).toBeLessThan(24_000);
    expect(data.members.length).toBeGreaterThan(5_500);
    expect(data.members.length).toBeLessThan(8_500);
    expect(orders[0].firstEventTime).toBeGreaterThanOrEqual(
      RETAIL_DEFAULTS.from,
    );
    expect(orders.at(-1)!.firstEventTime).toBeLessThan(now);
    const times = orders.map(o => o.firstEventTime);
    expect(times).toEqual([...times].sort((a, b) => a - b));
    // 地域参考数据里的每个省都有人买。
    const provinces = new Set(orders.map(o => o.state.address.province));
    expect(provinces.size).toBe(PROVINCES.length);
  });

  it('makes about 5× as many sub-orders at the large scale', () => {
    const large = generateRetail({ scale: 'large' });
    const ratio = large.orders.length / orders.length;
    expect(ratio).toBeGreaterThan(4);
    expect(ratio).toBeLessThan(6);
  });

  it('generates the showcase scale within 150 ms', () => {
    // 五次里最快的一次是生成本身的开销；其余几次带着机器上别的负载（CI 的
    // 机器是共享的），只记下来，不拿来判。浏览器里的实测在第 3 批。
    const times: number[] = [];
    for (let round = 0; round < 5; round += 1) {
      const start = performance.now();
      generateRetail();
      times.push(performance.now() - start);
    }
    console.info(
      `generateRetail(showcase): ${times.map(t => t.toFixed(1)).join(', ')} ms`,
    );
    // 预算是给浏览器定的（第 3 批在 Chromium 里实测）。CI 的机器与故事的
    // Chromium 同时跑，节点里的这个代理数在那里只记不判；本机照判。
    if (!process.env.CI) expect(Math.min(...times)).toBeLessThanOrEqual(150);
  });
});
