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
import { useMemo, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  MemoryViewStore,
  ViewStoreError,
  type ViewInstance,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import { EmbeddedDashboard } from '@ahoo-wang/wow-view-engine/ui';
import { BellRingIcon } from 'lucide-react';
import { Badge } from '@/ui/components/badge';
import { Button } from '@/ui/components/button';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { OPS_DAILY, retailInstances } from './retail/boards.js';
import { rowSource } from './rowSource.js';
import {
  NudgeStatus,
  RoutedBoard,
  createBoardEngine,
  orderPanels,
  overdueOrders,
  useNudges,
} from './retail/RetailHost.js';
import {
  RETAIL_SOURCES,
  RETAIL_ZONE,
  retailEnvironment,
} from './retail/source.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import sceneSource from './Home.stories.tsx?raw';
import hostShell from './retail/RetailHost.tsx?raw';
import { hostSource } from './hostSource.js';

/**
 * The host application's home page: 栖木生活's operations daily report
 * (docs/scenarios.md 4.1, 6.3), embedded as a report.
 *
 * It is the first screen anyone opening the catalog sees, so it answers
 * what View Engine looks like inside a real operations back office: the
 * host's own shell and actions, and the board in the middle.
 *
 * The board is `EmbeddedDashboard` in the interactive tier — read-only by
 * construction (D36): no 「编辑」, no save and no save-as are on it at all,
 * and the panel menus hold only what reads. The reader still sets the
 * filters, searches the overdue list, presses a channel to filter the board
 * by it, opens a panel in the host's workbench and fills the screen with
 * the board (`expandable`) — for this viewing alone. Building the board is
 * the 「业务场景/运营日报」 story's, in `DashboardWorkbench`.
 */

/** Which state the page is shown in (6.3「状态齐全」). */
export type HomeState =
  'data' | 'loading' | 'panel-error' | 'forbidden' | 'empty';

/** A source that never answers: the board as it opens on a slow network. */
const PENDING: ViewSource = {
  paged: () => new Promise(() => {}),
  cursor: () => new Promise(() => {}),
  aggregate: () => new Promise(() => {}),
};

/** The after-sales service refusing every query: one card fails, the rest run. */
const AFTER_SALES_DOWN: ViewSource = {
  paged: () => Promise.reject(new Error('售后服务暂时不可用（503）')),
  cursor: () => Promise.reject(new Error('售后服务暂时不可用（503）')),
  aggregate: () => Promise.reject(new Error('售后服务暂时不可用（503）')),
};

/** Every aggregate's source, set to `source`. */
const everySource = (source: () => ViewSource) =>
  Object.fromEntries(Object.values(RETAIL_SOURCES).map(key => [key, source()]));

/**
 * A store that refuses this reader the board: the operations team shares it
 * with the team, and this reader is not on it.
 */
class NoAccessStore extends MemoryViewStore {
  override async get(id: string): Promise<ViewInstance> {
    if (id === OPS_DAILY)
      throw new ViewStoreError('FORBIDDEN', 'Not shared with this reader.');
    return super.get(id);
  }
}

function engineFor(state: HomeState) {
  switch (state) {
    case 'loading':
      return createBoardEngine({ sources: everySource(() => PENDING) });
    case 'panel-error':
      return createBoardEngine({
        sources: { [RETAIL_SOURCES.afterSales]: AFTER_SALES_DOWN },
      });
    case 'forbidden':
      return createBoardEngine({
        store: new NoAccessStore({ instances: retailInstances }),
      });
    case 'empty':
      // A shop on its first morning: every aggregate answers, and none has a row.
      return createBoardEngine({ sources: everySource(() => rowSource([])) });
    default:
      return createBoardEngine();
  }
}

const formatDay = new Intl.DateTimeFormat(HOST_LANGUAGE.locale, {
  dateStyle: 'full',
  timeZone: RETAIL_ZONE,
});
const formatMoment = new Intl.DateTimeFormat(HOST_LANGUAGE.locale, {
  month: 'long',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: RETAIL_ZONE,
});

function HomePage({ state }: { state: HomeState }) {
  // When the page began, so the regression twin can time the whole board —
  // the data set generated, the engine built, every panel drawn.
  const [startedAt] = useState(() => performance.now());
  const nudges = useNudges();
  // 「催发货」 on the overdue list's rows, one or several (D39): the host's
  // command, run by the host; the board writes nothing (D36).
  const recordPanel = useMemo(() => orderPanels(nudges), [nudges]);
  const now = retailEnvironment().now();
  const yesterday = new Date(now.getTime() - 86_400_000);
  // The host's own order service, which the overdue list on the board reads
  // too: the count on its action is the host's, not a number off the board.
  const overdue = state === 'empty' ? [] : overdueOrders();
  return (
    <div
      data-host-page
      data-started-at={startedAt}
      // The host's markup, painted from View Engine's tokens as the shell is
      // (D17-10); the page area around it gives the gutter.
      className="fve-tokens bg-canvas text-foreground flex min-w-0 flex-col gap-3"
    >
      {/* Not a `header`: the shell's bar is the page's one banner. */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="text-xl font-semibold">运营日报</h1>
          <p className="text-muted-foreground text-sm">
            栖木生活全渠道 · 指标卡读
            <span data-host-report-day>{formatDay.format(yesterday)}</span>
            （昨日），较前一日 · 数据截至 {formatMoment.format(now)}
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          disabled={overdue.length === 0}
          onClick={() => nudges.nudge(overdue)}
        >
          <BellRingIcon data-icon="inline-start" />
          催发货
          <Badge variant="secondary">超时 {overdue.length} 单</Badge>
        </Button>
      </div>
      <NudgeStatus nudges={nudges} />
      <StoryEngine create={() => engineFor(state)}>
        {engine => (
          <RoutedBoard
            engine={engine}
            home={OPS_DAILY}
            nudges={nudges}
            board={reader => (
              <EmbeddedDashboard
                className="host-home"
                engine={engine}
                instanceId={OPS_DAILY}
                interaction="interactive"
                expandable
                recordPanel={recordPanel}
                {...reader}
                {...HOST_LANGUAGE}
              />
            )}
          />
        )}
      </StoryEngine>
    </div>
  );
}

/** What the page answers from, said in the host's service line and below. */
const FIXTURE = '内存 ViewStore · 栖木生活 2 万张子订单（示例数据）';

const description = `**首页 · 运营日报**

宿主应用打开时的那一页：栖木生活的运营日报，一块嵌入的只读仪表盘（docs/scenarios.md 4.1、6.3）。

- **数据源**：${FIXTURE}，按种子在浏览器里生成；时钟钉在 2026-09-22 上午 10 点（Asia/Shanghai），「昨日」就是 9 月 21 日。
- **准备**：每次挂载都新建引擎与存储。板子是运营组共享的「运营日报」，由 \`EmbeddedDashboard\` 以 \`interaction="interactive"\` 嵌入。
- **操作**：顶栏、左侧导航、「运营日报」标题与「催发货」是宿主的；下面整块是视图引擎。改「日期」「渠道」「店铺」，或在「搜索订单」里打订单号、买家昵称、商品名；点「渠道分布」的一根柱把整块板筛到那个渠道；「付款超过 48 小时仍未发货」的「⋯ → 在工作台中打开」进宿主的订单工作台，每行有「催发货」与「订单详情」；右上角「铺满屏幕」。这些只影响这一次观看，什么也不存——嵌入一律不写（D36），没有「编辑」、保存与另存为。
- **观察**：9 月 21 日华东（嘉兴）仓分拣线故障（A7）：「发货及时率」掉到约 82%，低于 95% 的目标；明细里有 11 张付款超过 48 小时仍未发出的单，全在华东仓。「退款率最高的 5 个商品」里竹纤维浴巾排在前面（A1）；点它进销售复盘的「品类」页，日期一并带过去。
- **状态**：「加载中」「一个面板出错」「没有权限」「没有数据」各是一个变体。`;

/** What 「Show code」 shows on this page (`hostSource.ts`). */
const HOST_CODE = hostSource(
  ['Home.stories.tsx', sceneSource],
  ['retail/RetailHost.tsx', hostShell],
);

const meta = {
  title: 'View Engine/首页',
  component: HomePage,
  parameters: {
    // The host's page fills its page area, as it would a screen.
    layout: 'fullscreen',
    docs: {
      description: { component: description },
      // 「Show code」: the host's side of this scene, read from the file.
      source: { code: HOST_CODE, language: 'tsx' },
    },
  },
  args: { state: 'data' },
  argTypes: { state: { table: { disable: true } } },
  decorators: [
    Story => (
      <AppShell current="home" service={{ fixture: FIXTURE }} padded grouped>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof HomePage>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * The daily report on its fixed morning. First in the catalog, so the docs
 * page mounts this one.
 */
export const DailyReport: Story = { name: '运营日报' };

/** Every query still on its way: each panel says it is loading, alone. */
export const Loading: Story = {
  name: '加载中',
  args: { state: 'loading' },
};

/**
 * The after-sales service is down: the one card over it says so and offers
 * 重试, and every other panel draws.
 */
export const PanelError: Story = {
  name: '一个面板出错',
  args: { state: 'panel-error' },
};

/** The board is not shared with this reader: the embed says so, and whom to ask. */
export const NoPermission: Story = {
  name: '没有权限',
  args: { state: 'forbidden' },
};

/** A shop on its first morning: every panel answers, and none has anything. */
export const NoData: Story = {
  name: '没有数据',
  args: { state: 'empty' },
};
