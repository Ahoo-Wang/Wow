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
 * 栖木生活运营后台（宿主）自己的那几样东西：离开一块板去哪（宿主的路由），
 * 订单上的业务动作「催发货」与「订单详情」，以及宿主页头。视图引擎只是中间那
 * 一块；这些属于宿主，所以用宿主的标记画，经 `fve-tokens` 读主题（D17-10）。
 * ------------------------------------------------------------------------ */

import { useCallback, useMemo, useState, type ReactNode } from 'react';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type DashboardFilters,
  type RecordRow,
  type ViewNavigation,
  type ViewSource,
  type ViewStore,
} from '@ahoo-wang/wow-view-engine';
import type { RecordActionSlots } from '@ahoo-wang/wow-view-engine/react';
import {
  DashboardWorkbench,
  DataWorkbench,
} from '@ahoo-wang/wow-view-engine/ui';
import { ArrowLeftIcon, BellRingIcon, FileTextIcon } from 'lucide-react';
import { Button, buttonVariants } from '@/ui/components/button';
import { HOST_LANGUAGE } from '../fixtures.js';
import { StoryEngine } from '../StoryEngine.js';
import {
  OPS_DAILY,
  RETAIL_BOARDS,
  RETAIL_BOARD_DEFINITIONS,
  SALES_REVIEW,
  retailInstances,
} from './boards.js';
import { WAREHOUSES } from './catalog.js';
import {
  MEMBER_OPTIONS,
  memberOptions,
  retailData,
  retailEnvironment,
  retailSource,
  type RetailSourceKey,
} from './source.js';
import { RETAIL_ORDERS } from './views.js';

// ---------------------------------------------------------------- 引擎

export interface BoardEngineOptions {
  /** 换掉某几个聚合的数据源：「加载中」「一个面板出错」「没有数据」的变体。 */
  sources?: Partial<Record<RetailSourceKey, ViewSource>>;
  store?: ViewStore;
}

/**
 * 一台板子引擎：第 3 批的五个定义与仪表盘定义、共享的数据源（`source.ts`），
 * 这次挂载新建的存储，时钟钉在数据集的「现在」。
 */
export function createBoardEngine(
  options: BoardEngineOptions = {},
): ViewEngine {
  return new ViewEngine({
    definitions: RETAIL_BOARD_DEFINITIONS,
    // 与真实的 Wow 服务一样，一页最多 100 行。
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: options.store ?? new MemoryViewStore({ instances: retailInstances }),
    resolveSource: key =>
      options.sources?.[key as RetailSourceKey] ??
      retailSource(key as RetailSourceKey),
    resolveOptions: remote => {
      if (remote !== MEMBER_OPTIONS)
        throw new Error(`No retail options ${remote}.`);
      return memberOptions();
    },
    environment: retailEnvironment(),
  });
}

// ---------------------------------------------------------------- 订单详情页的地址

/**
 * 订单详情页（`OrderDetail.stories.tsx`）。地址里带着要看的那张单：Storybook
 * 从 `args=orderNo:…` 读它。链接相对于 `iframe.html` 所在的目录（见 AppShell）。
 */
export const ORDER_DETAIL_STORY =
  './?path=/story/view-engine-业务场景-订单详情页--order-detail-page';

export function orderDetailHref(orderNo: string): string {
  return `${ORDER_DETAIL_STORY}&args=orderNo:${orderNo}`;
}

// ---------------------------------------------------------------- 催发货

/** 一行的某个字段：记录是嵌套的快照，字段名是点号路径。 */
export function valueAt(data: unknown, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (value, key) =>
        value !== null && typeof value === 'object'
          ? (value as Record<string, unknown>)[key]
          : undefined,
      data,
    );
}

const WAREHOUSE_NAMES = new Map<string, string>(
  WAREHOUSES.map(({ id, name }) => [id, name]),
);

/**
 * 宿主的「催发货」：给仓库发一条加急提醒。示例里不真的发出，只记下催过哪几张
 * 单，并说一句结果——这是宿主的业务命令，视图引擎不知道、也不存它。
 */
export interface Nudges {
  nudged: ReadonlySet<string>;
  message: string | null;
  nudge(orders: readonly { orderNo: string; warehouse: string }[]): void;
}

