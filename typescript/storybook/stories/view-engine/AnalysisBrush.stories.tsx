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
import type { ViewEngine, ViewNavigation } from '@ahoo-wang/wow-view-engine';
import {
  DashboardWorkbench,
  DataWorkbench,
} from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  brushInstances,
  brushSource,
  dailyView,
  shipmentsBoard,
  shipmentsBoardDefinition,
} from './brushShipments.js';
import { shipmentsDefinition } from './dailyShipments.js';
import { HOST_LANGUAGE, createStoryEngine } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * 框选与追问：在工作台里对一个月的日柱框一段，或在看板上框一段设筛选。
 */
function BrushDemo({ surface = 'workbench' }: { surface?: Surface }) {
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          definitions: [shipmentsDefinition, shipmentsBoardDefinition],
          source: brushSource(),
          instances: brushInstances,
        })
      }
    >
      {engine =>
        surface === 'workbench' ? (
          <DataWorkbench
            engine={engine}
            definitionId={shipmentsDefinition.id}
            instanceId={dailyView.id}
            kinds={['analysis', 'record']}
            {...HOST_LANGUAGE}
          />
        ) : (
          <BoardHost engine={engine} />
        )
      }
    </StoryEngine>
  );
}

type Surface = 'workbench' | 'dashboard';

/**
 * The board, and where a way off it goes: the workbench, opened on what the
 * board handed over, with a way back to the board as it was left.
 */
function BoardHost({ engine }: { engine: ViewEngine }) {
  const [away, setAway] = useState<ViewNavigation | null>(null);
  const route = (to: ViewNavigation) =>
    setAway(to.kind === 'dashboard' ? null : to);
  if (away && (away.kind === 'view' || away.kind === 'unsaved'))
    return (
      <DataWorkbench
        engine={engine}
        definitionId={away.definitionId}
        handOver={away}
        onNavigate={route}
        {...HOST_LANGUAGE}
      />
    );
  return (
    <DashboardWorkbench
      engine={engine}
      definitionId={shipmentsBoardDefinition.id}
      instanceId={shipmentsBoard.id}
      onNavigate={route}
      {...HOST_LANGUAGE}
    />
  );
}

const FIXTURE = '内存 ViewStore · 一个月的每日发货';

const description = `**框选与追问**（D33 批 C）

- **框一段**：在时间轴上按住拖过几天，弹出点一组时的同一个追问菜单——查看这些记录、按其他维度细分这段时间、只看这段时间。菜单标题与开出去的视图都读「创建时间 介于 9月1日 ～ 9月3日」：一段桶是**一个**条件，首桶起点到末桶终点（Q52）。都开在旁边，当前视图不变脏、不重跑；框不存，菜单一关就没了。
- **看板上多一项「设为「日期」」**：框按日的柱，把那一段设成看板的日期筛选，与「点击筛选」同一个口径——另两块面板在它之下重跑，被框的这块不筛自己、只标出框里的几天。漏斗按仓库分段，一段可按：点一段设「发货仓」。
- **键盘**：切到表格布局，一行 Enter 是这一天，按住 Shift 再选一行是两行之间的这段时间；菜单一开就说出选中了哪一段。
- **触屏**：第一下只出提示框，提示框底下写「再点一下追问」，同一根柱再点一下才开菜单；手指不框选，落在图上照样滚页面。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="brush" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/能力/框选与追问',
  component: BrushDemo,
  args: { surface: 'workbench' },
  argTypes: {
    surface: { control: 'inline-radio', options: ['workbench', 'dashboard'] },
  },
} satisfies Meta<typeof BrushDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 工作台：一个月的日柱，框几天就追问这几天。 */
export const InTheWorkbench: Story = { args: { surface: 'workbench' } };

/** 看板：框按日的柱设「日期」，点漏斗的一段设「发货仓」。 */
export const OnADashboard: Story = { args: { surface: 'dashboard' } };
