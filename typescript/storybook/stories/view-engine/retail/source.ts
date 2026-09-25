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
 * 零售数据集怎样接到引擎上（docs/scenarios.md 2.7、2.8）。
 *
 * - 数据在模块级只生成一次（第一次读取时），同一页里的所有故事共享；它不可变。
 * - 每个聚合一个 `rowSource`，也只建一次：行按时间列排好序，时间范围先二分
 *   切片；相同的聚合查询在同一个数据源里只算一次。数据源只读，跨挂载共享
 *   不会串味。
 * - 引擎与视图存储每次挂载都新建（README「示例与回归」），保存、改名、删除
 *   是真写入，也不会跨场景残留。
 * - 时钟钉在 `RETAIL_NOW`（2026-09-22 10:00），时区是 Asia/Shanghai：「本月」
 *   「昨日」「近 3 个月」每次都一样，孪生故事断言的数字才是黄金值。
 * ------------------------------------------------------------------------ */

import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type ViewDefinition,
  type FieldOption,
  type OptionSource,
  type RecordData,
  type RuntimeLimits,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { MEMBER_LEVELS } from './catalog.js';
import { generateRetail, RETAIL_NOW, type RetailDataset } from './generate.js';
import { rowSource } from '../rowSource.js';

/** 零售场景的时区：日、周、月都按上海时间切。 */
export const RETAIL_ZONE = 'Asia/Shanghai';

/** 每个聚合的数据源键，也是定义里的 `source`。 */
export const RETAIL_SOURCES = {
  orders: 'trade_order',
  afterSales: 'after_sale',
  members: 'member',
  waybills: 'waybill',
  orderEvents: 'trade_order_event',
} as const;

export type RetailSourceKey =
  (typeof RETAIL_SOURCES)[keyof typeof RETAIL_SOURCES];

let dataset: RetailDataset | undefined;

/** 默认种子、showcase 规模的数据集；第一次读取时生成，之后共享。 */
export function retailData(): RetailDataset {
  dataset ??= generateRetail();
  return dataset;
}

let sources: Map<RetailSourceKey, ViewSource> | undefined;

/**
 * 每个聚合的数据源。快照按 `firstEventTime`（下单、申请、发货、注册的那一
 * 刻）排序切片，事件流按 `createTime`。
 */
export function retailSource(key: RetailSourceKey): ViewSource {
  if (!sources) {
    const data = retailData();
    const rows = (list: readonly object[]) => list as readonly RecordData[];
    sources = new Map<RetailSourceKey, ViewSource>([
      [
        RETAIL_SOURCES.orders,
        rowSource(rows(data.orders), { timeField: 'firstEventTime' }),
      ],
      [
        RETAIL_SOURCES.afterSales,
        rowSource(rows(data.afterSales), { timeField: 'firstEventTime' }),
      ],
      [
        RETAIL_SOURCES.members,
        rowSource(rows(data.members), { timeField: 'firstEventTime' }),
      ],
      [
        RETAIL_SOURCES.waybills,
        rowSource(rows(data.waybills), { timeField: 'firstEventTime' }),
      ],
      [
        RETAIL_SOURCES.orderEvents,
        rowSource(rows(data.events), { timeField: 'createTime' }),
      ],
    ]);
  }
  const source = sources.get(key);
  if (!source) throw new Error(`No retail source ${key}.`);
  return source;
}

/** 远程选项的键：买家从会员里搜。 */
export const MEMBER_OPTIONS = 'members';

const LEVEL_NAMES = new Map<string, string>(
  MEMBER_LEVELS.map(level => [level.id, level.name]),
);

/** 一个会员作为选项：昵称是掩码，重名很多，所以带上等级与会员号。 */
function memberOption(member: RetailDataset['members'][number]): FieldOption {
  const { id, nick, level, city } = member.state;
  return {
    value: id,
    label: `${nick}（${LEVEL_NAMES.get(level) ?? level} · ${city} · ${id}）`,
  };
}

/**
 * 买家的远程搜索：按昵称、会员号或城市找，一页 20 个。7000 个会员，客服搜
 * 「林」能翻到的就是这些人。
 */
export function memberOptions(): OptionSource {
  const PAGE = 20;
  return {
    async search({ query, cursor }) {
      const needle = query.trim();
      const matched = retailData().members.filter(
        ({ state }) =>
          needle === '' ||
          state.nick.includes(needle) ||
          state.id.includes(needle.toUpperCase()) ||
          state.city.includes(needle),
      );
      const start = Number(cursor ?? 0);
      const end = start + PAGE;
      return {
        items: matched.slice(start, end).map(memberOption),
        nextCursor: end < matched.length ? String(end) : null,
      };
    },
    async resolve(ids) {
      const wanted = new Set(ids.map(String));
      return retailData()
        .members.filter(({ state }) => wanted.has(state.id))
        .map(memberOption);
    },
  };
}

/** 零售场景的运行环境：钉住的「现在」与上海时区。 */
export function retailEnvironment() {
  return defaultRuntimeEnvironment({
    now: () => new Date(RETAIL_NOW),
    timeZone: RETAIL_ZONE,
  });
}

/**
 * 一个新引擎、一个新存储：`definitions` 是这个场景的定义，`instances` 是
 * 存储里已有的共享与个人视图，`limits` 是这个场景另要的上限（例如主题一览
 * 一页上几十个查询要排队）。每次挂载调用一次。
 */
export function createRetailEngine(
  definitions: ViewDefinition[],
  instances: ViewInstance[] = [],
  limits: Partial<RuntimeLimits> = {},
): ViewEngine {
  return new ViewEngine({
    definitions,
    // Wow 的查询服务一页最多 100 行；导出按运行时最大的页取，所以这里也是。
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100, ...limits },
    store: new MemoryViewStore({ instances }),
    resolveSource: key => retailSource(key as RetailSourceKey),
    resolveOptions: remote => {
      if (remote !== MEMBER_OPTIONS)
        throw new Error(`No retail options ${remote}.`);
      return memberOptions();
    },
    environment: retailEnvironment(),
  });
}