export function useNudges(): Nudges {
  const [nudged, setNudged] = useState<ReadonlySet<string>>(new Set());
  const [message, setMessage] = useState<string | null>(null);
  const nudge = useCallback(
    (orders: readonly { orderNo: string; warehouse: string }[]) => {
      if (orders.length === 0) {
        setMessage('没有需要催的单。');
        return;
      }
      setNudged(before => new Set([...before, ...orders.map(o => o.orderNo)]));
      const houses = [
        ...new Set(
          orders.map(o => WAREHOUSE_NAMES.get(o.warehouse) ?? o.warehouse),
        ),
      ].join('、');
      setMessage(
        `已通知${houses}仓加急处理 ${orders.length} 张单（示例数据，没有真的发出）。`,
      );
    },
    [],
  );
  return useMemo(() => ({ nudged, message, nudge }), [nudged, message, nudge]);
}

/**
 * 付款超过 48 小时仍未发货的单，宿主从自己的订单接口读到的——与订单工作台的
 * 「发货超时」同一个口径：待发货或部分发货、超时、不是预售。
 */
export function overdueOrders(): { orderNo: string; warehouse: string }[] {
  return retailData()
    .orders.filter(
      ({ state }) =>
        WAITING.includes(state.status) &&
        state.shipSlaBreached &&
        !state.tags.includes('PRESALE'),
    )
    .map(({ state }) => ({
      orderNo: state.orderNo,
      warehouse: state.warehouse,
    }));
}

/** 还没发完货、可以催的订单状态。 */
const WAITING: readonly string[] = ['PAID', 'PARTIALLY_SHIPPED'];

function orderOf(row: RecordRow): { orderNo: string; warehouse: string } {
  return {
    orderNo: String(row.key),
    warehouse: String(valueAt(row.data, 'state.warehouse') ?? ''),
  };
}

/**
 * 订单上的宿主动作：每一行「订单详情」与（还在待发货的）「催发货」，勾选几行时
 * 成批「催发货」。按下之后那一行读作「已催」，同一张单不催第二次。
 */
export function orderActions(nudges: Nudges): RecordActionSlots {
  return {
    bulk: ({ rows }) => {
      const waiting = rows.filter(row =>
        WAITING.includes(String(valueAt(row.data, 'state.status'))),
      );
      return (
        <Button
          variant="outline"
          size="sm"
          disabled={waiting.length === 0}
          onClick={() => nudges.nudge(waiting.map(orderOf))}
        >
          <BellRingIcon data-icon="inline-start" />
          催发货
        </Button>
      );
    },
    row: ({ row }) => {
      const order = orderOf(row);
      const waiting = WAITING.includes(
        String(valueAt(row.data, 'state.status')),
      );
      const done = nudges.nudged.has(order.orderNo);
      return (
        <>
          {waiting && (
            <Button
              variant="ghost"
              size="xs"
              disabled={done}
              aria-label={
                done ? `已催 ${order.orderNo}` : `催发货 ${order.orderNo}`
              }
              onClick={() => nudges.nudge([order])}
            >
              {done ? '已催' : '催发货'}
            </Button>
          )}
          <a
            className={buttonVariants({ variant: 'ghost', size: 'xs' })}
            href={orderDetailHref(order.orderNo)}
            target="_top"
            aria-label={`订单详情 ${order.orderNo}`}
          >
            <FileTextIcon data-icon="inline-start" />
            订单详情
          </a>
        </>
      );
    },
  };
}

/** 宿主说「催发货」的结果：一句只读的状态，读屏也听得到。 */
export function NudgeStatus({ nudges }: { nudges: Nudges }) {
  return (
    <p
      role="status"
      data-host-status
      // Mounted from the start, so a screen reader hears what lands in
      // it; empty, it takes no room on the page.
      className="text-muted-foreground text-sm empty:sr-only"
    >
      {nudges.message}
    </p>
  );
}

// ---------------------------------------------------------------- 宿主的路由

/** 读者那一份筛选与标签页，宿主把它们放在自己的地址里。 */
export interface BoardReader {
  initialFilters: DashboardFilters | undefined;
  onFiltersChange(filters: DashboardFilters): void;
  initialTab: string | null | undefined;
  onTabChange(tab: string | null): void;
  onNavigate(to: ViewNavigation): void;
}

/**
 * 一块板与离开它的每一条路（D22 H、I，D26 Q30、Q33）。宿主只有一个路由：
 * - 视图（点一组的追问、面板的「在工作台中打开」、点击去另一个视图）：宿主的
 *   订单工作台（`DataWorkbench`），订单上挂着宿主的动作；工作台自己画「返回
 *   〈仪表盘〉」，按下回到这块板，筛选与标签页是离开时的样子；
 * - 另一块板：那块板的工作台，带着点击映射过去的筛选。从日报的「退款率最高
 *   的商品」过来的，落在销售复盘的「品类」页——点击本身说不出目标板的标签页
 *   （见 scenarios.md 的引擎缺口），这一步由宿主补上；
 * - 网址：宿主自己的页面。
 */
export function RoutedBoard({
  engine,
  home,
  board,
  nudges,
}: {
  engine: ViewEngine;
  /** 这一页的那块板。 */
  home: string;
  board(reader: BoardReader): ReactNode;
  nudges: Nudges;
}) {
  const [away, setAway] = useState<ViewNavigation | null>(null);
  const [filters, setFilters] = useState<DashboardFilters | undefined>();
  const [tab, setTab] = useState<string | null | undefined>();
  const route = useCallback(
    (to: ViewNavigation) => {
      if (to.kind === 'dashboard' && to.instanceId === home) {
        setFilters(to.filters);
        setTab(to.tab);
        setAway(null);
      } else setAway(to);
    },
    [home],
  );
  const actions = useMemo(() => orderActions(nudges), [nudges]);
  if (away === null)
    return board({
      initialFilters: filters,
      onFiltersChange: setFilters,
      initialTab: tab,
      onTabChange: setTab,
      onNavigate: route,
    });
  if (away.kind === 'view' || away.kind === 'unsaved')
    return (
      <div className="flex h-full min-h-0 flex-col" data-host-route="workbench">
        <DataWorkbench
          engine={engine}
          definitionId={away.definitionId}
          handOver={away}
          onNavigate={route}
          record={
            away.definitionId === RETAIL_ORDERS
              ? { actions, emptyTitle: '没有这样的订单' }
              : undefined
          }
          {...HOST_LANGUAGE}
        />
      </div>
    );
  return (
    <div
      className="flex h-full min-h-0 flex-col gap-3"
      data-host-route={away.kind}
    >
      <Button
        variant="outline"
        size="sm"
        className="self-start"
        data-slot="host-back"
        onClick={() => setAway(null)}
      >
        <ArrowLeftIcon data-icon="inline-start" />
        {home === OPS_DAILY ? '回到运营日报' : '回到上一块板'}
      </Button>
      {away.kind === 'url' ? (
        <p data-slot="host-page">宿主页面：{away.url}</p>
      ) : (
        <DashboardWorkbench
          key={away.instanceId}
          engine={engine}
          definitionId={away.definitionId}
          instanceId={away.instanceId}
          initialFilters={away.filters}
          initialTab={
            away.instanceId === SALES_REVIEW && home === OPS_DAILY
              ? 'category'
              : undefined
          }
          onNavigate={route}
          {...HOST_LANGUAGE}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------- 一块板的场景

/**
 * 一块板放在宿主的页面区里，由 `DashboardWorkbench` 打开：读、搭、存都在这里
 * （嵌入一律不写，D36）。引擎与存储每次挂载都新建，所以故事里搭的、存的都是
 * 真写入，也不会留到下一个故事。
 */
export function RetailBoardScene({
  instanceId,
  initialTab,
  initialFilters,
}: {
  instanceId: string;
  initialTab?: string;
  initialFilters?: DashboardFilters;
}) {
  const nudges = useNudges();
  return (
    <StoryEngine create={() => createBoardEngine()}>
      {engine => (
        <div className="fve-tokens flex h-full min-h-0 flex-col">
          <NudgeStatus nudges={nudges} />
          <RoutedBoard
            engine={engine}
            home={instanceId}
            nudges={nudges}
            board={reader => (
              <DashboardWorkbench
                engine={engine}
                definitionId={RETAIL_BOARDS}
                instanceId={instanceId}
                initialFilters={reader.initialFilters ?? initialFilters}
                onFiltersChange={reader.onFiltersChange}
                initialTab={reader.initialTab ?? initialTab}
                onTabChange={reader.onTabChange}
                onNavigate={reader.onNavigate}
                // The board is what the page is about: the view list starts
                // folded, so a 1280 screen lays the board out wide.
                defaultSidebarOpen={false}
                {...HOST_LANGUAGE}
              />
            )}
          />
        </div>
      )}
    </StoryEngine>
  );
}
