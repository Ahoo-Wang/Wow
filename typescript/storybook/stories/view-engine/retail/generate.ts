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
 * 栖木生活的零售交易数据：一个确定、有种子的纯函数（docs/scenarios.md 第 2 节）。
 *
 * `generateRetail` 按天走过 `from`～`to`：每天的主单数服从泊松分布，均值是
 * 基线 × 趋势 × 月季节 × 星期 × 活动；每张主单挑买家、渠道、商品行、优惠与
 * 付款，按仓库拆成子单，再给每张子单排出完整的生命周期——付款、取消、发货、
 * 签收、拒收、售后、完成或关闭。最后把生命周期截在「现在」：还没发生的事不进
 * 快照，也不进事件流。
 *
 * - 随机源只有两个，都由 `seed` 播种：订单层的每一次抽样走 d3-random 的
 *   `randomLcg`（泊松、对数正态、帕累托也用它作源），买家层（约 7000 人）的
 *   姓名、等级与地址走 faker 的 `zh_CN`。同一个种子得到逐字节相同的输出。
 * - 金额先按分取整数算，最后除以 100，不留浮点尾巴。
 * - 七处异常 A1～A7 都是 `ANOMALIES` 里的参数，不是事后改数据。
 * - 一致性规则（第 2.4 节）由 `generate.test.ts` 逐条断言。
 * ------------------------------------------------------------------------ */

import { base, Faker, zh_CN } from '@faker-js/faker';
import {
  randomLcg,
  randomLogNormal,
  randomPareto,
  randomPoisson,
} from 'd3-random';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import {
  ACTIVITIES,
  AFTER_SALE_RATES,
  AFTER_SALE_REASON_WEIGHTS,
  APPLIANCE_WAREHOUSE,
  BATH_TOWEL_SKU_ID,
  CARRIERS,
  CATEGORIES,
  CHANNEL_MIX,
  CHANNEL_RETURN_FACTOR,
  COUPONS,
  DOUBLE_11_MIDNIGHT_SHARE,
  FREE_SHIPPING_THRESHOLD,
  FREIGHTS,
  FULL_REDUCTION,
  HOUR_WEIGHTS,
  INSTALLMENT_THRESHOLD,
  INSTALLMENTS,
  MEMBER_LEVELS,
  MONTH_FACTORS,
  PAYMENT_WEIGHTS,
  PROVINCES,
  REMARK_SIGNATURES,
  REMARKS,
  REMOTE_EXTRA_HOURS,
  SHOPS,
  SKU_POPULARITY,
  SKUS,
  SPRING_FESTIVAL_FACTOR,
  SPRING_FESTIVALS,
  WAREHOUSES,
  WEEKDAY_FACTORS,
  type Activity,
  type AfterSaleReason,
  type AfterSaleType,
  type Brand,
  type CancelReason,
  type Category1,
  type Channel,
  type CarrierId,
  type City,
  type CityTier,
  type InvoiceType,
  type MemberLevel,
  type OrderStatus,
  type OrderTag,
  type PaymentMethod,
  type PriceBand,
  type Province,
  type ShopId,
  type Sku,
  type WarehouseId,
} from './catalog.js';

dayjs.extend(utc);

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const SHANGHAI_OFFSET = 8 * HOUR;

/** 纪元毫秒：上海时间的某一刻，如 `shanghai('2026-05-10')`、`shanghai('2025-11-11', '00:20')`。 */
export function shanghai(date: string, time = '00:00'): number {
  return Date.parse(`${date}T${time}:00+08:00`);
}

/** 这一刻所在的上海日历日的零点。 */
export function shanghaiDayStart(time: number): number {
  return time - ((((time + SHANGHAI_OFFSET) % DAY) + DAY) % DAY);
}

// ---------------------------------------------------------------- 参数

export type RetailScale = 'showcase' | 'large';

export interface RetailOptions {
  readonly seed: number;
  /** 第一天的零点（纪元毫秒，上海时间）。 */
  readonly from: number;
  /** 最后一张单的下单时刻不晚于它（不含）。 */
  readonly to: number;
  /** 钉住的「现在」：生命周期截在这一刻。 */
  readonly now: number;
  /** `showcase` 约 2 万张子单；`large` 约 10 万张，只给「大数据」的能力故事用。 */
  readonly scale: RetailScale;
}

/** 与首页的 `HOME_FIXTURE_NOW` 同一刻：2026-09-22 10:00 Asia/Shanghai。 */
export const RETAIL_NOW = shanghai('2026-09-22', '10:00');

export const RETAIL_DEFAULTS: RetailOptions = {
  seed: 20260922,
  from: shanghai('2024-09-01'),
  to: RETAIL_NOW,
  now: RETAIL_NOW,
  scale: 'showcase',
};

const SCALE_FACTORS: Record<RetailScale, number> = {
  showcase: 1,
  large: 4.8,
};

/** 事件流只生成最近这么多天下单的子单（事件流分析台看的本来就是近期）。 */
export const EVENT_WINDOW_DAYS = 90;

/** 付款后这么多小时仍未发货就算超时。 */
export const SHIP_SLA_HOURS = 48;

/** 日单量：2024-09 每天 18 张子单，每年增长 35%。 */
const VOLUME = {
  baseline: 18,
  origin: shanghai('2024-09-01'),
  growthPerYear: 0.35,
  /** 一张主单平均拆成这么多张子单，用来把子单的基线换成主单的。 */
  subOrdersPerParent: 1.1,
} as const;

/** 商品行数 1/2/3/4/5 的份额（%）：4+ 占 7%。大促囤货，行数更多，客单价更高。 */
const LINE_COUNT_WEIGHTS = {
  regular: [58, 25, 10, 5, 2],
  promo: [45, 28, 14, 8, 5],
} as const;

/** 每行件数 1/2/3/4 的份额（%），按单价分三档：便宜的东西一次买几件。 */
const QTY_WEIGHTS = {
  under50: [45, 33, 14, 8],
  under100: [72, 20, 8],
  other: [90, 9, 1],
} as const;

/** 下单到完成这一路的漏斗（第 2.5 节「漏斗」）。 */
const FUNNEL = {
  unpaid: 0.08,
  unpaidDouble11Midnight: 0.15,
  paymentTimeoutMinutes: 30,
  buyerCancel: 0.02,
  riskCancel: 0.0015,
  stockCancel: 0.0015,
  addressChanged: 0.03,
  rejection: 0.01,
  lost: 0.005,
  auditRejected: 0.05,
  completeAfterSignDays: 7,
} as const;

/** 优惠：大促期间约占原价 22%，平日约 8%。 */
const DISCOUNTS = {
  promo: {
    itemRate: 0.07,
    itemSpread: 0.06,
    shopCoupon: 0.45,
    platformCoupon: 0.35,
  },
  regular: {
    itemRate: 0.025,
    itemSpread: 0.03,
    shopCoupon: 0.18,
    platformCoupon: 0.06,
  },
  points: 0.06,
  pointsRate: 0.05,
  pointsCap: 20,
  /** 订单级优惠合计不超过商品金额的这一比例。 */
  cap: 0.6,
} as const;

/** 买家（第 2.5 节「买家」）：新客份额，与回购强度的帕累托形状。 */
const BUYERS = {
  newShareRegular: 0.33,
  newSharePromo: 0.48,
  paretoAlpha: 1.4,
  /** 分销渠道拉来的买家落在三线及以下城市的权重倍数。 */
  distributionTier3Boost: 2.2,
} as const;

/** 履约：付款到发货的小时数，中位数 14、95 分位约 40；双 11 积压。 */
const FULFILMENT = {
  payToShipMedianHours: 14,
  payToShipSigma: 0.64,
  signSigma: 0.35,
  splitPackage: 0.1,
  splitDelayHours: [12, 60] as const,
  /** 当天没货、从另一个仓调货的商品行的概率。 */
  transferLine: 0.1,
  double11Backlog: {
    days: 2,
    share: 0.45,
    medianHours: 60,
    sigma: 0.3,
  },
} as const;

/**
 * 埋下的七处异常（第 2.6 节）。都是生成参数：改一个数，异常就跟着变，
 * 分布也不会走样。
 */
