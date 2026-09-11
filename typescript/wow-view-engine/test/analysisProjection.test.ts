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

import { expect, it } from 'vitest';
import type {
  AnalysisPlan,
  AnalysisResultColumn,
} from '../src/analysis/analysisModel.js';
import {
  projectAnalysis,
  validateAnalysisPresentation,
} from '../src/analysis/analysisProjection.js';
const column = (
  alias: string,
  role: 'dimension' | 'metric',
  valueType: AnalysisResultColumn['valueType'] = 'number',
): AnalysisResultColumn => ({
  id: alias,
  alias,
  title: alias,
  role,
  valueType,
  nullable: true,
});
const plan = (schema: AnalysisResultColumn[]): AnalysisPlan => ({
  query: { metrics: [], limit: 100 },
  schema,
});
const x = column('a.b', 'dimension');
const m = column('avg', 'metric');
it('sorts numeric coordinates, preserves literal aliases, null gaps and original rows', () => {
  const rows = [
    { 'a.b': 10, avg: null },
    { 'a.b': 2, avg: 0 },
  ];
  const value = projectAnalysis(plan([x, m]), rows, {
    layout: 'line',
    columns: [],
  });
  expect(value.issues).toEqual([]);
  expect(value.points.map(p => [p.x, p.s0])).toEqual([
    [2, 0],
    [10, null],
  ]);
  expect(rows[0]['a.b']).toBe(10);
});
it('refuses dropping a tuple dimension or recomputing averages', () => {
  const p = plan([x, column('region', 'dimension', 'string'), m]);
  const rows = [
    { 'a.b': 1, region: 'a', avg: 2 },
    { 'a.b': 1, region: 'b', avg: 9 },
  ];
  expect(
    projectAnalysis(plan([...p.schema, column('extra', 'dimension')]), rows, {
      layout: 'bar',
      columns: [],
    }).issues[0],
  ).toMatch(/维度/);
  const value = projectAnalysis(p, rows, {
    layout: 'bar',
    columns: [],
    series: 'region',
  });
  expect(value.points[0]).toMatchObject({ s0: 2, s1: 9 });
  expect(
    projectAnalysis(p, rows, { layout: 'bar', columns: [] }).points,
  ).toEqual(value.points);
  expect(
    projectAnalysis(p, rows, { layout: 'metric', columns: [] }).issues[0],
  ).toMatch(/分组/);
});
it('rejects incompatible units and numeric ANY, never coerces values', () => {
  expect(
    projectAnalysis(
      plan([x, { ...m, aggregation: 'ANY' }]),
      [{ 'a.b': 1, avg: 4 }],
      { layout: 'line', columns: [] },
    ).issues.length,
  ).toBeGreaterThan(0);
  expect(
    projectAnalysis(
      plan([
        x,
        { ...m, unit: 'CNY' },
        { ...column('count', 'metric'), unit: 'count' },
      ]),
      [{ 'a.b': 1, avg: 4, count: 3 }],
      { layout: 'bar', columns: [] },
    ).issues[0],
  ).toMatch(/单位/);
});
it('keeps missing category combinations null without fabricating x buckets', () => {
  const value = projectAnalysis(
    plan([x, column('r', 'dimension', 'string'), m]),
    [
      { 'a.b': 1, r: 'a', avg: 3 },
      { 'a.b': 5, r: 'b', avg: 7 },
    ],
    { layout: 'line', columns: [], series: 'r' },
  );
  expect(value.points.map(p => [p.x, p.s0, p.s1])).toEqual([
    [1, 3, null],
    [5, null, 7],
  ]);
});
it('bounds rendering and explains negative pie values and invalid coordinates', () => {
  expect(
    projectAnalysis(
      plan([x, m]),
      Array.from({ length: 501 }, (_, i) => ({ 'a.b': i, avg: i })),
      { layout: 'line', columns: [] },
    ).issues[0],
  ).toMatch(/500/);
  expect(
    projectAnalysis(plan([x, m]), [{ 'a.b': 1, avg: -1 }], {
      layout: 'pie',
      columns: [],
    }).issues[0],
  ).toMatch(/负/);
  expect(
    projectAnalysis(plan([x, m]), [{ 'a.b': null, avg: 1 }], {
      layout: 'line',
      columns: [],
    }).issues[0],
  ).toMatch(/坐标/);
});
it('reorders only presentation and keeps a repairable issue for stale aliases', () => {
  const p = plan([x, m]);
  const value = projectAnalysis(p, [], {
    layout: 'table',
    columns: [{ alias: 'avg', width: 200 }],
  });
  expect(value.plan.schema.map(c => c.alias)).toEqual(['avg', 'a.b']);
  expect(value.plan.schema[0].width).toBe(200);
  expect(p.schema[0].alias).toBe('a.b');
  expect(
    projectAnalysis(p, [], { layout: 'bar', columns: [], x: 'gone' }).issues[0],
  ).toMatch(/横轴/);
});

