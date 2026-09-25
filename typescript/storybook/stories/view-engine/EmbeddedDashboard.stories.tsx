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
import { useState, type CSSProperties } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type {
  DashboardFilters,
  ViewNavigation,
  ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import { EmbeddedDashboard } from '@ahoo-wang/wow-view-engine/ui';
// View Engine's own primitives, so the mock host pages are composed rather
// than hand-styled; they paint inside the page's `fve-tokens` boundary.
import { Badge } from '@/ui/components/badge';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/ui/components/card';
import { Separator } from '@/ui/components/separator';
import { AppShell } from '../shared/AppShell.js';
import {
  CUSTOMER,
  CUSTOMER_PAGE,
  EMBED_ENVIRONMENT,
  boardWithAPanelOut,
  customerBoard,
  customerViews,
  wallBoard,
} from './embeddedBoards.js';
import { HOST_LANGUAGE, createStoryEngine, savedViews } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 一块已经存好的仪表盘，嵌在宿主自己的业务页面里（D22 嵌入一半）。
 *
 * 与 `EmbeddedView` 按资源分开：宿主嵌一块板就用 `EmbeddedDashboard`。它有明
 * 确的一档交互——`static` 只看、`interactive` 能改筛选、点一组、铺满屏幕——
 * 两档都不写任何东西（D36）：搭板子、保存在 `DashboardWorkbench` 里。每个筛
 * 选各自三态：可调（`adjustable`，在筛选条上、
 * 归读者）、锁定（在筛选条上读作它的值，改不了）、隐藏（不在筛选条上，照样收
 * 窄接上的面板）。读者的筛选值是宿主的地址：`initialFilters` 进、
 * `onFiltersChange` 出；锁定与隐藏的值是页面自己的（`pageValues`），从不进地
 * 址。包本身从不碰地址。
 *
 * 锁定不是安全边界：条件是在浏览器里拼进查询的，租户、归属与权限必须由 Wow 后
 * 端强制。
 */

/** 左栏那几条事实，纯静态。 */
const CUSTOMER_FACTS: [string, string][] = [
  ['客户编号', CUSTOMER.id.toUpperCase()],
  ['联系人', '李娜'],
  ['信用额度', '¥ 150,000'],
  ['结算方式', '月结 30 天'],
];

/** An embed on a card paints the card's colour (see `EmbeddedView` stories). */
const ON_CARD = {
  '--fve-background': 'var(--card)',
  '--fve-dark-background': 'var(--card)',
} as CSSProperties;

/** The host's address, as a query string a person could read. */
function addressOf(filters: DashboardFilters): string {
  return `?filters=${encodeURIComponent(JSON.stringify(filters.values))}`;
}

/** Where the host's route last went, as the page says it. */
function routeOf(to: ViewNavigation | null): string {
  if (!to) return '（还没去过哪里）';
  if (to.kind === 'url') return to.url;
  // What the page holds goes as the scope, what the reader set as the
  // view's own conditions (D26 Q30).
  if (to.kind === 'view')
    return `打开视图 ${to.instanceId} · 作用域 ${JSON.stringify(to.scopeFilter)} · 条件 ${JSON.stringify(to.filter)}`;
  if (to.kind === 'dashboard')
    return `打开仪表盘 ${to.instanceId} · ${JSON.stringify(to.filters.values)}`;
  return `打开「${to.title}」 · 作用域 ${JSON.stringify(to.scopeFilter)} · 条件 ${JSON.stringify(to.config.filter)}`;
}

/**
 * 客户详情页：嵌一块「客户订单」板，可交互。客户由页面锁定成这一页的客户，下
 * 单时间归读者、经宿主的地址来回（客户不进地址，它是页面自己的）；追问与「在工作台中打开」经宿主的路由。
 */
function CustomerPage({ engine }: { engine: ViewEngine }) {
  // The reader's filters, as the host's address keeps them; the customer
  // is the page's own and never goes into it.
  const [address, setAddress] = useState<DashboardFilters>({ values: {} });
  const [route, setRoute] = useState<ViewNavigation | null>(null);
  return (
    <div
      data-host-page
      className="fve-tokens bg-background text-foreground flex min-h-0 flex-col gap-4"
    >
      <div className="flex min-w-0 flex-col gap-0.5">
        <p className="text-muted-foreground text-xs">
          订单中心 / 客户 / {CUSTOMER.label}
        </p>
        <h1 className="truncate text-base font-semibold">
          {CUSTOMER.label} · 客户详情
        </h1>
      </div>
      <Separator />
      <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(180px,220px)_minmax(0,1fr)]">
        <Card>
          <CardHeader>
            <CardTitle>客户资料</CardTitle>
            <CardAction>
              <Badge variant="secondary">月结</Badge>
            </CardAction>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {CUSTOMER_FACTS.map(([label, value]) => (
              <div key={label} className="flex flex-col gap-0.5">
                <span className="text-muted-foreground text-xs">{label}</span>
                <span className="text-sm">{value}</span>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card className="min-w-0" style={ON_CARD}>
          <CardHeader>
            <CardTitle>订单</CardTitle>
            <CardDescription>
              这块板锁定在这位客户上；下单时间可以换。
            </CardDescription>
          </CardHeader>
          <CardContent className="flex min-w-0 flex-col gap-3">
            <EmbeddedDashboard
              className="host-embed"
              engine={engine}
              instanceId={customerBoard.id}
              interaction="interactive"
              withExport
              expandable
              filterModes={{ customer: 'locked' }}
              pageValues={CUSTOMER_PAGE}
              initialFilters={address}
              onFiltersChange={setAddress}
              onNavigate={setRoute}
              {...HOST_LANGUAGE}
            />
          </CardContent>
        </Card>
      </div>
      <Separator />
      <dl className="text-muted-foreground grid gap-1 text-xs">
        <div className="flex gap-2">
          <dt>宿主地址</dt>
          <dd data-host-address className="min-w-0 font-mono break-all">
            {addressOf(address)}
          </dd>
        </div>
        <div className="flex gap-2">
          <dt>宿主路由</dt>
          <dd data-host-route className="min-w-0 font-mono break-all">
            {routeOf(route)}
          </dd>
        </div>
      </dl>
    </div>
  );
}

/**
 * 大屏：一整页只有这块板，暗色、全只读、铺满，每分钟自己刷新。仓库由页面锁定
 * 成这块屏所在的华东仓。
 */
function WallScreen({ engine }: { engine: ViewEngine }) {
  return (
    // The wall is dark whatever the host application is: the class sits on
    // an ancestor, which is what both style boundaries read the mode from.
    <div className="dark">
      <div
        data-host-page
        className="fve-tokens bg-background text-foreground flex h-[720px] min-h-0 flex-col gap-3 rounded-lg p-4"
      >
        <div className="flex items-baseline justify-between gap-2">
          <h1 className="text-lg font-semibold">华东仓 · 出库大屏</h1>
          <span className="text-muted-foreground text-xs">每分钟刷新</span>
        </div>
        <div data-wall className="min-h-0 flex-1">
          <EmbeddedDashboard
            className="host-embed"
            engine={engine}
            instanceId={wallBoard.id}
            size="fill"
            withTitle
            filterModes={{ region: 'locked' }}
            pageValues={{ values: { region: ['CN-EAST'] } }}
            {...HOST_LANGUAGE}
          />
        </div>
      </div>
    </div>
  );
}

/** A board one of whose panels is out, on the customer page's card. */
function PanelOutPage({ engine }: { engine: ViewEngine }) {
  return (
    <div data-host-page className="fve-tokens bg-background text-foreground">
      <Card className="min-w-0" style={ON_CARD}>
        <CardHeader>
          <CardTitle>出库概览</CardTitle>
          <CardDescription>
            一块共享仪表盘，其中一个面板指向的视图已经删了。
          </CardDescription>
        </CardHeader>
        <CardContent className="min-w-0">
          <EmbeddedDashboard
            className="host-embed"
            engine={engine}
            instanceId={boardWithAPanelOut.id}
            {...HOST_LANGUAGE}
          />
        </CardContent>
      </Card>
    </div>
  );
}

type Scene = 'customer' | 'wall' | 'panel-out';

function EmbeddedDashboardDemo({ scene }: { scene: Scene }) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          instances: [
            ...savedViews,
            ...customerViews,
            customerBoard,
            wallBoard,
            boardWithAPanelOut,
          ],
          environment: EMBED_ENVIRONMENT,
        })
      }
    >
      {engine =>
        scene === 'customer' ? (
          <CustomerPage engine={engine} />
        ) : scene === 'wall' ? (
          <WallScreen engine={engine} />
        ) : (
          <PanelOutPage engine={engine} />
        )
      }
    </StoryEngine>
  );
}

