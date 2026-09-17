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
import { AnalysisWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  analysisConfig,
  createStoryEngine,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The Analysis workbench. Table and chart are two layouts of one saved
 * config, and switching between them is a new execution rather than a redraw,
 * because the kernel shapes a chart only when the config that ran asked for one.
 */
function AnalysisWorkbenchDemo({
  behaviour = 'data',
  layout = 'chart',
  chart = 'bar',
}: {
  behaviour?: SourceBehaviour;
  layout?: 'table' | 'chart';
  chart?: 'bar' | 'line' | 'pie';
}) {
  const config = analysisConfig({
    layout,
    chart:
      chart === 'pie'
        ? { type: 'pie', pie: { category: 'warehouse', value: 'amount' } }
        : {
            type: chart,
            cartesian: { x: 'warehouse', series: [{ metric: 'amount' }] },
          },
    table: {
      columns: [
        { alias: 'warehouse' },
        { alias: 'orders' },
        { alias: 'amount' },
      ],
      totals: true,
    },
  });

  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          instances: [{ ...savedViews[1], config }],
        })
      }
    >
      {engine => (
        <AnalysisWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={savedViews[1].id}
        />
      )}
    </StoryEngine>
  );
}

const scene = {
  ...viewEngineScene,
  domain: '分析视图',
  summary: '分组与指标进去，图表或表格出来。',
  fixture: '内存 ViewStore · 两个仓库的聚合结果',
  setup: '每次挂载都新建引擎与存储；分组、指标与图型来自保存的配置。',
  observe: '切换 Table／Chart 会重新执行，因为图表整形发生在投影层。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/分析视图/Analysis 工作台',
  component: AnalysisWorkbenchDemo,
  args: { behaviour: 'data', layout: 'chart', chart: 'bar' },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing'],
    },
    layout: { control: 'inline-radio', options: ['table', 'chart'] },
    chart: { control: 'inline-radio', options: ['bar', 'line', 'pie'] },
  },
} satisfies Meta<typeof AnalysisWorkbenchDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The default: a bar chart of one metric across one grouping. */
export const BarChart: Story = { args: { layout: 'chart', chart: 'bar' } };

/** The same result as rows, with the totals row from its own ungrouped query. */
export const TableWithTotals: Story = { args: { layout: 'table' } };

/** A pie needs a category and one value, and the kernel merges the tail. */
export const PieChart: Story = { args: { layout: 'chart', chart: 'pie' } };

/** An aggregation that matched nothing still has its editor. */
export const EmptyResult: Story = {
  args: { behaviour: 'empty', layout: 'table' },
};

/** A failed aggregation keeps the configuration on screen. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };
