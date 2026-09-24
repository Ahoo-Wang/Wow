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
import type { CSSProperties } from 'react';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  shipmentsConfig,
  shipmentsDefinition,
  shipmentsSource,
  shipmentsView,
  type ShipmentScene,
} from './dailyShipments.js';
import { HOST_LANGUAGE, createStoryEngine } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 长时间轴上的一张图：一年的日数据，或一万天的。`patterns` 是宿主对花纹的
 * 钉法——写在工作台外面一层的 `--fve-chart-patterns` 上，`auto` 跟随系统的
 * 「提高对比度」（D33 Q57）。
 */
function TimeAxisDemo({
  scene = 'year',
  chart,
  patterns = 'auto',
}: {
  scene?: ShipmentScene;
  /** The chart type; left out, the scene's own. */
  chart?: 'line' | 'bar';
  patterns?: 'auto' | 'on' | 'off';
}) {
  const view = shipmentsView(shipmentsConfig(scene, chart));
  return (
    <div
      className="h-full"
      style={
        patterns === 'auto'
          ? undefined
          : ({ '--fve-chart-patterns': patterns } as CSSProperties)
      }
    >
      <StoryEngine
        create={() =>
          createStoryEngine({
            definitions: [shipmentsDefinition],
            source: shipmentsSource(scene),
            instances: [view],
          })
        }
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId={shipmentsDefinition.id}
            instanceId={view.id}
            {...HOST_LANGUAGE}
            kinds={['analysis']}
          />
        )}
      </StoryEngine>
    </div>
  );
}

const FIXTURE = '内存 ViewStore · 一年与一万天的每日发货';

const description = `**分析视图 · 长时间轴**（D33 批 A）

- **缩放**：点多于 60 个的横轴在图下有一条滑条，拖两端缩放、拖中间平移；工作台里按住 Ctrl 滚动（触控板双指捏合同理）或触屏双指捏合也能缩放。**不按 Ctrl 的滚轮照常滚动页面**。缩放不随视图保存（Q51），换一个结果就回到全范围。
- **图例**：多于一条系列时图例的每一项是一颗开关，点一下（或 Tab 到它按空格）藏起这条系列；刻度与读屏表跟着只量留下的系列。
- **较上一期**：时间轴上悬停，提示框在每个数后写它较前一天的变化，不多发查询（Q59）。
- **大数据**：折线多于绘图区宽度的点按 LTTB 采样；多于一千根的柱一条路径画完、不写数。
- **花纹**：跟随系统「提高对比度」；宿主用 \`--fve-chart-patterns: on | off\` 钉开或钉关。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="analysis" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/分析视图/长时间轴',
  component: TimeAxisDemo,
  args: { scene: 'year', patterns: 'auto' },
  argTypes: {
    scene: {
      control: 'inline-radio',
      options: ['year', 'year-bars', 'ten-thousand-days'],
    },
    chart: { control: 'inline-radio', options: [undefined, 'line', 'bar'] },
    patterns: { control: 'inline-radio', options: ['auto', 'on', 'off'] },
  },
} satisfies Meta<typeof TimeAxisDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 一年的日数据，两个仓库两条线：滑条、图例开关、提示框的「较上一期」。 */
export const YearOfDays: Story = { args: { scene: 'year' } };

/** 同一年画成堆叠的柱：730 根柱仍一根一个元素，缩到一周读得出每一天。 */
export const YearOfDaysBars: Story = { args: { scene: 'year-bars' } };

/**
 * A story of ten thousand rows is not judged by axe: its reading table holds
 * ten thousand of them, and axe walking it outlasts the test. The same table
 * at a year's length is judged in every other story here.
 */
const LONG_RUN = { a11y: { test: 'off' } };

/** 一万天：一条一万个点的线，按绘图区的宽度采样。 */
export const TenThousandDays: Story = {
  args: { scene: 'ten-thousand-days' },
  parameters: LONG_RUN,
};

/** 一万天画成柱：多于一千根，一条路径画完、不写数。 */
export const TenThousandBars: Story = {
  args: { scene: 'ten-thousand-days', chart: 'bar' },
  parameters: LONG_RUN,
};

/** 宿主钉开花纹：每条系列除了颜色还有自己的花纹。 */
export const PatternsPinnedOn: Story = {
  args: { scene: 'year-bars', patterns: 'on' },
};