export const ANOMALIES = {
  /** 竹纤维浴巾 70×140 的退款率从约 4% 升到 28%，理由集中在「质量问题」。 */
  a1: {
    skuId: BATH_TOWEL_SKU_ID,
    from: shanghai('2026-05-10'),
    /** 在此之前，这个 SKU 的售后倍率（浴巾贴身用，平时很少退）。 */
    baseReturnFactor: 0.4,
    returnRate: 0.3,
    qualityShare: 0.8,
  },
  /** 台风：中通在两广的签收时长中位数涨到 120 小时。 */
  a2: {
    carrier: 'ZTO' as CarrierId,
    provinces: ['广东省', '广西壮族自治区'] as readonly string[],
    from: shanghai('2026-07-18'),
    to: shanghai('2026-07-29'),
    signHours: 120,
    sigma: 0.2,
  },
  /** 直播渠道的券可以叠加：优惠占比约 45%、新客 ×4，之后 7 天取消加退款达 30%。 */
  a3: {
    channel: 'LIVE' as Channel,
    from: shanghai('2026-03-08'),
    to: shanghai('2026-03-09'),
    /** 叠加券按商品金额的这一比例减。 */
    stackRate: 0.3,
    /** 那一天直播渠道的份额倍数与直播间的新客份额。 */
    channelBoost: 1.6,
    newShare: 0.6,
    cancelRate: 0.15,
    refundRate: 0.25,
  },
  /**
   * 云闪付支付失败，这一时段付款超时猛增。双 11 零点云闪付有立减，这一段选它
   * 付款的人比平时多得多（`share`），偏偏它挂了。
   */
  a4: {
    method: 'UNIONPAY' as PaymentMethod,
    from: shanghai('2025-11-11', '00:20'),
    to: shanghai('2025-11-11', '01:10'),
    share: 0.35,
    failureRate: 0.85,
  },
  /** 旧版小程序没有上报城市：全部子单的 0.4%，城市为空。 */
  a5: {
    channel: 'MINI_PROGRAM' as Channel,
    share: 0.004,
  },
  /** 春节停运：仓库停发，节后积压按先后顺序一周才消化。 */
  a6: {
    from: shanghai('2026-02-10'),
    to: shanghai('2026-02-25'),
    closedFrom: shanghai('2026-02-15'),
    closedTo: shanghai('2026-02-20'),
    drainDays: 5,
  },
  /**
   * 华东（嘉兴）仓分拣线故障：09-20 零点起，已上线分拣的包裹卡在线上，要等
   * 人工重新分拣（排在「现在」之后），所以到 09-22 10:00 仍未发出；09-21
   * 傍晚分拣线修好，之后付款的单照常发。
   */
  a7: {
    warehouse: 'EAST' as WarehouseId,
    from: shanghai('2026-09-20'),
    to: shanghai('2026-09-21', '18:00'),
    resortFrom: shanghai('2026-09-22', '14:00'),
    resortHours: 30,
  },
} as const;

// ---------------------------------------------------------------- 输出的形状

/** Wow 快照的信封；时间都是纪元毫秒。 */
export type RetailSnapshot<S> = {
  contextName: string;
  aggregateName: string;
  aggregateId: string;
  tenantId: string;
  ownerId: string;
  spaceId: string;
  version: number;
  firstEventTime: number;
  eventTime: number;
  deleted: boolean;
  state: S;
};

export type RetailOrderLine = {
  lineId: string;
  skuId: string;
  spuId: string;
  title: string;
  category1: Category1;
  category2: string;
  brand: Brand;
  priceBand: PriceBand;
  qty: number;
  listPrice: number;
  salePrice: number;
  /** 订单级优惠（满减、两种券、积分）分摊到这一行的金额。 */
  discountShare: number;
  payAmount: number;
  refundedQty: number;
  refundedAmount: number;
};

export type RetailAmounts = {
  listAmount: number;
  itemDiscount: number;
  fullReduction: number;
  shopCoupon: number;
  platformCoupon: number;
  pointsDeduct: number;
  freight: number;
  payableAmount: number;
  paidAmount: number;
  refundedAmount: number;
};

export type RetailPackage = {
  packageNo: string;
  carrier: CarrierId;
  waybillNo: string;
  shippedAt: number;
  signedAt: number | null;
  lineIds: string[];
};

export type RetailAddress = {
  province: string;
  /** A5：旧版小程序没有上报城市时为 `null`，区县与城市等级随之为空。 */
  city: string | null;
  cityTier: CityTier | null;
  district: string | null;
  recipient: string;
};

export type RetailOrderState = {
  orderNo: string;
  parentOrderNo: string;
  shopId: ShopId;
  channel: Channel;
  warehouse: WarehouseId;
  buyer: {
    id: string;
    nick: string;
    level: MemberLevel;
    isNewBuyer: boolean;
  };
  status: OrderStatus;
  afterSaleStatus: 'NONE' | 'IN_PROGRESS' | 'FINISHED';
  cancelReason: CancelReason | null;
  items: RetailOrderLine[];
  amounts: RetailAmounts;
  payment: {
    method: PaymentMethod;
    installments: number;
    paidAt: number | null;
  };
  promotion: {
    activityId: string | null;
    coupons: string[];
  };
  address: RetailAddress;
  packages: RetailPackage[];
  timing: {
    paidAt: number | null;
    shippedAt: number | null;
    signedAt: number | null;
    completedAt: number | null;
    cancelledAt: number | null;
    closedAt: number | null;
    /** 读模型派生：付款后 48 小时的发货期限。 */
    shipDueAt: number | null;
  };
  /*
   * 读模型派生的字段。Wow 聚合不能按「星期几」「几点」分组，也不能对两个时刻
   * 相减；真实系统由投影在写读模型时算好（wow-bi 的宽表也是这样），这里照做。
   */
  /** 下单的星期，1 是周一，7 是周日。 */
  placedWeekday: number;
  placedHour: number;
  payToShipHours: number | null;
  shipToSignHours: number | null;
  /** 付款后 48 小时仍未发出（到「现在」为止）。 */
  shipSlaBreached: boolean;
  invoice: { type: InvoiceType };
  tags: OrderTag[];
  remark: string | null;
};

export type RetailAfterSaleState = {
  afterSaleNo: string;
  orderNo: string;
  parentOrderNo: string;
  lineId: string;
  skuId: string;
  title: string;
  category1: Category1;
  channel: Channel;
  shopId: ShopId;
  buyerId: string;
  type: AfterSaleType;
  reason: AfterSaleReason;
  status: 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'REFUNDED' | 'EXCHANGED';
  qty: number;
  requestedAmount: number;
  refundedAmount: number;
  requestedAt: number;
  auditedAt: number | null;
  refundedAt: number | null;
};

export type RetailMemberState = {
  id: string;
  nick: string;
  level: MemberLevel;
  registerChannel: Channel;
  registeredAt: number;
  province: string;
  city: string;
  cityTier: CityTier;
  district: string;
  /** 由订单算出：第一张已付款子单的付款时刻、已付款的主单数、累计实付。 */
  firstOrderAt: number | null;
  orderCount: number;
  totalPaid: number;
};

export type RetailWaybillState = {
  waybillNo: string;
  packageNo: string;
  orderNo: string;
  parentOrderNo: string;
  buyerId: string;
  carrier: CarrierId;
  warehouse: WarehouseId;
  province: string;
  city: string | null;
  cityTier: CityTier | null;
  remote: boolean;
  status: 'IN_TRANSIT' | 'SIGNED' | 'REJECTED' | 'LOST';
  lineCount: number;
  itemQty: number;
  weightKg: number;
  shippedAt: number;
  signedAt: number | null;
  shipToSignHours: number | null;
};

export type RetailDomainEvent = {
  id: string;
  name: string;
  revision: string;
  bodyType: string;
  body: Record<string, unknown>;
};

/** 一条事件流：一次命令追加的事件，版本从 1 起连续。 */
export type RetailEventStream = {
  id: string;
  contextName: string;
  aggregateName: string;
  aggregateId: string;
  tenantId: string;
  ownerId: string;
  spaceId: string;
  commandId: string;
  requestId: string;
  version: number;
  header: Record<string, string>;
  body: RetailDomainEvent[];
  createTime: number;
};

export interface RetailDataset {
  readonly options: RetailOptions;
  /** 子订单，按下单时刻排序。 */
  readonly orders: RetailSnapshot<RetailOrderState>[];
  readonly afterSales: RetailSnapshot<RetailAfterSaleState>[];
  readonly members: RetailSnapshot<RetailMemberState>[];
  /** 包裹：子单 `packages` 的展开，一包一行。 */
  readonly waybills: RetailSnapshot<RetailWaybillState>[];
  /** 最近 `EVENT_WINDOW_DAYS` 天下单的子单的事件流，按时刻排序。 */
  readonly events: RetailEventStream[];
}

const CONTEXT = 'qimu-retail';
const TENANT = 'qimu';
const LINE_IDS = ['L1', 'L2', 'L3', 'L4', 'L5'] as const;
const WAREHOUSE_IDS: readonly WarehouseId[] = WAREHOUSES.map(w => w.id);
/** 缺货时从哪几个仓调货：除了就近的那一个。 */
const ALTERNATE_WAREHOUSES = new Map(
  WAREHOUSE_IDS.map(id => [id, WAREHOUSE_IDS.filter(other => other !== id)]),
);
const EVENT_TYPE_PREFIX = 'me.ahoo.qimu.retail';

// ---------------------------------------------------------------- 抽样工具

type Random = () => number;

/** 按权重抽一个：累积权重上二分，只花一次均匀随机数。 */
function weighted<T>(
  random: Random,
  items: readonly T[],
  weights: readonly number[],
): () => T {
  const cumulative = new Float64Array(weights.length);
  let total = 0;
  weights.forEach((weight, index) => {
    total += weight;
    cumulative[index] = total;
  });
  return () => items[search(cumulative, random() * total)];
}

