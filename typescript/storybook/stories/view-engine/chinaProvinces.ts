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

/* --------------------------------------------------------------------------
 * 中国省级地图的例子（view-engine D41）：零售订单按收货省份看近 12 个月的
 * GMV。
 *
 * 本包不带任何地图数据，地理数据由宿主注册（`registerChartMap`）。这里注册
 * 的是阿里云 DataV GeoAtlas 的省级边界（数据源自高德开放平台），只在
 * Storybook 里、第一次画这张地图时才从 DataV 下载，仓库里不存这份文件；
 * 地图能不能发布（在中国发布须有审图号）由宿主负责。
 *
 * 名字怎么对上：地区维度的值按列显示的文字与要素的 `properties.name` 对上。
 * 零售数据的省份写的就是全称（「广东省」「广西壮族自治区」「北京市」），与
 * DataV 一字不差，所以这里不改名；宿主的数据若是简称，在 `load` 里把要素名
 * 改成数据的写法即可。台湾省、香港、澳门在地图上、不在数据里，画成没有数
 * 的底色；DataV 另有一个没有名字的要素（南海的九段线），照原样画出。
 *
 * 这个故事要连外网，所以只供文档与开发时打开：不进交互测试（`!test`），也
 * 不进截图基线。回归孪生注册一张合成的小地图（几个以省名命名的方块），见
 * `AnalysisMap.test.stories.tsx`。
 * ------------------------------------------------------------------------ */

import type {
  AnalysisViewConfig,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import {
  registerChartMap,
  type ChartMapGeoJson,
} from '@ahoo-wang/wow-view-engine/ui';
import { RETAIL_ORDER_ANALYSIS } from './retail/views.js';

/** 地图规格里这张地图的名字。 */
export const CHINA_MAP = 'china';

/** DataV GeoAtlas：全国，下一级（省级）边界，含南海诸岛。 */
export const DATAV_CHINA_URL =
  'https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json';

/** 注册 DataV 的中国省级地图，返回取回它的函数。 */
export function registerChinaMap(): () => void {
  return registerChartMap({
    name: CHINA_MAP,
    label: '中国省级（DataV GeoAtlas）',
    load: async () => {
      // DataV 按 Referer 防盗链：从发布的文档站打开时带着站点的 Referer
      // 会被拒（403，`denied by Referer ACL`），不带 Referer 则放行。
      const response = await fetch(DATAV_CHINA_URL, {
        referrerPolicy: 'no-referrer',
      });
      if (!response.ok)
        throw new Error(`DataV GeoAtlas answered ${response.status}.`);
      return (await response.json()) as ChartMapGeoJson;
    },
  });
}

const config: AnalysisViewConfig = {
  kind: 'analysis',
  filter: {
    op: 'and',
    children: [
      {
        field: 'firstEventTime',
        operator: 'BETWEEN',
        value: { type: 'relative', amount: 12, unit: 'month' },
      },
    ],
  },
  filterMode: 'simple',
  refresh: { interval: null },
  groups: [
    { type: 'TERMS', field: 'state.address.province', alias: 'province' },
  ],
  metrics: [
    {
      type: 'NUMERIC',
      alias: 'gmv',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'state.amounts.payableAmount' },
      label: 'GMV',
    },
  ],
  sort: [{ alias: 'gmv', direction: 'DESC' }],
  limit: 100,
  layout: 'chart',
  table: { columns: [] },
  chart: {
    type: 'map',
    map: { region: 'province', value: 'gmv', map: CHINA_MAP },
  },
};

/** 已存分析：各省 GMV（近 12 个月），画在中国省级地图上。 */
export const provinceMapView: ViewInstance = {
  id: 'chart-map-province-gmv',
  definitionId: RETAIL_ORDER_ANALYSIS,
  title: '各省 GMV（近 12 个月）',
  scope: 'shared',
  revision: '1',
  config,
};
