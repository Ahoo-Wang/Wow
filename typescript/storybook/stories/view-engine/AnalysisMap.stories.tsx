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
import { provinceMapView, registerChinaMap } from './chinaProvinces.js';
import { RETAIL_FIXTURE } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import { retailOrderAnalysisDefinition } from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/** Which data the map is drawn over: the exports by country, the retail orders by province. */
type MapDataset = 'exports' | 'provinces';

/**
 * The analysis workbench on a map. The host registers its map in the
 * story's `beforeEach`; without one, the map has nothing to draw on and
 * says so.
 */
function MapScene({ dataset }: { dataset: MapDataset }) {
  return dataset === 'exports' ? (
    <StoryEngine
      key={dataset}
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
  ) : (
    <StoryEngine
      key={dataset}
      create={() =>
        createRetailEngine([retailOrderAnalysisDefinition], [provinceMapView])
      }
    >
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={retailOrderAnalysisDefinition.id}
          instanceId={provinceMapView.id}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const description = `**能力 · 地图**（view-engine D41）

**本包不带任何地图数据**：地图画哪些边界、能不能发布，由发布地的法规决定（例如在中国发布中国地图须有审图号），地理数据由宿主经 \`registerChartMap\` 注册、由宿主负责。地区维度的值按这一列显示的文字与地图要素的 \`properties.name\` 对上，对不上的地区在图上方说出个数，不猜。

**世界地图**：跨境出口的订单按目的国看 GMV。这里注册的是公有领域的 Natural Earth 世界地图（\`world-atlas\` 的 \`countries-110m\`，ISC；\`topojson-client\` 转成 GeoJSON，ISC），只在 Storybook 里，第一次画地图时才下载。国家名是 Natural Earth 的英文名，按目的国这一列显示的文字与地图对上；新加坡在这个比例尺的地图上没有面积，图上方说「1 个地区不在这张地图上」，读屏表里照样有它。

**中国省级地图**：零售订单按收货省份看近 12 个月的 GMV。地理数据是阿里云 DataV GeoAtlas 的省级边界（\`areas_v3/bound/100000_full.json\`，数据源自高德开放平台，含南海诸岛），只在 Storybook 里、第一次画地图时才从 DataV 在线读取，仓库与本包都不存这份文件；要连外网，所以这个故事只供浏览，不进回归测试与截图基线。用在产品里时，地图的来源、授权与审图号由宿主负责。DataV 的要素名是全称（「广东省」「广西壮族自治区」「北京市」），零售数据的省份写的也是全称，所以不用改名；宿主的数据若写简称，在注册时的 \`load\` 里把要素名改成数据的写法。台湾省、香港、澳门在地图上、不在数据里，画成没有数的底色。

- **有地图**：GMV 越高颜色越深，色标在下；按下一个地区弹出追问菜单，「查看这些记录」就是那里的订单。
- **没有注册地图**：图上方写「没有可用的地图」，可视化面板里地图置灰并写「这里没有可用的地图」。`;

const meta = {
  title: 'View Engine/能力/地图',
  component: MapScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  args: { dataset: 'exports' },
  argTypes: { dataset: { control: false } },
  decorators: [
    (Story, { args }) => (
      <AppShell
        current="analysis"
        service={{
          fixture:
            (args as { dataset: MapDataset }).dataset === 'provinces'
              ? RETAIL_FIXTURE
              : '跨境出口 · 示例数据',
        }}
      >
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof MapScene>;

export default meta;

type Story = StoryObj<typeof meta>;

// Each story says whether the host has offered its map; the one offered is
// taken back when the story goes, so no other story sees it.

export const WorldMap: Story = {
  name: '各目的国出口 GMV',
  beforeEach: () => registerWorldMap(),
};

export const NoMapRegistered: Story = {
  name: '宿主没有注册地图',
};

/**
 * The real geography, read from DataV at run time: a docs and dev story
 * only. It needs the network, so it is kept out of the interaction tests
 * (`!test`) and has no screenshot baseline; the regression twin draws the
 * same analysis on a small synthetic map instead.
 */
export const ChinaProvinces: Story = {
  name: '各省 GMV（DataV 省级地图）',
  tags: ['!test'],
  args: { dataset: 'provinces' },
  beforeEach: () => registerChinaMap(),
};
