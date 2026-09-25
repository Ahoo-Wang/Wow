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
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ViewEngine } from '@ahoo-wang/wow-view-engine';
import { EmbeddedDashboard } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  DEFAULT_COMPENSATION_HOST,
  compensationFetcher,
  compensationSource,
} from './compensation.js';
import { HOST_LANGUAGE } from './fixtures.js';
import {
  HOME_DASHBOARD,
  createHomeEngine,
  createHomeFixtureEngine,
} from './home.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The compensation service's overview: a host page that embeds a dashboard
 * over the service's failed executions. It was the home page until the
 * retail daily report took that place (docs/scenarios.md Q2); it now sits
 * with the compensation service's two consoles, whose readers are the ones
 * looking for it.
 *
 * What it owes the person who opens it is the state of things — so the
 * dashboard is the page, and the host adds only what a dashboard cannot
 * know: which page this is and which day it is being read on. There are no host-drawn number tiles: the
 * numbers worth a tile are counts over the same executions, which are the
 * dashboard's to query and to keep in step with its panels, so they are
 * metric panels on it rather than a second set of queries in the host.
 *
 * The dashboard is `EmbeddedDashboard` on the operations team's shared
 * board (`home.ts`), in the interactive tier: a report, read like any embed
 * — no title bar, no view list, and nothing on it builds or saves (D36) —
 * whose reader may still change the filters, press into a panel and fill
 * the screen with it (`expandable`), for this viewing alone. The team
 * builds the board in `DashboardWorkbench`.
 */
function OverviewPage({ engine }: { engine: ViewEngine }) {
  const { timeZone } = engine.environment;
  // The runtime's clock and zone, so the date above the dashboard is the
  // one its "today" and "this month" are counted in.
  const today = new Intl.DateTimeFormat(HOST_LANGUAGE.locale, {
    dateStyle: 'full',
    timeZone,
  }).format(engine.environment.now());
  return (
    <div
      data-host-page
      // The host's own markup, painted from View Engine's tokens as the
      // shell is (D17-10); the page area around it gives the gutter.
      className="fve-tokens bg-background text-foreground flex min-w-0 flex-col gap-4"
    >
      {/* Not a `header`: the shell's bar is the page's one banner. */}
      <div className="flex flex-col gap-1">
        <p className="text-muted-foreground text-xs">{today}</p>
        <h1 className="text-xl font-semibold">运营概览</h1>
        <p className="text-muted-foreground text-sm">
          补偿服务里执行失败的现状：还在等人处理的、今天新开的，以及这个月每天的走势。
        </p>
      </div>
      <EmbeddedDashboard
        className="host-home"
        engine={engine}
        instanceId={HOME_DASHBOARD}
        interaction="interactive"
        expandable
        {...HOST_LANGUAGE}
      />
    </div>
  );
}

/**
 * The page over the service at `host`, or over the fixture when there is no
 * host. One engine per host, keyed by it, so pointing the Controls panel
 * elsewhere starts over rather than mixing two services' views.
 */
function Home({ host }: { host?: string }) {
  return host ? <LiveHome key={host} host={host} /> : <FixtureHome />;
}

function LiveHome({ host }: { host: string }) {
  const [fetcher] = useState(() => compensationFetcher(host));
  return (
    <StoryEngine create={() => createHomeEngine(compensationSource(fetcher))}>
      {engine => <OverviewPage engine={engine} />}
    </StoryEngine>
  );
}

function FixtureHome() {
  return (
    <StoryEngine create={createHomeFixtureEngine}>
      {engine => <OverviewPage engine={engine} />}
    </StoryEngine>
  );
}

/** What the fixture answers from, said in the host's service line and below. */
const FIXTURE = '内存 ViewStore · 八月以来的执行失败';

/**
 * What the scene is, on the docs page rather than above the page: the home
 * page has the host's page area to itself.
 */
const description = `**补偿控制台 · 运营概览**

一块嵌入的仪表盘，说清补偿服务里执行失败的现状。它原来是首页；首页换成零售的运营日报之后（docs/scenarios.md Q2），它搬到补偿控制台这一组，与快照控制台、事件流分析台放在一起。

- **数据源**：「示例数据」是 ${FIXTURE}，时钟钉在 2026-09-22 上午 10 点（Asia/Shanghai），「今日」「本月」每次都一样；「真实后端」连 \`host\` 指向的 Wow 补偿服务，与两个控制台同一个地址，只读。
- **准备**：每次挂载都新建引擎与存储。仪表盘是运营组共享的一块板，面板引用的也是运营组共享的视图——其中「按状态分布」就是快照控制台自己的系统视图。
- **操作**：打开任一场景。页面放在宿主应用里——顶栏、左侧导航、日期与「运营概览」标题是宿主的，下面那一整块是视图引擎的 \`EmbeddedDashboard\`，\`interaction="interactive"\`：只读的运营报告——没有「编辑」、保存与另存为（嵌入一律不写），读者能点进面板（板上有筛选时也能改筛选），右上角「铺满屏幕」把整块板铺开，Esc 收起；改动只影响这一次观看。搭这块板在仪表盘工作台里。
- **观察**：三个数字、本月每天新开的失败、状态分布、最近的活动失败与失败最多的处理器；每个面板各自加载、各自出错。宿主不另画数字卡片——那些数字是同一份数据上的计数，归仪表盘去查。`;

const meta = {
  title: 'View Engine/真实后端/补偿控制台/运营概览',
  component: Home,
  parameters: {
    // The host's page fills its page area, as it would a screen.
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  argTypes: {
    host: {
      control: 'text',
      description: 'Wow 补偿服务地址，改动后按新地址重建引擎。',
    },
  },
  decorators: [
    (Story, context) => (
      <AppShell
        current="compensation-overview"
        service={
          context.args.host ? { host: context.args.host } : { fixture: FIXTURE }
        }
        padded
      >
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Home>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * Over the fixture, on a fixed morning. First in the file, so the docs page
 * mounts this one and opening the catalog never calls a service.
 */
export const Fixture: Story = {
  name: '示例数据',
  argTypes: { host: { table: { disable: true } } },
};

/**
 * Over the real compensation service. A live service answers differently
 * every time, so this is never a regression test (its twin is the fixture's).
 */
export const Live: Story = {
  name: '真实后端',
  tags: ['!test'],
  args: { host: DEFAULT_COMPENSATION_HOST },
};