/** 第一个累积值大于 `target` 的下标。 */
function search(cumulative: ArrayLike<number>, target: number): number {
  let low = 0;
  let high = cumulative.length - 1;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (cumulative[middle] > target) high = middle;
    else low = middle + 1;
  }
  return low;
}

/** 把 `total` 分按权重分到各份，取整后差额给余数最大的几份，合计严格相等。 */
function allocate(total: number, weights: readonly number[]): number[] {
  if (weights.length === 1) return [total];
  let sum = 0;
  for (const weight of weights) sum += weight;
  if (total === 0 || sum === 0) return weights.map(() => 0);
  const shares: number[] = [];
  const fractions: number[] = [];
  let rest = total;
  for (const weight of weights) {
    const exact = (total * weight) / sum;
    const share = Math.floor(exact);
    shares.push(share);
    fractions.push(exact - share);
    rest -= share;
  }
  // 差额（不超过份数）逐个给余数最大的一份；余数相同取靠前的。
  while (rest > 0) {
    let best = 0;
    for (let i = 1; i < fractions.length; i++) {
      if (fractions[i] > fractions[best]) best = i;
    }
    shares[best] += 1;
    fractions[best] = -1;
    rest -= 1;
  }
  return shares;
}

const yuan = (cents: number): number => cents / 100;
const hours = (ms: number): number => Math.round((ms / HOUR) * 10) / 10;
const pad = (value: number, length: number): string =>
  String(value).padStart(length, '0');

// ---------------------------------------------------------------- 内部事实

interface MemberFacts {
  readonly index: number;
  readonly id: string;
  readonly nick: string;
  readonly level: MemberLevel;
  readonly registerChannel: Channel;
  readonly registeredAt: number;
  readonly province: Province;
  readonly city: City;
  readonly district: string;
}

/** 会员的累计值，边生成订单边累加。 */
interface MemberTally {
  first: RetailOrderState | null;
  firstPaidAt: number;
  lastPaidAt: number;
  lastPaidParent: string;
  paidParents: number;
  paidSubOrders: number;
  totalPaid: number;
}

interface LineFacts {
  readonly index: number;
  readonly lineId: string;
  readonly sku: Sku;
  readonly qty: number;
  /** 以下都是分。 */
  readonly listPrice: number;
  readonly salePrice: number;
  readonly fullReduction: number;
  readonly shopCoupon: number;
  readonly platformCoupon: number;
  readonly pointsDeduct: number;
  /** 分摊来的订单级优惠合计，与这一行的实付。 */
  readonly discountShare: number;
  readonly pay: number;
  readonly warehouse: WarehouseId;
}

interface AfterSaleFacts {
  readonly afterSaleNo: string;
  readonly line: LineFacts;
  readonly type: AfterSaleType;
  readonly reason: AfterSaleReason;
  readonly qty: number;
  readonly requestedAmount: number;
  /** 审核通过后实退的分；驳回或换货为 0。 */
  readonly refundAmount: number;
  readonly requestedAt: number;
  readonly auditedAt: number;
  readonly rejected: boolean;
  /** 退款到账或换货完成的时刻。 */
  readonly resolvedAt: number;
}

interface PackageFacts {
  readonly packageNo: string;
  readonly carrier: CarrierId;
  readonly waybillNo: string;
  readonly shippedAt: number;
  /** 签收、拒收或判定丢件的时刻。 */
  readonly endedAt: number;
  readonly outcome: 'SIGNED' | 'REJECTED' | 'LOST';
  readonly lines: readonly LineFacts[];
}

/** 一张子单下单时就定下的事。 */
interface SubOrderInput {
  readonly orderNo: string;
  readonly parentOrderNo: string;
  readonly placedAt: number;
  readonly placedWeekday: number;
  readonly placedHour: number;
  readonly channel: Channel;
  readonly shopId: ShopId;
  readonly warehouse: WarehouseId;
  readonly member: MemberFacts;
  readonly lines: readonly LineFacts[];
  readonly freight: number;
  readonly promotion: RetailOrderState['promotion'];
  readonly method: PaymentMethod;
  readonly installments: number;
  readonly paidAt: number | null;
  readonly address: RetailAddress;
  readonly invoiceType: InvoiceType;
  readonly tags: OrderTag[];
  readonly remark: string | null;
  /** 这张单落在 A3（直播券叠加）里。 */
  readonly a3: boolean;
}

/** 一张子单完整的生命周期，还没截在「现在」。 */
interface Lifecycle {
  timeoutAt: number | null;
  cancelledAt: number | null;
  cancelReason: CancelReason | null;
  addressChangedAt: number | null;
  packages: PackageFacts[];
  cases: AfterSaleFacts[];
  completedAt: number | null;
  closedAt: number | null;
}

/**
 * 一张子单的事件流：近期的子单留下每一条（时刻、事件名、事件体），其余的
 * 只数条数、记最后一刻——快照的 `version` 与 `eventTime` 要用。
 */
interface StreamLog {
  count: number;
  last: number;
  readonly list:
    [number, readonly string[], Record<string, unknown>[]][] | null;
}

function emit(
  log: StreamLog,
  now: number,
  time: number | null,
  names: readonly string[],
  bodies: Record<string, unknown>[] | null,
): void {
  if (time === null || time > now) return;
  log.count += 1;
  if (time > log.last) log.last = time;
  log.list?.push([time, names, bodies!]);
}

// 事件名。
const E = {
  created: ['order_created'],
  timedOut: ['order_payment_timed_out', 'order_cancelled'],
  paid: ['order_paid'],
  addressChanged: ['address_changed'],
  cancelled: ['order_cancelled', 'refund_succeeded'],
  shipped: ['package_shipped'],
  invoiced: ['invoice_issued'],
  signed: ['package_signed'],
  rejected: ['package_rejected'],
  afterSale: ['after_sale_requested'],
  refunded: ['refund_succeeded'],
  closed: ['order_closed'],
  completed: ['order_completed'],
} as const;

// ---------------------------------------------------------------- 生成

/**
 * 生成栖木生活的零售交易数据。纯函数：同样的参数得到逐字节相同的结果，
 * 不读时钟，也不碰全局随机源。
 */