it('does not stack non-additive averages and does not hide missing pie values', () => {
  const p = plan([x, { ...m, aggregation: 'AVG' }]);
  expect(
    projectAnalysis(p, [{ 'a.b': 1, avg: 2 }], {
      layout: 'area',
      columns: [],
      stacked: true,
    }).issues[0],
  ).toMatch(/堆叠/);
  expect(
    projectAnalysis(plan([x, m]), [{ 'a.b': 1, avg: null }], {
      layout: 'pie',
      columns: [],
    }).issues[0],
  ).toMatch(/无值/);
});
it('validates persisted malformed display shapes and preserves typed tuple identity', () => {
  expect(
    projectAnalysis(plan([x, m]), [], {
      layout: 'bar',
      columns: [],
      metrics: [],
    }).issues[0],
  ).toMatch(/指标/);
  const p = plan([column('k', 'dimension', 'string'), m]);
  const projected = projectAnalysis(
    p,
    [
      { k: null, avg: 1 },
      { k: '无值', avg: 2 },
    ],
    { layout: 'bar', columns: [] },
  );
  expect(new Set(projected.points.map(p => p.identity)).size).toBe(2);
  expect(projected.points.map(point => point.x)).toEqual([null, '无值']);
});

it('rejects unordered category trends and non-additive pie shares', () => {
  expect(
    projectAnalysis(
      plan([column('x', 'dimension', 'string'), m]),
      [{ x: 'a', avg: 2 }],
      { layout: 'line', columns: [] },
    ).issues[0],
  ).toMatch(/连续/);
  expect(
    projectAnalysis(
      plan([x, { ...m, aggregation: 'AVG' }]),
      [{ 'a.b': 1, avg: 2 }],
      { layout: 'pie', columns: [] },
    ).issues[0],
  ).toMatch(/SUM/);
});

it('preserves temporal spacing and stable split identity regardless of returned row order', () => {
  const p = plan([
    { ...x, valueType: 'datetime' },
    column('region', 'dimension', 'string'),
    m,
  ]);
  const rows = [
    { 'a.b': 86400000, region: 'b', avg: 2 },
    { 'a.b': 0, region: 'a', avg: 1 },
  ];
  const a = projectAnalysis(p, rows, {
    layout: 'line',
    columns: [],
    series: 'region',
  });
  const b = projectAnalysis(p, [...rows].reverse(), {
    layout: 'line',
    columns: [],
    series: 'region',
  });
  expect(a.continuous).toBe(true);
  expect(a.points.map(p => p.x)).toEqual([0, 86400000]);
  expect(a.series).toEqual(b.series);
});
it('formats duplicate series labels without merging typed identities or values', () => {
  const split = {
    ...column('state', 'dimension', 'string'),
    options: [
      { value: 'FAILED', label: '失败' },
      { value: 'ERROR', label: '失败' },
    ],
  };
  const rows = [
    { 'a.b': 1, state: 'FAILED', avg: 2 },
    { 'a.b': 1, state: 'ERROR', avg: 7 },
  ];
  const result = projectAnalysis(plan([x, split, m]), rows, {
    layout: 'bar',
    columns: [],
    x: 'a.b',
    series: 'state',
  });
  expect(result.issues).toEqual([]);
  expect(result.series.map(s => s.title)).toEqual(['失败 · avg', '失败 · avg']);
  expect(new Set(result.series.map(s => s.key)).size).toBe(2);
  expect([result.points[0].s0, result.points[0].s1].sort()).toEqual([2, 7]);
  expect(rows[0].state).toBe('FAILED');
});

