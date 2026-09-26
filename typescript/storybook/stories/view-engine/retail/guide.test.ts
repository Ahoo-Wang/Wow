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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ANOMALIES } from './generate.js';
import { OVERDUE_ORDERS } from './goldens.js';
import { guideAnswers, monthDay, percent, about } from './guide.js';
import { BATH_TOWEL_TITLE } from './views.js';

/*
 * The guide (`Intro.mdx`) reads its numbers and names from `guide.ts`, so
 * they follow the data. What it says around them — which product, which
 * carrier, which day — is a claim about the data; these hold it to that.
 */
const answers = await guideAnswers();

describe('the guide’s answers (Intro.mdx)', () => {
  it('A1: the bath towel’s refund rate stands far above every other product', () => {
    const { a1 } = answers;
    expect(a1.title).toBe(BATH_TOWEL_TITLE);
    expect(a1.rate).toBeGreaterThan(2 * a1.othersAtMost);
  });

  it('A2: in 广东, 中通 slowed the most against its own usual week, in the typhoon', () => {
    const { a2 } = answers;
    expect(a2.carrier).toBe('中通快递');
    expect(a2.jump).toBeGreaterThan(a2.nextJump);
    // The week (Monday) the slowest reading starts in overlaps the typhoon.
    expect(a2.week).toBeLessThan(ANOMALIES.a2.to);
    expect(a2.week + 7 * 86_400_000).toBeGreaterThan(ANOMALIES.a2.from);
  });

  it('A3: the stacked-coupon day is the rightmost point, well apart', () => {
    const { a3 } = answers;
    expect(monthDay(a3.day)).toBe(monthDay(ANOMALIES.a3.from));
    expect(a3.discountRate - a3.othersAtMost).toBeGreaterThan(0.1);
  });

  it('A4: every UnionPay order of Double 11’s first hours timed out', () => {
    expect(answers.a4.method).toBe('云闪付');
    expect(answers.a4.timeoutRate).toBe(1);
  });

  it('A5: the orders with no city come from the mini program alone', () => {
    const { a5 } = answers;
    expect(a5.count).toBeGreaterThan(0);
    expect(a5.channels).toEqual(['微信小程序']);
  });

  it('A6: the Spring Festival week tops the year, over the red line, and clears the next', () => {
    const { a6 } = answers;
    expect(a6.week).toBeGreaterThanOrEqual(ANOMALIES.a6.from - 7 * 86_400_000);
    expect(a6.week).toBeLessThan(ANOMALIES.a6.to);
    expect(a6.lateRate).toBeGreaterThan(a6.redLine);
    expect(a6.backWeek).toBeGreaterThan(a6.week);
  });

  it('A7: every overdue order sits in the one warehouse', () => {
    const { a7 } = answers;
    expect(a7.warehouses).toEqual(['华东（嘉兴）']);
    expect(a7.overdue).toBe(OVERDUE_ORDERS.length);
  });
});

describe('the data note every retail scene opens with', () => {
  it('says the sizes the data set has', () => {
    // `RETAIL_DATA_NOTE` is a docs description, a string written before the
    // data exists; this holds it to the data, as the guide's own line is.
    const note = readFileSync(new URL('./scene.tsx', import.meta.url), 'utf8');
    const { sizes } = answers;
    for (const phrase of [
      `约 ${about(sizes.orders, '张')}子订单`,
      `${about(sizes.afterSales, '张')}售后单`,
      `${about(sizes.members, '个')}会员`,
      `${about(sizes.waybills, '个')}包裹`,
    ])
      expect(note).toContain(phrase);
  });
});

describe('how the guide says a number', () => {
  it('keeps a share to a whole percent, and one decimal under 1%', () => {
    expect(percent(0.2601)).toBe('26%');
    expect(percent(0.00432)).toBe('0.4%');
    expect(percent(1)).toBe('100%');
  });

  it('rounds a count to what a reader keeps in mind', () => {
    expect(about(20_375, '张')).toBe('2 万张');
    expect(about(18_976, '个')).toBe('1.9 万个');
    expect(about(1_727, '张')).toBe('1700 张');
    expect(about(6_705, '个')).toBe('6700 个');
  });
});