export function generateRetail(
  options: Partial<RetailOptions> = {},
): RetailDataset {
  const opts: RetailOptions = { ...RETAIL_DEFAULTS, ...options };
  const { seed, from, now } = opts;
  const until = Math.min(opts.to, now);
  const eventsFrom = now - EVENT_WINDOW_DAYS * DAY;
  const random = randomLcg(seed);
  const faker = new Faker({ locale: [zh_CN, base], seed });
  const poisson = randomPoisson.source(random);
  const logNormal = randomLogNormal.source(random);

  const chance = (probability: number): boolean => random() < probability;
  const between = (low: number, high: number): number =>
    low + random() * (high - low);

  // ---- 分布
  const payDelayMinutes = logNormal(Math.log(2), 0.9);
  const payToShip = logNormal(
    Math.log(FULFILMENT.payToShipMedianHours),
    FULFILMENT.payToShipSigma,
  );
  const backlog = FULFILMENT.double11Backlog;
  const backlogShip = logNormal(Math.log(backlog.medianHours), backlog.sigma);
  const signHours = CARRIERS.map(carrier =>
    logNormal(Math.log(carrier.signHours), FULFILMENT.signSigma),
  );
  const typhoonSign = logNormal(
    Math.log(ANOMALIES.a2.signHours),
    ANOMALIES.a2.sigma,
  );
  const buyerIntensity = randomPareto.source(random)(BUYERS.paretoAlpha);
  const pickCarrier = weighted(
    random,
    CARRIERS.map((_, index) => index),
    CARRIERS.map(carrier => carrier.weight),
  );
  const pickLineCount = {
    regular: weighted(random, [1, 2, 3, 4, 5], LINE_COUNT_WEIGHTS.regular),
    promo: weighted(random, [1, 2, 3, 4, 5], LINE_COUNT_WEIGHTS.promo),
  };
  const pickSku = weighted(
    random,
    SKUS,
    SKUS.map(sku => (sku.rank + SKU_POPULARITY.q) ** -SKU_POPULARITY.s),
  );
  const hoursOfDay = HOUR_WEIGHTS.map((_, hour) => hour);
  const pickHour = weighted(random, hoursOfDay, HOUR_WEIGHTS);
  const otherHours = HOUR_WEIGHTS.slice(1).reduce((a, b) => a + b, 0);
  const pickDouble11Hour = weighted(
    random,
    hoursOfDay,
    HOUR_WEIGHTS.map((weight, hour) =>
      hour === 0
        ? (otherHours * DOUBLE_11_MIDNIGHT_SHARE) /
          (1 - DOUBLE_11_MIDNIGHT_SHARE)
        : weight,
    ),
  );
  const pickPayment = {
    small: paymentPicker(random, PAYMENT_WEIGHTS.small),
    large: paymentPicker(random, PAYMENT_WEIGHTS.large),
  };
  const pickInstallments = weighted(random, INSTALLMENTS, [40, 35, 25]);
  const returnFactorOf = new Map<Category1, number>(
    CATEGORIES.map(category => [category.name, category.returnFactor]),
  );
  const reasonPickers = {
    REFUND_ONLY: reasonPicker(random, AFTER_SALE_REASON_WEIGHTS.REFUND_ONLY),
    RETURN_REFUND: reasonPicker(
      random,
      AFTER_SALE_REASON_WEIGHTS.RETURN_REFUND,
    ),
    EXCHANGE: reasonPicker(random, AFTER_SALE_REASON_WEIGHTS.EXCHANGE),
  };
  const double11Windows = [2024, 2025, 2026].map(year => {
    const start = shanghai(`${year}-11-11`);
    return [start, start + backlog.days * DAY] as const;
  });

  // ---- 买家层：faker 只用在这里（约 7000 次）
  const remarks = Array.from({ length: 240 }, () => {
    const remark = faker.helpers.arrayElement(REMARKS);
    return faker.datatype.boolean(0.3)
      ? `${remark}（${faker.person.lastName()}${faker.helpers.arrayElement(REMARK_SIGNATURES)}）`
      : remark;
  });
  const provinceChoices = PROVINCES.map(province => ({
    weight: province.weight,
    value: province,
  }));
  const levelChoices = MEMBER_LEVELS.map(level => ({
    weight: level.weight,
    value: level,
  }));
  const members: MemberFacts[] = [];
  const tallies: MemberTally[] = [];
  const memberCumulative: number[] = [];
  let memberWeightTotal = 0;

  function createMember(channel: Channel, placedAt: number): MemberFacts {
    const level = faker.helpers.weightedArrayElement(levelChoices);
    const province = faker.helpers.weightedArrayElement(provinceChoices);
    const city = faker.helpers.weightedArrayElement(
      province.cities.map(candidate => ({
        weight:
          candidate.weight *
          (channel === 'DISTRIBUTION' && candidate.tier === 'TIER_3_BELOW'
            ? BUYERS.distributionTier3Boost
            : 1),
        value: candidate,
      })),
    );
    const member: MemberFacts = {
      index: members.length,
      id: `M${pad(100001 + members.length, 6)}`,
      nick: `${faker.person.lastName()}*`,
      level: level.id,
      registerChannel: channel,
      registeredAt:
        placedAt - faker.number.int({ min: 10, max: 30 * 24 * 60 }) * MINUTE,
      province,
      city,
      district: faker.helpers.arrayElement(city.districts),
    };
    members.push(member);
    tallies.push({
      first: null,
      firstPaidAt: Infinity,
      lastPaidAt: member.registeredAt,
      lastPaidParent: '',
      paidParents: 0,
      paidSubOrders: 0,
      totalPaid: 0,
    });
    memberWeightTotal += buyerIntensity() * level.intensity;
    memberCumulative.push(memberWeightTotal);
    return member;
  }

  function returningMember(): MemberFacts {
    return members[search(memberCumulative, random() * memberWeightTotal)];
  }

  // ---- 履约的时刻
  function shipTime(paidAt: number, warehouse: WarehouseId): number {
    let double11 = false;
    for (const [start, end] of double11Windows) {
      if (paidAt >= start && paidAt < end) double11 = true;
    }
    const delay =
      double11 && chance(backlog.share) ? backlogShip() : payToShip();
    return delayShipment(paidAt + delay * HOUR, warehouse);
  }

  /** A6、A7：仓库停发或分拣线故障，把本该在这段时间发出的包裹推后。 */
  function delayShipment(natural: number, warehouse: WarehouseId): number {
    const { a6, a7 } = ANOMALIES;
    const drainEnd = a6.closedTo + a6.drainDays * DAY;
    if (natural >= a6.closedFrom && natural < drainEnd) {
      // 节后按先后顺序消化：[停发起点, 消化完) 线性压到 [复工, 消化完)。
      const position = (natural - a6.closedFrom) / (drainEnd - a6.closedFrom);
      return a6.closedTo + position * (drainEnd - a6.closedTo);
    }
    if (warehouse === a7.warehouse && natural >= a7.from && natural < a7.to) {
      return a7.resortFrom + random() * a7.resortHours * HOUR;
    }
    return natural;
  }

  function signTime(
    carrier: number,
    province: Province,
    shippedAt: number,
  ): number {
    const { a2 } = ANOMALIES;
    const typhoon =
      CARRIERS[carrier].id === a2.carrier &&
      a2.provinces.includes(province.name) &&
      shippedAt >= a2.from &&
      shippedAt < a2.to;
    const transit = typhoon ? typhoonSign() : signHours[carrier]();
    const extra = province.remote ? REMOTE_EXTRA_HOURS : 0;
    return shippedAt + (transit + extra) * HOUR;
  }

  // ---- 输出
  const orders: RetailSnapshot<RetailOrderState>[] = [];
  const afterSales: RetailSnapshot<RetailAfterSaleState>[] = [];
  const waybills: RetailSnapshot<RetailWaybillState>[] = [];
  const events: RetailEventStream[] = [];
  let waybillSequence = 0;

  const volumeScale = SCALE_FACTORS[opts.scale];
  for (let day = shanghaiDayStart(from); day < until; day += DAY) {
    const calendar = dayOf(day);
    const lambda =
      (VOLUME.baseline * volumeScale * dailyFactor(calendar)) /
      VOLUME.subOrdersPerParent;
    const count = poisson(lambda)();
    const pickChannel = channelPicker(random, calendar);
    const isDouble11 = calendar.date.endsWith('-11-11');
    const times: number[] = [];
    for (let i = 0; i < count; i++) {
      const hour = isDouble11 ? pickDouble11Hour() : pickHour();
      const time = day + hour * HOUR + Math.floor(random() * HOUR);
      if (time >= from && time < until) times.push(time);
    }
    times.sort((a, b) => a - b);
    let subSequence = 0;
    for (let parentIndex = 0; parentIndex < times.length; parentIndex++) {
      const placedAt = times[parentIndex];
      const placedHour = Math.floor((placedAt - day) / HOUR);
      const channel = pickChannel();
      const parentOrderNo = `PO${calendar.compact}${pad(parentIndex + 1, 5)}`;
      const promo = calendar.activity !== undefined;
      const a3 = channel === ANOMALIES.a3.channel && inA3(placedAt);
      const newShare = a3
        ? ANOMALIES.a3.newShare
        : promo
          ? BUYERS.newSharePromo
          : BUYERS.newShareRegular;
      const member =
        members.length === 0 || chance(newShare)
          ? createMember(channel, placedAt)
          : returningMember();
      const shopId = shopOf(channel, random);

      // 商品行与单品直降；同一个 SKU 抽到两次就并成一行。
      const lineCount = promo ? pickLineCount.promo() : pickLineCount.regular();
      const skus: Sku[] = [];
      const quantities: number[] = [];
      for (let i = 0; i < lineCount; i++) {
        const sku = pickSku();
        const qtyWeights =
          sku.listPrice < 50
            ? QTY_WEIGHTS.under50
            : sku.listPrice < 100
              ? QTY_WEIGHTS.under100
              : QTY_WEIGHTS.other;
        const qty = weightedIndex(random(), qtyWeights) + 1;
        const existing = skus.indexOf(sku);
        if (existing >= 0) quantities[existing] += qty;
        else {
          skus.push(sku);
          quantities.push(qty);
        }
      }
      const discount = promo ? DISCOUNTS.promo : DISCOUNTS.regular;
      const listPrices: number[] = [];
      const salePrices: number[] = [];
      const saleAmounts: number[] = [];
      let subtotal = 0;
      for (let i = 0; i < skus.length; i++) {
        const listPrice = Math.round(skus[i].listPrice * 100);
        const rate = discount.itemRate + random() * discount.itemSpread;
        const salePrice = Math.round(listPrice * (1 - rate));
        listPrices.push(listPrice);
        salePrices.push(salePrice);
        saleAmounts.push(salePrice * quantities[i]);
        subtotal += salePrice * quantities[i];
      }

      // 订单级优惠：满减、店铺券、平台券、积分（分）
      const coupons: string[] = [];
      let fullReduction = promo
        ? Math.floor(subtotal / (FULL_REDUCTION.every * 100)) *
          FULL_REDUCTION.minus *
          100
        : 0;
      let shopCoupon = 0;
      if (chance(discount.shopCoupon)) {
        const coupon = bestCoupon('SHOP', subtotal);
        if (coupon) {
          shopCoupon = coupon.amount * 100;
          coupons.push(coupon.id);
        }
      }
      let platformCoupon = 0;
      if (chance(discount.platformCoupon)) {
        const coupon = bestCoupon('PLATFORM', subtotal);
        if (coupon) {
          platformCoupon = coupon.amount * 100;
          coupons.push(coupon.id);
        }
      }
      if (a3) {
        platformCoupon += Math.round(subtotal * ANOMALIES.a3.stackRate);
        coupons.push('PC-LIVE-STACK');
      }
      let pointsDeduct = chance(DISCOUNTS.points)
        ? Math.min(
            Math.round(subtotal * DISCOUNTS.pointsRate),
            DISCOUNTS.pointsCap * 100,
          )
        : 0;
      const over =
        fullReduction +
        shopCoupon +
        platformCoupon +
        pointsDeduct -
        Math.floor(subtotal * DISCOUNTS.cap);
      if (over > 0) {
        // 超出上限时先少抵积分，再少减满减。
        const fromPoints = Math.min(over, pointsDeduct);
        pointsDeduct -= fromPoints;
        fullReduction -= Math.min(over - fromPoints, fullReduction);
      }
      const fullShares = allocate(fullReduction, saleAmounts);
      const shopShares = allocate(shopCoupon, saleAmounts);
      const platformShares = allocate(platformCoupon, saleAmounts);
      const pointShares = allocate(pointsDeduct, saleAmounts);

      // 按仓库拆单：就近发货；小家电只从华南仓发；缺货的行从另一个仓调。
      const nearest = member.province.warehouse;
      const alternates = ALTERNATE_WAREHOUSES.get(nearest)!;
      const transfer = alternates[Math.floor(random() * alternates.length)];
      const groupWarehouses: WarehouseId[] = [];
      const groups: LineFacts[][] = [];
      for (let i = 0; i < skus.length; i++) {
        const sku = skus[i];
        const warehouse = sku.isAppliance
          ? APPLIANCE_WAREHOUSE
          : chance(FULFILMENT.transferLine)
            ? transfer
            : nearest;
        const discountShare =
          fullShares[i] + shopShares[i] + platformShares[i] + pointShares[i];
        let group = groupWarehouses.indexOf(warehouse);
        if (group < 0) {
          group = groups.length;
          groupWarehouses.push(warehouse);
          groups.push([]);
        }
        groups[group].push({
          index: groups[group].length,
          lineId: LINE_IDS[groups[group].length],
          sku,
          qty: quantities[i],
          listPrice: listPrices[i],
          salePrice: salePrices[i],
          fullReduction: fullShares[i],
          shopCoupon: shopShares[i],
          platformCoupon: platformShares[i],
          pointsDeduct: pointShares[i],
          discountShare,
          pay: saleAmounts[i] - discountShare,
          warehouse,
        });
      }

      // 付款（按主单）：满 ¥99 包邮，按子单算。
      const freights = groups.map(group => {
        let goods = 0;
        for (const line of group) goods += line.pay;
        return goods >= FREE_SHIPPING_THRESHOLD * 100
          ? 0
          : FREIGHTS[Math.floor(random() * FREIGHTS.length)] * 100;
      });
      let parentPayable = 0;
      groups.forEach((group, index) => {
        parentPayable += freights[index];
        for (const line of group) parentPayable += line.pay;
      });
      const unionPayWindow =
        placedAt >= ANOMALIES.a4.from && placedAt < ANOMALIES.a4.to;
      const method =
        unionPayWindow && chance(ANOMALIES.a4.share)
          ? ANOMALIES.a4.method
          : parentPayable >= INSTALLMENT_THRESHOLD * 100
            ? pickPayment.large()
            : pickPayment.small();
      const installments =
        method === 'CREDIT_INSTALLMENT' ? pickInstallments() : 0;
      const unpaidRate =
        unionPayWindow && method === ANOMALIES.a4.method
          ? ANOMALIES.a4.failureRate
          : isDouble11 && placedHour === 0
            ? FUNNEL.unpaidDouble11Midnight
            : FUNNEL.unpaid;
      const paidAt = chance(unpaidRate)
        ? null
        : placedAt +
          Math.min(
            FUNNEL.paymentTimeoutMinutes - 1,
            Math.max(0.2, payDelayMinutes()),
          ) *
            MINUTE;

      // 地址：A5 旧版小程序不报城市
      const cityMissing =
        channel === ANOMALIES.a5.channel &&
        chance(ANOMALIES.a5.share / (channelShare(calendar, channel) / 100));
      const address: RetailAddress = {
        province: member.province.name,
        city: cityMissing ? null : member.city.name,
        cityTier: cityMissing ? null : member.city.tier,
        district: cityMissing ? null : member.district,
        recipient: member.nick,
      };
      const invoiceType = invoiceOf(shopId, channel, random);
      const tags = tagsOf(calendar, random);
      const remark = chance(0.1)
        ? remarks[Math.floor(random() * remarks.length)]
        : null;
      const promotion = { activityId: calendar.activity?.id ?? null, coupons };

      for (let group = 0; group < groups.length; group++) {
        subSequence += 1;
        const input: SubOrderInput = {
          orderNo: `TO${calendar.compact}${pad(subSequence, 5)}`,
          parentOrderNo,
          placedAt,
          placedWeekday: calendar.weekday === 0 ? 7 : calendar.weekday,
          placedHour,
          channel,
          shopId,
          warehouse: groupWarehouses[group],
          member,
          lines: groups[group],
          freight: freights[group],
          promotion,
          method,
          installments,
          paidAt,
          address,
          invoiceType,
          tags,
          remark,
          a3,
        };
        project(input, lifecycle(input));
      }
    }
  }

  // ---- 生命周期：付款、取消、发货、签收、拒收、售后、完成或关闭
  function lifecycle(input: SubOrderInput): Lifecycle {
    const { placedAt, paidAt, lines } = input;
    const life: Lifecycle = {
      timeoutAt: null,
      cancelledAt: null,
      cancelReason: null,
      addressChangedAt: null,
      packages: [],
      cases: [],
      completedAt: null,
      closedAt: null,
    };
    if (paidAt === null) {
      life.timeoutAt = placedAt + FUNNEL.paymentTimeoutMinutes * MINUTE;
      life.cancelledAt = life.timeoutAt;
      life.cancelReason = 'PAYMENT_TIMEOUT';
      return life;
    }
    const firstShip = shipTime(paidAt, input.warehouse);
    // A3 那一天的直播单：先定下会不会退，再走取消与发货。
    const a3Refund = input.a3 && chance(ANOMALIES.a3.refundRate);
    const buyerCancel = input.a3
      ? ANOMALIES.a3.cancelRate
      : FUNNEL.buyerCancel * CHANNEL_RETURN_FACTOR[input.channel];
    const roll = random();
    if (roll < buyerCancel) life.cancelReason = 'BUYER_CANCELLED';
    else if (roll < buyerCancel + FUNNEL.riskCancel)
      life.cancelReason = 'RISK_CONTROL';
    else if (roll < buyerCancel + FUNNEL.riskCancel + FUNNEL.stockCancel)
      life.cancelReason = 'OUT_OF_STOCK';
    if (life.cancelReason) {
      life.cancelledAt = paidAt + between(0.05, 0.95) * (firstShip - paidAt);
      return life;
    }
    if (chance(FUNNEL.addressChanged)) {
      life.addressChangedAt = paidAt + between(0.1, 0.9) * (firstShip - paidAt);
    }
    shipPackages(input, life, firstShip);
    const only = life.packages.length === 1 ? life.packages[0] : undefined;
    if (only && only.outcome !== 'SIGNED') {
      // 拒收或丢件：每一行全额退，订单随之关闭。
      const rejected = only.outcome === 'REJECTED';
      const requestedAt = only.endedAt + between(1, 12) * HOUR;
      for (const line of lines) {
        const auditedAt = requestedAt + between(1, 12) * HOUR;
        life.cases.push({
          afterSaleNo: `AS${input.orderNo.slice(2)}${life.cases.length + 1}`,
          line,
          type: rejected ? 'REJECTION' : 'REFUND_ONLY',
          reason: rejected ? 'REJECTED_ON_DELIVERY' : 'LOST_IN_TRANSIT',
          qty: line.qty,
          requestedAmount: line.pay,
          refundAmount: line.pay,
          requestedAt,
          auditedAt,
          rejected: false,
          resolvedAt: auditedAt + between(0.5, 24) * HOUR,
        });
      }
    } else {
      requestAfterSales(input, life, a3Refund);
    }
    settle(input, life);
    return life;
  }

  function shipPackages(
    input: SubOrderInput,
    life: Lifecycle,
    firstShip: number,
  ): void {
    const { lines } = input;
    const province = input.member.province;
    const split = lines.length >= 2 && chance(FULFILMENT.splitPackage);
    const half = Math.ceil(lines.length / 2);
    const parts = split ? [lines.slice(0, half), lines.slice(half)] : [lines];
    const [low, high] = FULFILMENT.splitDelayHours;
    const roll = random();
    for (let index = 0; index < parts.length; index++) {
      const shippedAt =
        index === 0
          ? firstShip
          : Math.max(
              firstShip,
              delayShipment(
                firstShip + between(low, high) * HOUR,
                input.warehouse,
              ),
            );
      const carrier = pickCarrier();
      const arrival = signTime(carrier, province, shippedAt);
      const outcome =
        split || roll >= FUNNEL.rejection + FUNNEL.lost
          ? 'SIGNED'
          : roll < FUNNEL.rejection
            ? 'REJECTED'
            : 'LOST';
      waybillSequence += 1;
      life.packages.push({
        packageNo: `${input.orderNo}-${index + 1}`,
        carrier: CARRIERS[carrier].id,
        waybillNo: waybillNoOf(carrier, waybillSequence),
        shippedAt,
        endedAt: outcome === 'LOST' ? shippedAt + 5 * DAY : arrival,
        outcome,
        lines: parts[index],
      });
    }
  }

  function requestAfterSales(
    input: SubOrderInput,
    life: Lifecycle,
    a3Refund: boolean,
  ): void {
    const { lines, placedAt } = input;
    let signedAt = 0;
    for (const pkg of life.packages) signedAt = Math.max(signedAt, pkg.endedAt);
    const { a1 } = ANOMALIES;
    const towel = lines.find(line => line.sku.skuId === a1.skuId);
    if (towel && signedAt >= a1.from) {
      if (chance(a1.returnRate)) {
        const reason = chance(a1.qualityShare)
          ? 'QUALITY_ISSUE'
          : 'NO_REASON_7_DAYS';
        addCase(input, life, towel, 'RETURN_REFUND', reason, signedAt, false);
      }
      return;
    }
    if (input.a3) {
      if (a3Refund) {
        const line = lines[Math.floor(random() * lines.length)];
        // 发货之后、下单 7 天之内申请（发货太晚的，申请落在发货之后）。
        const requestedAt = Math.max(
          life.packages[0].shippedAt + between(1, 6) * HOUR,
          placedAt + between(1.5, 6) * DAY,
        );
        addCase(
          input,
          life,
          line,
          'REFUND_ONLY',
          'NO_REASON_7_DAYS',
          requestedAt,
          true,
        );
      }
      return;
    }
    const line =
      lines.length === 1
        ? lines[0]
        : lines[
            weightedIndex(
              random(),
              lines.map(item => item.pay),
            )
          ];
    const factor =
      CHANNEL_RETURN_FACTOR[input.channel] *
      (line.sku.skuId === a1.skuId
        ? a1.baseReturnFactor
        : returnFactorOf.get(line.sku.category1)!);
    const roll = random() * 100;
    const refundOnly = AFTER_SALE_RATES.REFUND_ONLY * factor;
    const returnRefund = refundOnly + AFTER_SALE_RATES.RETURN_REFUND * factor;
    const exchange = returnRefund + AFTER_SALE_RATES.EXCHANGE * factor;
    const type =
      roll < refundOnly
        ? 'REFUND_ONLY'
        : roll < returnRefund
          ? 'RETURN_REFUND'
          : roll < exchange
            ? 'EXCHANGE'
            : null;
    if (type === null) return;
    let reason = reasonPickers[type]();
    if (
      type === 'RETURN_REFUND' &&
      line.sku.category1 === '床品布艺' &&
      chance(0.5)
    ) {
      reason = 'SIZE_COLOR_MISMATCH';
    }
    addCase(input, life, line, type, reason, signedAt, false);
  }

  function addCase(
    input: SubOrderInput,
    life: Lifecycle,
    line: LineFacts,
    type: 'REFUND_ONLY' | 'RETURN_REFUND' | 'EXCHANGE',
    reason: AfterSaleReason,
    start: number,
    exact: boolean,
  ): void {
    const requestedAt = exact ? start : start + between(2, 6.5 * 24) * HOUR;
    const auditedAt = requestedAt + between(1, 24) * HOUR;
    const rejected = type !== 'EXCHANGE' && chance(FUNNEL.auditRejected);
    const qty = line.qty > 1 && chance(0.4) ? 1 : line.qty;
    const requestedAmount =
      type === 'EXCHANGE'
        ? 0
        : type === 'REFUND_ONLY' && reason !== 'QUALITY_ISSUE'
          ? Math.max(
              1,
              Math.round((line.pay * qty * between(0.3, 1)) / line.qty),
            )
          : qty === line.qty
            ? line.pay
            : Math.round((line.pay * qty) / line.qty);
    const settleHours =
      type === 'RETURN_REFUND'
        ? between(48, 120)
        : type === 'EXCHANGE'
          ? between(72, 168)
          : between(0.5, 12);
    life.cases.push({
      afterSaleNo: `AS${input.orderNo.slice(2)}${life.cases.length + 1}`,
      line,
      type,
      reason,
      qty,
      requestedAmount,
      refundAmount: rejected || type === 'EXCHANGE' ? 0 : requestedAmount,
      requestedAt,
      auditedAt,
      rejected,
      resolvedAt: auditedAt + settleHours * HOUR,
    });
  }

  /** 每一行都全额退了就关闭；否则签收 7 天、售后办完后交易完成。 */
  function settle(input: SubOrderInput, life: Lifecycle): void {
    const refunded = input.lines.map(() => 0);
    let lastResolution = 0;
    for (const item of life.cases) {
      refunded[item.line.index] += item.refundAmount;
      lastResolution = Math.max(lastResolution, item.resolvedAt);
    }
    if (input.lines.every(line => refunded[line.index] === line.pay)) {
      life.closedAt = lastResolution;
      return;
    }
    let signedAt = 0;
    for (const pkg of life.packages) {
      if (pkg.outcome !== 'SIGNED') return;
      signedAt = Math.max(signedAt, pkg.endedAt);
    }
    life.completedAt = Math.max(
      signedAt + FUNNEL.completeAfterSignDays * DAY,
      lastResolution,
    );
  }

  // ---- 截在「现在」：快照、售后单、运单、事件流
  function project(input: SubOrderInput, life: Lifecycle): void {
    const { orderNo, placedAt, lines, member } = input;
    const recent = placedAt >= eventsFrom;
    const paid = input.paidAt !== null && input.paidAt <= now;
    const paidAt = paid ? input.paidAt : null;
    const cancelled = life.cancelledAt !== null && life.cancelledAt <= now;
    const closed = life.closedAt !== null && life.closedAt <= now;
    const completed = life.completedAt !== null && life.completedAt <= now;
    let payable = input.freight;
    for (const line of lines) payable += line.pay;

    const refundedQty = lines.map(() => 0);
    const refundedCents = lines.map(() => 0);
    if (cancelled && paid) {
      for (const line of lines) {
        refundedQty[line.index] = line.qty;
        refundedCents[line.index] = line.pay;
      }
    }
    let casesOpen = false;
    let casesSeen = 0;
    for (const item of life.cases) {
      if (item.requestedAt > now) continue;
      casesSeen += 1;
      if (item.resolvedAt > now) {
        casesOpen = true;
        continue;
      }
      if (item.refundAmount > 0) {
        refundedQty[item.line.index] += item.qty;
        refundedCents[item.line.index] += item.refundAmount;
      }
    }
    let lineRefunds = 0;
    for (const cents of refundedCents) lineRefunds += cents;
    const refunded =
      lineRefunds + ((cancelled && paid) || closed ? input.freight : 0);

    let shippedAt: number | null = null;
    let shippedCount = 0;
    let signedAt: number | null = 0;
    for (const pkg of life.packages) {
      if (pkg.shippedAt <= now) {
        shippedCount += 1;
        if (shippedAt === null) shippedAt = pkg.shippedAt;
      }
      if (pkg.outcome === 'SIGNED' && pkg.endedAt <= now && signedAt !== null)
        signedAt = Math.max(signedAt, pkg.endedAt);
      else signedAt = null;
    }
    if (life.packages.length === 0) signedAt = null;
    const packageCount = life.packages.length;

    let status: OrderStatus;
    if (!paid) status = cancelled ? 'CANCELLED' : 'PENDING_PAYMENT';
    else if (life.cancelledAt !== null)
      status = cancelled ? 'CANCELLED' : 'PAID';
    else if (closed) status = 'CLOSED';
    else if (completed) status = 'COMPLETED';
    else if (signedAt !== null) status = 'SIGNED';
    else if (shippedCount === 0) status = 'PAID';
    else if (shippedCount < packageCount) status = 'PARTIALLY_SHIPPED';
    else status = 'SHIPPED';

    let listAmount = 0;
    let itemDiscount = 0;
    let fullReduction = 0;
    let shopCoupon = 0;
    let platformCoupon = 0;
    let pointsDeduct = 0;
    const items: RetailOrderLine[] = [];
    for (const line of lines) {
      listAmount += line.listPrice * line.qty;
      itemDiscount += (line.listPrice - line.salePrice) * line.qty;
      fullReduction += line.fullReduction;
      shopCoupon += line.shopCoupon;
      platformCoupon += line.platformCoupon;
      pointsDeduct += line.pointsDeduct;
      const sku = line.sku;
      items.push({
        lineId: line.lineId,
        skuId: sku.skuId,
        spuId: sku.spuId,
        title: sku.title,
        category1: sku.category1,
        category2: sku.category2,
        brand: sku.brand,
        priceBand: sku.priceBand,
        qty: line.qty,
        listPrice: yuan(line.listPrice),
        salePrice: yuan(line.salePrice),
        discountShare: yuan(line.discountShare),
        payAmount: yuan(line.pay),
        refundedQty: Math.min(line.qty, refundedQty[line.index]),
        refundedAmount: yuan(refundedCents[line.index]),
      });
    }

    const shipClock = shippedAt ?? (cancelled ? life.cancelledAt! : now);
    const packages: RetailPackage[] = [];
    for (const pkg of life.packages) {
      if (pkg.shippedAt > now) continue;
      packages.push({
        packageNo: pkg.packageNo,
        carrier: pkg.carrier,
        waybillNo: pkg.waybillNo,
        shippedAt: pkg.shippedAt,
        signedAt:
          pkg.outcome === 'SIGNED' && pkg.endedAt <= now ? pkg.endedAt : null,
        lineIds: pkg.lines.map(line => line.lineId),
      });
    }
    const state: RetailOrderState = {
      orderNo,
      parentOrderNo: input.parentOrderNo,
      shopId: input.shopId,
      channel: input.channel,
      warehouse: input.warehouse,
      buyer: {
        id: member.id,
        nick: member.nick,
        level: member.level,
        isNewBuyer: false,
      },
      status,
      afterSaleStatus:
        casesSeen === 0 ? 'NONE' : casesOpen ? 'IN_PROGRESS' : 'FINISHED',
      cancelReason: cancelled ? life.cancelReason : null,
      items,
      amounts: {
        listAmount: yuan(listAmount),
        itemDiscount: yuan(itemDiscount),
        fullReduction: yuan(fullReduction),
        shopCoupon: yuan(shopCoupon),
        platformCoupon: yuan(platformCoupon),
        pointsDeduct: yuan(pointsDeduct),
        freight: yuan(input.freight),
        payableAmount: yuan(payable),
        paidAmount: paid ? yuan(payable) : 0,
        refundedAmount: yuan(refunded),
      },
      payment: {
        method: input.method,
        installments: input.installments,
        paidAt,
      },
      promotion: input.promotion,
      address: input.address,
      packages,
      timing: {
        paidAt,
        shippedAt,
        signedAt,
        completedAt: completed ? life.completedAt : null,
        cancelledAt: cancelled ? life.cancelledAt : null,
        closedAt: closed ? life.closedAt : null,
        shipDueAt: paidAt === null ? null : paidAt + SHIP_SLA_HOURS * HOUR,
      },
      placedWeekday: input.placedWeekday,
      placedHour: input.placedHour,
      payToShipHours:
        paidAt !== null && shippedAt !== null
          ? hours(shippedAt - paidAt)
          : null,
      shipToSignHours:
        shippedAt !== null && signedAt !== null
          ? hours(signedAt - shippedAt)
          : null,
      shipSlaBreached:
        paidAt !== null && shipClock - paidAt > SHIP_SLA_HOURS * HOUR,
      invoice: { type: input.invoiceType },
      tags: input.tags,
      remark: input.remark,
    };

    // 会员的累计值
    if (paidAt !== null) {
      const tally = tallies[member.index];
      if (
        tally.first === null ||
        paidAt < tally.firstPaidAt ||
        (paidAt === tally.firstPaidAt && orderNo < tally.first.orderNo)
      ) {
        tally.first = state;
        tally.firstPaidAt = paidAt;
      }
      if (tally.lastPaidParent !== input.parentOrderNo) {
        tally.lastPaidParent = input.parentOrderNo;
        tally.paidParents += 1;
      }
      tally.paidSubOrders += 1;
      tally.totalPaid += payable;
      tally.lastPaidAt = Math.max(tally.lastPaidAt, paidAt);
    }

    // 事件流
    const log: StreamLog = { count: 0, last: 0, list: recent ? [] : null };
    const money = yuan(payable);
    emit(
      log,
      now,
      placedAt,
      E.created,
      recent
        ? [
            {
              parentOrderNo: input.parentOrderNo,
              channel: input.channel,
              payableAmount: money,
            },
          ]
        : null,
    );
    emit(
      log,
      now,
      life.timeoutAt,
      E.timedOut,
      recent ? [{ method: input.method }, { reason: 'PAYMENT_TIMEOUT' }] : null,
    );
    emit(
      log,
      now,
      paidAt,
      E.paid,
      recent ? [{ method: input.method, paidAmount: money }] : null,
    );
    emit(
      log,
      now,
      life.addressChangedAt,
      E.addressChanged,
      recent
        ? [{ province: input.address.province, city: input.address.city }]
        : null,
    );
    if (paid && life.cancelReason !== 'PAYMENT_TIMEOUT') {
      emit(
        log,
        now,
        life.cancelledAt,
        E.cancelled,
        recent ? [{ reason: life.cancelReason }, { amount: money }] : null,
      );
    }
    life.packages.forEach((pkg, index) => {
      emit(
        log,
        now,
        pkg.shippedAt,
        E.shipped,
        recent
          ? [
              {
                packageNo: pkg.packageNo,
                carrier: pkg.carrier,
                waybillNo: pkg.waybillNo,
              },
            ]
          : null,
      );
      if (index === 0 && input.invoiceType !== 'NONE') {
        emit(
          log,
          now,
          pkg.shippedAt + HOUR,
          E.invoiced,
          recent ? [{ type: input.invoiceType, amount: money }] : null,
        );
      }
      if (pkg.outcome !== 'LOST') {
        emit(
          log,
          now,
          pkg.endedAt,
          pkg.outcome === 'SIGNED' ? E.signed : E.rejected,
          recent ? [{ packageNo: pkg.packageNo }] : null,
        );
      }
    });
    for (const item of life.cases) {
      emit(
        log,
        now,
        item.requestedAt,
        E.afterSale,
        recent
          ? [
              {
                afterSaleNo: item.afterSaleNo,
                type: item.type,
                reason: item.reason,
                lineId: item.line.lineId,
                amount: yuan(item.requestedAmount),
              },
            ]
          : null,
      );
      if (item.refundAmount > 0) {
        emit(
          log,
          now,
          item.resolvedAt,
          E.refunded,
          recent
            ? [
                {
                  afterSaleNo: item.afterSaleNo,
                  amount: yuan(item.refundAmount),
                },
              ]
            : null,
        );
      }
    }
    emit(
      log,
      now,
      life.closedAt,
      E.closed,
      recent ? [{ refundedAmount: yuan(refunded) }] : null,
    );
    emit(log, now, life.completedAt, E.completed, recent ? [{}] : null);

    orders.push({
      contextName: CONTEXT,
      aggregateName: 'trade_order',
      aggregateId: orderNo,
      tenantId: TENANT,
      ownerId: member.id,
      spaceId: '',
      version: log.count,
      firstEventTime: placedAt,
      eventTime: log.last,
      deleted: false,
      state,
    });
    if (log.list) {
      log.list.sort((a, b) => a[0] - b[0]);
      log.list.forEach(([time, names, bodies], index) => {
        const version = index + 1;
        const id = `${orderNo}-v${version}`;
        events.push({
          id,
          contextName: CONTEXT,
          aggregateName: 'trade_order',
          aggregateId: orderNo,
          tenantId: TENANT,
          ownerId: member.id,
          spaceId: '',
          commandId: `${id}-command`,
          requestId: `${id}-request`,
          version,
          header: { command_operator: version === 1 ? member.id : 'system' },
          body: names.map((name, eventIndex) => ({
            id: `${id}-event-${eventIndex + 1}`,
            name,
            revision: '1.0.0',
            bodyType: `${EVENT_TYPE_PREFIX}.${pascal(name)}`,
            body: bodies[eventIndex],
          })),
          createTime: time,
        });
      });
    }

    for (const item of life.cases) {
      if (item.requestedAt > now) continue;
      const audited = item.auditedAt <= now;
      const resolved = item.resolvedAt <= now;
      const refundedNow = resolved && !item.rejected;
      afterSales.push({
        contextName: CONTEXT,
        aggregateName: 'after_sale',
        aggregateId: item.afterSaleNo,
        tenantId: TENANT,
        ownerId: member.id,
        spaceId: '',
        version: 1 + (audited ? 1 : 0) + (audited && refundedNow ? 1 : 0),
        firstEventTime: item.requestedAt,
        eventTime: refundedNow
          ? item.resolvedAt
          : audited
            ? item.auditedAt
            : item.requestedAt,
        deleted: false,
        state: {
          afterSaleNo: item.afterSaleNo,
          orderNo,
          parentOrderNo: input.parentOrderNo,
          lineId: item.line.lineId,
          skuId: item.line.sku.skuId,
          title: item.line.sku.title,
          category1: item.line.sku.category1,
          channel: input.channel,
          shopId: input.shopId,
          buyerId: member.id,
          type: item.type,
          reason: item.reason,
          status: !audited
            ? 'REQUESTED'
            : item.rejected
              ? 'REJECTED'
              : !resolved
                ? 'APPROVED'
                : item.type === 'EXCHANGE'
                  ? 'EXCHANGED'
                  : 'REFUNDED',
          qty: item.qty,
          requestedAmount: yuan(item.requestedAmount),
          refundedAmount: refundedNow ? yuan(item.refundAmount) : 0,
          requestedAt: item.requestedAt,
          auditedAt: audited ? item.auditedAt : null,
          refundedAt:
            refundedNow && item.refundAmount > 0 ? item.resolvedAt : null,
        },
      });
    }

    for (const pkg of life.packages) {
      if (pkg.shippedAt > now) continue;
      const ended = pkg.endedAt <= now;
      const signed = ended && pkg.outcome === 'SIGNED';
      let itemQty = 0;
      let weight = 0;
      for (const line of pkg.lines) {
        itemQty += line.qty;
        weight += line.sku.weightKg * line.qty;
      }
      waybills.push({
        contextName: CONTEXT,
        aggregateName: 'waybill',
        aggregateId: pkg.waybillNo,
        tenantId: TENANT,
        ownerId: member.id,
        spaceId: '',
        version: ended ? 2 : 1,
        firstEventTime: pkg.shippedAt,
        eventTime: ended ? pkg.endedAt : pkg.shippedAt,
        deleted: false,
        state: {
          waybillNo: pkg.waybillNo,
          packageNo: pkg.packageNo,
          orderNo,
          parentOrderNo: input.parentOrderNo,
          buyerId: member.id,
          carrier: pkg.carrier,
          warehouse: input.warehouse,
          province: input.address.province,
          city: input.address.city,
          cityTier: input.address.cityTier,
          remote: member.province.remote === true,
          status: ended ? pkg.outcome : 'IN_TRANSIT',
          lineCount: pkg.lines.length,
          itemQty,
          weightKg: Math.round(weight * 10) / 10,
          shippedAt: pkg.shippedAt,
          signedAt: signed ? pkg.endedAt : null,
          shipToSignHours: signed ? hours(pkg.endedAt - pkg.shippedAt) : null,
        },
      });
    }
  }

  // ---- 会员：累计值由订单算出；新客标在第一张已付款的子单上
  const memberSnapshots = members.map(
    (member): RetailSnapshot<RetailMemberState> => {
      const tally = tallies[member.index];
      if (tally.first) tally.first.buyer.isNewBuyer = true;
      return {
        contextName: CONTEXT,
        aggregateName: 'member',
        aggregateId: member.id,
        tenantId: TENANT,
        ownerId: member.id,
        spaceId: '',
        version: 1 + tally.paidSubOrders,
        firstEventTime: member.registeredAt,
        eventTime: tally.lastPaidAt,
        deleted: false,
        state: {
          id: member.id,
          nick: member.nick,
          level: member.level,
          registerChannel: member.registerChannel,
          registeredAt: member.registeredAt,
          province: member.province.name,
          city: member.city.name,
          cityTier: member.city.tier,
          district: member.district,
          firstOrderAt: tally.first ? tally.firstPaidAt : null,
          orderCount: tally.paidParents,
          totalPaid: yuan(tally.totalPaid),
        },
      };
    },
  );

  afterSales.sort(byTime);
  waybills.sort(byTime);
  events.sort(
    (a, b) =>
      a.createTime - b.createTime ||
      compareText(a.aggregateId, b.aggregateId) ||
      a.version - b.version,
  );
  return {
    options: opts,
    orders,
    afterSales,
    members: memberSnapshots,
    waybills,
    events,
  };
}

