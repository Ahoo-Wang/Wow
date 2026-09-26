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
import type { DashboardFilters } from '@ahoo-wang/wow-view-engine';
import { AppShell } from '../shared/AppShell.js';
import { FULFILMENT } from './retail/boards.js';
import { RetailBoardScene } from './retail/RetailHost.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import sceneSource from './Fulfilment.stories.tsx?raw';
import hostShell from './retail/RetailHost.tsx?raw';
import { hostSource } from './hostSource.js';

/** 履约与售后 in the dashboard workbench, on its two tabs. */
function Fulfilment({
  tab,
  filters,
}: {
  tab?: string;
  filters?: DashboardFilters;
}) {
  return (
    <RetailBoardScene
      instanceId={FULFILMENT}
      initialTab={tab}
      initialFilters={filters}
    />
  );
}

const FIXTURE = '内存 ViewStore · 栖木生活 2 万张子订单（示例数据）';

const description = `**业务场景 · 履约与售后**

仓配与客服主管的板（docs/scenarios.md 4.1），两个标签页，只看已付款的单。

- **数据源**：${FIXTURE}；时钟钉在 2026-09-22 上午 10 点。
- **筛选**：日期（可选）、仓库、省份、渠道。
- **履约**：按付款周的发货超时率，红线 5%（A-12：春节停运那几周冲过红线，A6；双 11 的积压也在）；付款到发货的 P50 与 P90（≈）按仓库；承运商 × 周的平均签收时长（A-13，设「省份 = 广东省」，中通 7 月下旬那几格一下亮起来，A2）；超时明细。
- **售后**：售后理由（点一个理由，打开售后单明细，条件就是这个理由）、退款率最高的 10 个商品（A-07）、每日退款金额、售后单明细。`;

/** What 「Show code」 shows on this page (`hostSource.ts`). */
const HOST_CODE = hostSource(
  ['Fulfilment.stories.tsx', sceneSource],
  ['retail/RetailHost.tsx', hostShell],
);

const meta = {
  title: 'View Engine/业务场景/履约与售后',
  component: Fulfilment,
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: { component: description },
      // 「Show code」: the host's side of this scene, read from the file.
      source: { code: HOST_CODE, language: 'tsx' },
    },
  },
  argTypes: {
    tab: { table: { disable: true } },
    filters: { table: { disable: true } },
  },
  decorators: [
    Story => (
      <AppShell current="fulfilment" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Fulfilment>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 履约: the weekly breach rate, the ship hours and the carriers. */
export const FulfilmentTab: Story = { name: '履约' };

/**
 * 履约 narrowed to 广东省: the typhoon weeks of July (A2) stand out on
 * 中通's row of the carrier heatmap.
 */
export const Guangdong: Story = {
  name: '履约 · 广东省',
  args: { filters: { values: { province: ['广东省'] } } },
};

/** 售后: why buyers ask for their money back, and which products. */
export const AfterSales: Story = {
  name: '售后',
  args: { tab: 'afterSales' },
};
