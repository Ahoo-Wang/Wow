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
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { HOST_LANGUAGE, createStoryEngine } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import {
  exportsDefinition,
  exportsMapView,
  exportsSource,
  registerWorldMap,
} from './worldExports.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The analysis workbench on the exports' map. The host registers its map
 * (`registered`); without one, the map has nothing to draw on and says so.
 */
function MapScene({ registered }: { registered: boolean }) {
  return (
    <StoryEngine
      key={String(registered)}
      create={() =>
        createStoryEngine({
          definitions: [exportsDefinition],
          source: exportsSource(),
          instances: [exportsMapView],
        })
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={exportsDefinition.id}
          instanceId={exportsMapView.id}
          kinds={['analysis', 'record']}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const description = `**能力 · 地图**（view-engine D41）

跨境出口的订单按目的国看 GMV。**本包不带任何地图数据**：地图画哪些边界、能不能发布，由发布地的法规决定（例如在中国发布中国地图须有审图号），地理数据由宿主经 \`registerChartMap\` 注册、由宿主负责。

这里注册的是公有领域的 Natural Earth 世界地图（\`world-atlas\` 的 \`countries-110m\`，ISC；\`topojson-client\` 转成 GeoJSON，ISC），只在 Storybook 里，第一次画地图时才下载。国家名是 Natural Earth 的英文名，按目的国这一列显示的文字与地图对上；新加坡在这个比例尺的地图上没有面积，图上方说「1 个地区不在这张地图上」，读屏表里照样有它。

- **有地图**：GMV 越高颜色越深，色标在下；按下一个国家弹出追问菜单，「查看这些记录」就是发往它的出口单。
- **没有注册地图**：图上方写「没有可用的地图」，可视化面板里地图置灰并写「这里没有可用的地图」。`;

const meta = {
  title: 'View Engine/能力/地图',
  component: MapScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  args: { registered: true },
  // Each story says whether the host has offered its map; the one offered
  // is taken back when the story goes, so no other story sees it.
  beforeEach: ({ args }) =>
    (args as { registered: boolean }).registered
      ? registerWorldMap()
      : undefined,
  decorators: [
    Story => (
      <AppShell current="analysis" service={{ fixture: '跨境出口 · 示例数据' }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof MapScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const WorldMap: Story = {
  name: '各目的国出口 GMV',
};

export const NoMapRegistered: Story = {
  name: '宿主没有注册地图',
  args: { registered: false },
};