function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function byTime(
  a: { firstEventTime: number; aggregateId: string },
  b: { firstEventTime: number; aggregateId: string },
): number {
  return (
    a.firstEventTime - b.firstEventTime ||
    compareText(a.aggregateId, b.aggregateId)
  );
}

// ---------------------------------------------------------------- 日历

interface CalendarDay {
  readonly start: number;
  /** `YYYY-MM-DD` */
  readonly date: string;
  /** `YYYYMMDD` */
  readonly compact: string;
  readonly month: number;
  /** 0 是周日。 */
  readonly weekday: number;
  readonly activity: Activity | undefined;
  /** 距 2024-09 的月数，渠道结构按它插值。 */
  readonly monthIndex: number;
}

const SPRING_FESTIVAL_STARTS = SPRING_FESTIVALS.map(date => shanghai(date));

function dayOf(start: number): CalendarDay {
  const local = dayjs(start).utcOffset(8 * 60);
  const date = local.format('YYYY-MM-DD');
  return {
    start,
    date,
    compact: date.replaceAll('-', ''),
    month: local.month() + 1,
    weekday: local.day(),
    activity: ACTIVITIES.find(
      activity => activity.start <= date && date <= activity.end,
    ),
    monthIndex: (local.year() - 2024) * 12 + local.month() - 8,
  };
}

