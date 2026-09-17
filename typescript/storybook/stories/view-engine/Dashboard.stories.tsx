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
import { DashboardWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  createStoryEngine,
  dashboardConfig,
  emptyDashboard,
  savedDashboard,
  savedViews,
  type SourceBehaviour,
} from './fixtures.js';
import { StoryEngine, viewEngineScene } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * A dashboard composes saved views. The global filter reaches each panel as
 * an injected scope, mapped onto that panel's own field, so a referenced view
 * never becomes dirty and no dashboard condition is saved back into it.
 */
function DashboardDemo({
  behaviour = 'data',
  variant = 'panels',
  editable = false,
}: {
  behaviour?: SourceBehaviour;
  variant?: 'panels' | 'unavailable' | 'empty';
  editable?: boolean;
}) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          behaviour,
          instances: [
            // An unavailable panel is one whose instance is not in the store.
            ...(variant === 'unavailable' ? [savedViews[1]] : savedViews),
            variant === 'empty'
              ? { ...savedDashboard, config: emptyDashboard() }
              : { ...savedDashboard, config: dashboardConfig() },
          ],
        })
      }
    >
      {engine => (
        <DashboardWorkbench
          engine={engine}
          definitionId="overview"
          instanceId={savedDashboard.id}
          editable={editable}
        />
      )}
    </StoryEngine>
  );
}

const scene = {
  ...viewEngineScene,
  domain: '仪表盘视图',
  summary: '把已保存的明细与分析放在一页，用一个全局筛选统一收口径。',
  fixture: '内存 ViewStore · 两个被引用的共享视图 · 一个内容面板',
  setup: '每次挂载都新建引擎与存储；面板引用的实例随场景增减。',
  observe: '每个面板各自加载、各自出错；一个面板不可用不影响其余面板。',
};

const meta = {
  decorators: [
    (Story, context) => (
      <ScenarioFrame title={context.name} {...scene}>
        <Story />
      </ScenarioFrame>
    ),
  ],
  title: 'View Engine/仪表盘视图/Dashboard',
  component: DashboardDemo,
  args: { behaviour: 'data', variant: 'panels', editable: false },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'empty', 'slow', 'failing'],
    },
    variant: { table: { disable: true } },
    editable: { control: 'boolean' },
  },
} satisfies Meta<typeof DashboardDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Two data panels and one content panel, under one global filter. */
export const AllPanels: Story = { args: { variant: 'panels' } };

/** Drag by a panel's grip, or resize it; both apply at once, like sorting. */
export const EditableLayout: Story = { args: { editable: true } };

/** A referenced view that was deleted: only that panel says so. */
export const PanelUnavailable: Story = { args: { variant: 'unavailable' } };

/** Each panel loads on its own, so they arrive independently. */
export const Loading: Story = { args: { behaviour: 'slow' } };

/** A failing backend leaves the layout and the filter intact. */
export const QueryFailed: Story = { args: { behaviour: 'failing' } };

/** A dashboard with nothing on it yet. */
export const EmptyDashboard: Story = { args: { variant: 'empty' } };
