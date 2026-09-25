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
import { useMemo, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { FilterTree } from '@ahoo-wang/wow-view-engine';
import { EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
import { BellRingIcon } from 'lucide-react';
import { Badge } from '@/ui/components/badge';
import { Button } from '@/ui/components/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/ui/components/card';
import { Separator } from '@/ui/components/separator';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { ORDER_HISTORY_VIEW, ORDER_LINES_VIEW } from './retail/boards.js';
import {
  CHANNELS,
  ORDER_STATUSES,
  PAYMENT_METHODS,
  SHOPS,
  WAREHOUSES,
} from './retail/catalog.js';
import {
  NudgeStatus,
  createBoardEngine,
  useNudges,
} from './retail/RetailHost.js';
import { RETAIL_ZONE, retailData } from './retail/source.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The order the page opens on: one of the eleven the East China sorting
 * line left unshipped (A7), paid on the evening of 09-19 and still waiting on 09-22.
 */
const ORDER_NO = 'TO2026091900032';

const ON_CARD = {
  '--fve-background': 'var(--card)',
  '--fve-dark-background': 'var(--card)',
} as CSSProperties;

const nameOf = (list: readonly { id: string; name: string }[], id: unknown) =>
  list.find(item => item.id === id)?.name ?? String(id);
const money = new Intl.NumberFormat(HOST_LANGUAGE.locale, {
  style: 'currency',
  currency: 'CNY',
});
const moment = new Intl.DateTimeFormat(HOST_LANGUAGE.locale, {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: RETAIL_ZONE,
});

/**
 * 订单详情页（docs/scenarios.md 4.1「嵌入页」）：订单头是宿主自己画的——它从
 * 自己的订单接口读这一张单；下面两块是视图引擎的 `EmbeddedView`：这张单的商品
 * 行（只读的表，展开 `items`），与这张单的事件流（模板视图按订单号填写、按
 * 版本排序）。两块都由页面收窄到这张单（`scopeFilter`），读者拿不掉。
 */
function OrderDetail({ orderNo }: { orderNo: string }) {
  const order = useMemo(
    () => retailData().orders.find(({ state }) => state.orderNo === orderNo),
    [orderNo],
  );
  const nudges = useNudges();
  if (!order) return <p>没有订单 {orderNo}。</p>;
  const { state } = order;
  const byOrder: FilterTree = {
    op: 'and',
    children: [{ field: 'state.orderNo', operator: 'EQ', value: orderNo }],
  };
  const byStream: FilterTree = {
    op: 'and',
    children: [{ field: 'aggregateId', operator: 'EQ', value: orderNo }],
  };
  const overdue = state.status === 'PAID' && state.shipSlaBreached;
  const facts: [string, string][] = [
    ['下单时间', moment.format(order.firstEventTime)],
    [
      '付款',
      state.timing.paidAt === null
        ? '未付款'
        : `${moment.format(state.timing.paidAt)} · ${nameOf(PAYMENT_METHODS, state.payment.method)}`,
    ],
    [
      '发货期限',
      state.timing.shipDueAt === null
        ? '—'
        : moment.format(state.timing.shipDueAt),
    ],
    ['店铺', nameOf(SHOPS, state.shopId)],
    ['渠道', nameOf(CHANNELS, state.channel)],
    ['发货仓', nameOf(WAREHOUSES, state.warehouse)],
    ['买家', `${state.buyer.nick}（${state.buyer.id}）`],
    [
      '收货',
      [state.address.province, state.address.city, state.address.district]
        .filter(Boolean)
        .join(' '),
    ],
    ['应付', money.format(state.amounts.payableAmount)],
    ['实付', money.format(state.amounts.paidAmount)],
  ];
  return (
    <div
      data-host-page
      className="fve-tokens bg-background text-foreground flex min-w-0 flex-col gap-4"
    >
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <p className="text-muted-foreground text-xs">
            订单中心 / 订单 / {orderNo}
          </p>
          <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold">
            订单 {orderNo}
            <Badge variant={overdue ? 'destructive' : 'secondary'}>
              {nameOf(ORDER_STATUSES, state.status)}
              {overdue && ' · 发货超时'}
            </Badge>
          </h1>
        </div>
        {overdue && (
          <Button
            variant="outline"
            size="sm"
            disabled={nudges.nudged.has(orderNo)}
            onClick={() =>
              nudges.nudge([{ orderNo, warehouse: state.warehouse }])
            }
          >
            <BellRingIcon data-icon="inline-start" />
            {nudges.nudged.has(orderNo) ? '已催' : '催发货'}
          </Button>
        )}
      </div>
      <NudgeStatus nudges={nudges} />
      <Separator />
      <dl className="grid min-w-0 grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-x-6 gap-y-3">
        {facts.map(([label, value]) => (
          <div key={label} className="flex min-w-0 flex-col gap-0.5">
            <dt className="text-muted-foreground text-xs">{label}</dt>
            <dd className="text-sm tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
      <StoryEngine create={() => createBoardEngine()}>
        {engine => (
          <div className="grid min-w-0 gap-4 xl:grid-cols-2">
            <Card className="min-w-0" style={ON_CARD}>
              <CardHeader>
                <CardTitle>商品</CardTitle>
                <CardDescription>这张单的每一行，含已退金额。</CardDescription>
              </CardHeader>
              <CardContent className="min-w-0">
                <EmbeddedView
                  className="host-embed"
                  engine={engine}
                  instanceId={ORDER_LINES_VIEW}
                  scopeFilter={byOrder}
                  headingLevel={3}
                  {...HOST_LANGUAGE}
                />
              </CardContent>
            </Card>
            <Card className="min-w-0" style={ON_CARD}>
              <CardHeader>
                <CardTitle>订单历史</CardTitle>
                <CardDescription>
                  这张单的事件流，按版本从早到晚；没有「包裹发出」，就是还没发货。
                </CardDescription>
              </CardHeader>
              <CardContent className="min-w-0">
                <EmbeddedView
                  className="host-embed"
                  engine={engine}
                  instanceId={ORDER_HISTORY_VIEW}
                  scopeFilter={byStream}
                  interaction="interactive"
                  headingLevel={3}
                  {...HOST_LANGUAGE}
                />
              </CardContent>
            </Card>
          </div>
        )}
      </StoryEngine>
    </div>
  );
}

const FIXTURE = '内存 ViewStore · 栖木生活 2 万张子订单（示例数据）';

const description = `**业务场景 · 订单详情页**

宿主自己的订单详情页，嵌两块视图（\`EmbeddedView\`）。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-22 上午 10 点。默认打开的是华东（嘉兴）仓分拣线故障卡住的那 11 张单里的一张（A7）；运营日报 → 超时明细 → 「在工作台中打开」 → 行上的「订单详情」就走到这里，地址里带着订单号（\`args=orderNo:…\`）。
- **订单头**：宿主从自己的订单接口读、自己画；发货超时时有宿主的「催发货」。
- **商品**：只读的表（\`static\` 一档）：展开 \`items\`，每个商品一行，带合计行。
- **订单历史**：事件流的模板视图，由页面填上这张单的订单号（\`scopeFilter\`），按版本排序；可交互一档，能排序、翻页。这张单只有「下单」「付款」两条——包裹一直没发出。`;

const meta = {
  title: 'View Engine/业务场景/订单详情页',
  component: OrderDetail,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
    // Two views embedded on one page each draw their 「正在显示」 band, and
    // the engine names both regions alike (scenarios.md, engine gaps: a host
    // cannot tell two embeds' landmarks apart). Every other rule stays on.
    a11y: { config: { rules: [{ id: 'landmark-unique', enabled: false }] } },
  },
  args: { orderNo: ORDER_NO },
  argTypes: {
    orderNo: { control: 'text', description: '订单号，例如 TO2026092100011。' },
  },
  decorators: [
    Story => (
      <AppShell current="order-detail" service={{ fixture: FIXTURE }} padded>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof OrderDetail>;

export default meta;

type Story = StoryObj<typeof meta>;

export const OrderDetailPage: Story = { name: '订单详情页' };