function dailyFactor(calendar: CalendarDay): number {
  const years = (calendar.start - VOLUME.origin) / (365.25 * DAY);
  const trend = (1 + VOLUME.growthPerYear) ** years;
  const activity = calendar.activity;
  const promo = !activity
    ? 1
    : calendar.date === activity.end
      ? activity.windowFactor * activity.peakFactor
      : activity.windowFactor;
  const spring = SPRING_FESTIVAL_STARTS.some(
    festival => Math.abs(festival - calendar.start) <= 3 * DAY,
  )
    ? SPRING_FESTIVAL_FACTOR
    : 1;
  return (
    trend *
    MONTH_FACTORS[calendar.month - 1] *
    WEEKDAY_FACTORS[calendar.weekday] *
    promo *
    spring
  );
}

function inA3(time: number): boolean {
  return time >= ANOMALIES.a3.from && time < ANOMALIES.a3.to;
}

/** 这一天某个渠道的份额（%）：两端之间按月线性插值。 */
function channelShare(calendar: CalendarDay, channel: Channel): number {
  const t = Math.min(1, Math.max(0, calendar.monthIndex / 24));
  const mix = CHANNEL_MIX[channel];
  return mix.start + (mix.end - mix.start) * t;
}

function channelPicker(random: Random, calendar: CalendarDay): () => Channel {
  const channels = Object.keys(CHANNEL_MIX) as Channel[];
  const a3Day = inA3(calendar.start);
  return weighted(
    random,
    channels,
    channels.map(
      channel =>
        channelShare(calendar, channel) *
        (a3Day && channel === ANOMALIES.a3.channel
          ? ANOMALIES.a3.channelBoost
          : 1),
    ),
  );
}

