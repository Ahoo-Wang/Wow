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
 * `goldens.test.ts` 在 node 里按同一口径从数据集直接算一遍，与这里比——所以
 * 这些数不是从屏幕上抄下来的，是两条路算出来一致的。
 * ------------------------------------------------------------------------ */

/** 运营日报读的那一天：昨日。 */
export const REPORT_DAY = '2026-09-21';

/** 运营日报八张卡读出的数（`DAILY_CARDS` 的标题 → 屏幕上的读法）。 */
export const DAILY_GOLDEN = {
  cards: {
    GMV: '¥2,843.07',
    实付金额: '¥2,448.77',
    '订单数（单）': '19',
    '新客数（人）': '6',
    '客单价 · 昨日较近 30 天': '¥149.64',
    '支付转化率 · 昨日较近 30 天': '94.7%',
    售后退款: '¥100.71',
    '发货及时率 · 昨日较近 30 天': '81.6%',
  },
  /** 带走势的卡：9 月 21 日较 9 月 20 日的变化（百分比那一半）。 */
  changes: {
    GMV: '-61.8%',
    实付金额: '-63.2%',
    '订单数（单）': '-42.4%',
    '新客数（人）': '-40%',
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
