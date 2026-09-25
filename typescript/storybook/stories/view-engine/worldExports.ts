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
 * 地图图型的例子（view-engine D41）：跨境出口的订单，按目的国看 GMV。
 *
 * 本包不带任何地图数据，地理数据由宿主注册（`registerChartMap`）。这里注册
 * 的是公有领域的 Natural Earth 世界地图——`world-atlas` 的 `countries-110m`
 * （ISC），经 `topojson-client`（ISC）转成 GeoJSON——只在 Storybook 里用，
 * 第一次画地图时才下载。国家名用 Natural Earth 的英文名，地区维度的值按列
 * 显示的文字与地图要素名对上；「Singapore」在 1:1.1 亿的地图上没有面积，正好
 * 演示「不在这张地图上」。
 * ------------------------------------------------------------------------ */

import {
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import type {
  AnalysisViewConfig,
  DataViewDefinition,
  RecordData,
  ViewInstance,
} from '@ahoo-wang/wow-view-engine';
import {
  registerChartMap,
  type ChartMapGeoJson,
} from '@ahoo-wang/wow-view-engine/ui';
import { feature } from 'topojson-client';
import worldUrl from 'world-atlas/countries-110m.json?url';
import { rowSource } from './rowSource.js';

/** 注册世界地图，返回取回它的函数。 */
export function registerWorldMap(): () => void {
  return registerChartMap({
    name: 'world',
    label: '世界（Natural Earth）',
    load: async () => {
      const response = await fetch(worldUrl);
      const topology = (await response.json()) as Parameters<typeof feature>[0];
      return feature(
        topology,
        topology.objects.countries!,
      ) as unknown as ChartMapGeoJson;
    },
  });
}

/** 目的国与它的出口权重：大的市场单多。 */
const DESTINATIONS: [string, number][] = [
  ['United States of America', 26],
  ['Japan', 14],
  ['Germany', 9],
  ['United Kingdom', 8],
  ['Australia', 7],
  ['Canada', 6],
  ['France', 5],
  ['South Korea', 5],
  ['Netherlands', 3],
  ['Brazil', 3],
  ['Mexico', 2],
  ['Spain', 2],
  ['Italy', 2],
  ['Saudi Arabia', 2],
  ['New Zealand', 1],
  ['Sweden', 1],
  ['Singapore', 4],
];

/** 出口订单：每个目的国若干张单，金额由序号定，每次一样。 */
export const EXPORT_ORDERS: RecordData[] = DESTINATIONS.flatMap(
  ([country, weight], at) =>
    Array.from({ length: weight * 3 }, (_, index) => ({
      id: `EX-${String(at).padStart(2, '0')}-${String(index).padStart(3, '0')}`,
      country,
      amount: 180 + ((index * 97 + at * 53) % 640),
    })),
);

export const exportsDefinition: DataViewDefinition = {
  id: 'exports',
  title: '跨境出口',
  recordNoun: '出口单',
  kind: 'data',
  source: 'exports',
  fields: [
    { name: 'id', label: '单号', kind: 'string', sortable: true },
    { name: 'country', label: '目的国', kind: 'string', sortable: true },
    {
      name: 'amount',
      label: '金额',
      kind: 'number',
      numberFormat: { style: 'currency', currency: 'USD' },
    },
  ],
  record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
  analysis: {
    count: true,
    fields: [
      {
        field: 'country',
        groups: [AggregationGroupType.TERMS],
        functions: [],
      },
      {
        field: 'amount',
        groups: [],
        functions: [AggregationFunction.SUM, AggregationFunction.AVG],
      },
    ],
  },
};

const config: AnalysisViewConfig = {
  kind: 'analysis',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  groups: [{ type: 'TERMS', field: 'country', alias: 'country' }],
  metrics: [
    {
      type: 'NUMERIC',
      alias: 'gmv',
      function: 'SUM',
      expression: { type: 'FIELD', field: 'amount' },
      label: 'GMV',
    },
  ],
  sort: [{ alias: 'gmv', direction: 'DESC' }],
  limit: 100,
  layout: 'chart',
  table: { columns: [] },
  chart: {
    type: 'map',
    map: { region: 'country', value: 'gmv', map: 'world' },
  },
};

export const exportsMapView: ViewInstance = {
  id: 'exports-by-country',
  definitionId: exportsDefinition.id,
  title: '各目的国出口 GMV',
  scope: 'shared',
  revision: '1',
  config,
};

export function exportsSource() {
  return rowSource(EXPORT_ORDERS);
}