// ---------------------------------------------------------------- 小工具

function weightedIndex(roll: number, weights: readonly number[]): number {
  let total = 0;
  for (const weight of weights) total += weight;
  let target = roll * total;
  for (let i = 0; i < weights.length; i++) {
    target -= weights[i];
    if (target < 0) return i;
  }
  return weights.length - 1;
}

function paymentPicker(
  random: Random,
  weights: Record<PaymentMethod, number>,
): () => PaymentMethod {
  const methods = Object.keys(weights) as PaymentMethod[];
  return weighted(
    random,
    methods,
    methods.map(method => weights[method]),
  );
}

function reasonPicker(
  random: Random,
  weights: Partial<Record<AfterSaleReason, number>>,
): () => AfterSaleReason {
  const reasons = Object.keys(weights) as AfterSaleReason[];
  return weighted(
    random,
    reasons,
    reasons.map(reason => weights[reason]!),
  );
}

function bestCoupon(kind: 'SHOP' | 'PLATFORM', subtotal: number) {
  let best: (typeof COUPONS)[number] | undefined;
  for (const coupon of COUPONS) {
    if (
      coupon.kind === kind &&
      coupon.amount > 0 &&
      coupon.threshold * 100 <= subtotal &&
      (!best || coupon.amount > best.amount)
    ) {
      best = coupon;
    }
  }
  return best;
}