it('refuses zero-total pie shares and stacked missing values instead of rendering fabricated zeros', () => {
  const p = plan([x, { ...m, aggregation: 'SUM' }]);
  expect(
    projectAnalysis(
      p,
      [
        { 'a.b': 1, avg: 0 },
        { 'a.b': 2, avg: 0 },
      ],
      { layout: 'pie', columns: [] },
    ).issues[0],
  ).toMatch(/零/);
  const splitPlan = plan([
    x,
    column('region', 'dimension', 'string'),
    { ...m, aggregation: 'SUM' },
  ]);
  expect(
    projectAnalysis(
      splitPlan,
      [
        { 'a.b': 1, region: 'a', avg: 5 },
        { 'a.b': 2, region: 'b', avg: 8 },
      ],
      { layout: 'area', columns: [], series: 'region', stacked: true },
    ).issues[0],
  ).toMatch(/空值|缺失/);
});

it('uses ANY labels without merging IDs and falls back for missing or conflicting names', () => {
  const id = column('id', 'dimension', 'string');
  const name = {
    ...column('name', 'metric', 'string'),
    aggregation: 'ANY' as const,
    labelFor: 'id',
  };
  const presentation = {
    layout: 'bar' as const,
    columns: [],
  };
  const projected = projectAnalysis(
    plan([id, name, m]),
    [
      { id: 'p1', name: '键盘', avg: 10 },
      { id: 'p2', name: '键盘', avg: 20 },
      { id: 'p3', name: null, avg: 30 },
      { id: 'p4', name: '鼠标', avg: 40 },
    ],
    presentation,
  );
  expect(projected.issues).toEqual([]);
  expect(
    projected.points.map(p => [p.x, p.label, p.tooltipLabel, p.s0]),
  ).toEqual([
    ['p1', '键盘 · p1', '键盘 · p1', 10],
    ['p2', '键盘 · p2', '键盘 · p2', 20],
    ['p3', 'p3', 'p3', 30],
    ['p4', '鼠标', '鼠标 · p4', 40],
  ]);
  const split = column('channel', 'dimension', 'string');
  const conflict = projectAnalysis(
    plan([id, split, name, m]),
    [
      { id: 'p1', channel: 'a', name: '甲', avg: 1 },
      { id: 'p1', channel: 'b', name: '乙', avg: 2 },
    ],
    { ...presentation, x: 'id', series: 'channel' },
  );
  expect(conflict.points[0].label).toBe('p1');
  const series = projectAnalysis(
    plan([id, split, name, m]),
    [
      { id: 'p1', channel: 'a', name: '甲', avg: 1 },
      { id: 'p2', channel: 'a', name: '乙', avg: 2 },
    ],
    { ...presentation, x: 'channel', series: 'id' },
  );
  expect(series.series.map(s => s.title)).toEqual(['甲 · avg', '乙 · avg']);
  expect(
    projectAnalysis(
      plan([id, { ...name, labelFor: 'missing' }, m]),
      [],
      presentation,
    ).issues,
  ).not.toEqual([]);
});

it.each([
  [null, '显示配置无效'],
  [{ layout: 'unknown', columns: [] }, '有效的图表类型'],
  [{ layout: 'bar', columns: [{ alias: 'avg', width: -1 }] }, '表格列配置'],
  [{ layout: 'bar', columns: [], orientation: 'diagonal' }, '图表方向'],
  [{ layout: 'bar', columns: [], stacked: 'true' }, '图表开关配置'],
  [{ layout: 'pie', columns: [], donut: 1 }, '图表开关配置'],
  [{ layout: 'bar', columns: [], x: 42 }, '维度别名'],
  [{ layout: 'bar', columns: [], series: '' }, '维度别名'],
  [{ layout: 'bar', columns: [], metrics: ['avg', null] }, '至少选择一个指标'],
  [
    { layout: 'table', columns: [{ alias: 'avg' }, { alias: 'avg' }] },
    '重复别名',
  ],
  [{ layout: 'bar', columns: [], metrics: ['avg', 'avg'] }, '重复别名'],
] as const)(
  'rejects malformed saved chart configuration %j',
  (presentation, message) => {
    expect(
      validateAnalysisPresentation(presentation, [x, m]).join(';'),
    ).toContain(message);
  },
);

it('rejects duplicate returned groups rather than silently overwriting their metric', () => {
  const projected = projectAnalysis(
    plan([x, m]),
    [
      { 'a.b': 1, avg: 10 },
      { 'a.b': 1, avg: 20 },
    ],
    { layout: 'bar', columns: [], x: 'a.b', metrics: ['avg'] },
  );
  expect(projected.issues.join(';')).toContain('重复分组');
});
