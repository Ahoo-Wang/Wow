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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE } from './fixtures.js';
import {
  DEFAULT_PRICING_HOST,
  PRODUCT_PRICING,
  createProductPricingEngine,
  pricingFetcher,
} from './productPricing.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The product pricing snapshot console — one workbench over the prices a
 * real Wow pricing service holds: which are in force, which lapse soon,
 * which have lapsed, and how they distribute over status, price, brand and
 * delivery. The record views and the analysis views sit in one list.
 *
 * Read-only: prices are set by the pricing flows, not from here. Nothing
 * here is faked, so nothing here is repeatable — this story is for using,
 * not for CI (its regression twin runs over a recorded service).
 */

/**
 * One engine per host, keyed by the host so pointing the Controls panel
 * elsewhere starts over rather than mixing two services' views.
 */
function Console({ host }: { host: string }) {
  return <HostConsole key={host} host={host} />;
}

function HostConsole({ host }: { host: string }) {
  const [fetcher] = useState(() => pricingFetcher(host));
  return (
    <StoryEngine create={() => createProductPricingEngine(fetcher)}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={PRODUCT_PRICING}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

/**
 * What the scene is, on the docs page rather than above the console: the
 * console is the product an operator uses, so it has the screen to itself.
 */
const description = `**真实后端 · 商品定价**

Wow 定价服务里的商品定价：一条记录是一档价格——哪个商品、什么货期、起订多少、单价多少、有效到哪天。同一个工作台里查生效中的价格、半年内到期的价格、已停用或过期的价格，看价格按状态、区间、品牌与货期的分布。

- **数据源**：Wow 定价服务 \`product_pricing\` 的快照，地址是 \`host\` 参数，可在 Controls 面板切换；改动后按新地址重建引擎。默认是本机转发的 \`http://localhost:8089\`，集群内直接用 \`http://pricing-service.dev.svc.cluster.local\`；初始值可用 \`STORYBOOK_WOW_PRICING_HOST\` 改。
- **准备**：引擎与视图存储随故事新建；数据直连 \`host\` 指向的服务，只读。
- **操作**：打开「快照控制台」场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像操作员使用时一样。
- **观察**：「生效中」按商品、货期、起订量排好，同一商品的阶梯价连在一起读；筛选、排序、分页与聚合都由服务端执行。

> 只读：价格由定价流程写入，这里没有命令。`;

const meta = {
  title: 'View Engine/真实后端/商品定价/快照控制台',
  component: Console,
  // A live service answers differently every time, so this is never a
  // regression test, and its docs page does not mount it: opening the
  // catalog must not call the service.
  tags: ['!test'],
  parameters: {
    // The console is the product: it fills the canvas inside the host's
    // own bar and navigation (`AppShell`), as it would a screen.
    layout: 'fullscreen',
    docs: { autoMount: false, description: { component: description } },
  },
  args: { host: DEFAULT_PRICING_HOST },
  argTypes: {
    host: {
      control: 'text',
      description: 'Wow 定价服务地址，改动后按新地址重建引擎。',
    },
  },
  decorators: [
    (Story, context) => (
      <AppShell
        current="pricing-snapshots"
        service={{ host: context.args.host }}
      >
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Console>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The prices in force, lapsing and lapsed, and how they distribute. */
export const SnapshotConsole: Story = { name: '快照控制台' };