function shopOf(channel: Channel, random: Random): ShopId {
  if (channel === 'LIVE') return SHOPS[1].id;
  if (channel === 'DISTRIBUTION' && random() < 0.5) return SHOPS[2].id;
  return SHOPS[0].id;
}

/** 电子普票约 18%；增值税专票约 3%，几乎都来自分销与企业团购店。 */
function invoiceOf(
  shopId: ShopId,
  channel: Channel,
  random: Random,
): InvoiceType {
  const roll = random();
  const special =
    shopId === 'SHOP-ENTERPRISE'
      ? 0.45
      : channel === 'DISTRIBUTION'
        ? 0.08
        : 0.004;
  if (roll < special) return 'VAT_SPECIAL';
  return roll < special + 0.18 ? 'E_NORMAL' : 'NONE';
}

function tagsOf(calendar: CalendarDay, random: Random): OrderTag[] {
  const tags: OrderTag[] = [];
  const activity = calendar.activity;
  if (activity && calendar.date !== activity.end && random() < 0.06)
    tags.push('PRESALE');
  if (random() < 0.03) tags.push('URGENT');
  if (random() < (calendar.month === 2 || calendar.month === 12 ? 0.1 : 0.05))
    tags.push('GIFT');
  if (random() < 0.01) tags.push('RISK_REVIEW');
  return tags;
}

function waybillNoOf(carrier: number, sequence: number): string {
  return `${CARRIERS[carrier].prefix}${pad(310_000_000_000 + sequence * 7919, 12)}`;
}

const PASCAL = new Map<string, string>();

function pascal(name: string): string {
  let value = PASCAL.get(name);
  if (value === undefined) {
    value = name.replace(/(^|_)([a-z0-9])/g, (_, __, letter: string) =>
      letter.toUpperCase(),
    );
    PASCAL.set(name, value);
  }
  return value;
}
