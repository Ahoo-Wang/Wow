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
 * 零售仪表盘的黄金值（docs/scenarios.md 6.1）：默认种子下，2026-09-22 10:00
 * 打开时各块板读出的数。它们由种子决定；生成器一改，就在同一个 PR 里更新
 * 这里。生成器自己的正确性由 `generate.test.ts` 守着，不靠这些数。
 *
 * 09-21 的单量在近期护栏（2.4 规则 8）之下重抽过：原来 19 张子单、GMV 较前一
 * 日 −61.8%，把 A7 埋掉了；现在是一个平常的周一。
 *
 * `goldens.test.ts` 在 node 里按同一口径从数据集直接算一遍，与这里比——所以
 * 这些数不是从屏幕上抄下来的，是两条路算出来一致的。
 * ------------------------------------------------------------------------ */

/** 运营日报读的那一天：昨日。 */
export const REPORT_DAY = '2026-09-21';

/** 运营日报八张卡读出的数（`DAILY_CARDS` 的标题 → 屏幕上的读法）。 */
export const DAILY_GOLDEN = {
  cards: {
    GMV: '¥6,052.93',
    实付金额: '¥5,671.54',
    '订单数（单）': '33',
    '新客数（人）': '12',
    '客单价 · 昨日较近 30 天': '¥183.42',
    '支付转化率 · 昨日较近 30 天': '93.9%',
    售后退款: '¥100.71',
    '发货及时率 · 昨日较近 30 天': '81.6%',
  },
  /** 带走势的卡：9 月 21 日较 9 月 20 日的变化（百分比那一半）。 */
  changes: {
    GMV: '-18.6%',
    实付金额: '-14.8%',
    '订单数（单）': '0%',
    '新客数（人）': '+20%',
    售后退款: '+31.1%',
  },
  /** 发货及时率的目标读法：81.6%，目标 95%（A7）。 */
  onTime: { value: '81.6%', target: '95%' },
} as const;

/**
 * 付款超过 48 小时仍未发货的 11 张单（A7），最早付款的在前——都在华东（嘉兴）
 * 仓。
 */
export const OVERDUE_ORDERS = [
  'TO2026091700021',
  'TO2026091800019',
  'TO2026091900005',
  'TO2026091900014',
  'TO2026091900032',
  'TO2026091900033',
  'TO2026091900037',
  'TO2026091900040',
  'TO2026091900039',
  'TO2026092000002',
  'TO2026092000004',
] as const;

/** 其中直播间的三张：日报上点「直播间」那根柱，超时明细只剩它们。 */
export const OVERDUE_LIVE_ORDERS = [
  'TO2026091900032',
  'TO2026091900037',
  'TO2026091900040',
] as const;