const FIXTURE = '内存 ViewStore · 六条订单 · 四块共享仪表盘';

const description = `**仪表盘视图 · 嵌入仪表盘**

一块已经存好的仪表盘，摆进宿主自己的页面：交互一档、筛选三态。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-18 上午（Asia/Shanghai），「本月」每次都一样。
- **准备**：每次挂载都新建引擎与存储。
- **操作**：客户详情页 \`interactive\`——客户锁定、下单时间可改、点一组经宿主路由追问、右上角「铺满屏幕」；大屏 \`static\`、铺满容器；第三个场景里一个面板出不来。两档都不写任何东西：没有「编辑」、保存与另存为，搭板子在仪表盘工作台里。
- **观察**：锁定的筛选读作它的值、没有控件；读者的筛选值在页脚的「宿主地址」里来回，锁定的客户不在里面；锁定不是安全边界——租户、归属与权限归 Wow 后端。`;

const meta = {
  title: 'View Engine/仪表盘视图/EmbeddedDashboard',
  component: EmbeddedDashboardDemo,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell
        current="embedded-dashboard"
        service={{ fixture: FIXTURE }}
        padded
      >
        <Story />
      </AppShell>
    ),
  ],
  args: { scene: 'customer' },
  argTypes: { scene: { table: { disable: true } } },
} satisfies Meta<typeof EmbeddedDashboardDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 客户详情页，`interactive` 一档（D22、D36）。
 *
 * 筛选条上「客户」是锁定的：读作「晨光食品」、带一把锁、没有控件；「下单时间」
 * 是读者的，默认本月。点「按仓库金额」的一行弹出追问菜单，「查看这些记录」经
 * 宿主的路由打开——带着这位客户与这段时间。页脚的「宿主地址」跟着下单时间变，锁定的客户从不进去。
 * 页面打开了导出（`withExport`）：「这个客户的订单」的「⋯」里有「导出数据…」，
 * 导出的就是这位客户、这段时间的订单。页面还打开了 `expandable`：右上角「铺满
 * 屏幕」把这块板就地铺开，Esc 收起。这些都只影响这一次观看，什么也不存。
 */
export const CustomerDetail: Story = {
  name: '客户详情页（interactive）',
  args: { scene: 'customer' },
};

/**
 * 大屏，`static` 一档（D22、D36）：一整页只有这块板，暗色，铺满容器，标题画出来，
 * 仓库锁定在华东仓。点什么都不会弹菜单、不会联动、没有「⋯」、没有「编辑」；
 * 看板每分钟自己刷新。
 */
export const WallScreenStatic: Story = {
  name: '大屏（static）',
  args: { scene: 'wall' },
};

/**
 * 嵌一块有面板出不来的仪表盘：栅格照常画，出不来的那一个在自己的框里说为什
 * 么、找谁（R3）。
 */
export const DashboardWithAPanelOut: Story = {
  name: '有面板出不来',
  args: { scene: 'panel-out' },
};
