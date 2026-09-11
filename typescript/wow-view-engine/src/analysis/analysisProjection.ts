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

import { getAnalysisVisualization } from './analysisVisualizations.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisPlan,
  AnalysisRow,
  AnalysisResultColumn,
} from './analysisModel.js';
import {
  resolveAnalysisAxes,
  resolveAnalysisMetricAliases,
} from './analysisPresentation.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
import { formatAnalysisValue } from './analysisFormatting.js';
import { analysisRowKey } from './analysisResult.js';

export function validateAnalysisPresentation(
  value: unknown,
  schema?: DeepReadonly<readonly AnalysisResultColumn[]>,
): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    return ['显示配置无效，请重新选择图表'];
  const p = value as Record<string, unknown>;
  const issues: string[] = [];
  if (!getAnalysisVisualization(p.layout)) issues.push('请选择有效的图表类型');
  if (
    !Array.isArray(p.columns) ||
    p.columns.some(
      c =>
        !c ||
        typeof c.alias !== 'string' ||
        (c.width !== undefined &&
          (typeof c.width !== 'number' ||
            !Number.isFinite(c.width) ||
            c.width <= 0)),
    )
  )
    issues.push('请修复表格列配置');
  if (
    p.orientation !== undefined &&
    !['vertical', 'horizontal'].includes(p.orientation as string)
  )
    issues.push('请选择有效的图表方向');
  for (const key of ['stacked', 'donut'])
    if (p[key] !== undefined && typeof p[key] !== 'boolean')
      issues.push('请修复图表开关配置');
  for (const key of ['x', 'series'])
    if (p[key] !== undefined && (typeof p[key] !== 'string' || !p[key]))
      issues.push('请选择有效的维度别名');
  if (
    p.metrics !== undefined &&
    (!Array.isArray(p.metrics) ||
      !p.metrics.length ||
      p.metrics.some(m => typeof m !== 'string' || !m))
  )
    issues.push('请至少选择一个指标');
  if (issues.length) return issues;
  const columns = p.columns as AnalysisPresentation['columns'];
  const metrics = p.metrics as string[] | undefined;
  if (
    new Set(columns.map(c => c.alias)).size !== columns.length ||
    (metrics && new Set(metrics).size !== metrics.length)
  )
    issues.push('显示配置存在重复别名');
  if (schema) {
    for (const c of columns)
      if (!schema.some(s => s.alias === c.alias))
        issues.push(`表格列 ${c.alias} 已失效，请重新选择`);
    if (
      p.x !== undefined &&
      !schema.some(s => s.alias === p.x && s.role === 'dimension')
    )
      issues.push(`横轴维度已失效，请重新选择（${p.x}）`);
    if (
      p.series !== undefined &&
      !schema.some(s => s.alias === p.series && s.role === 'dimension')
    )
      issues.push(`系列维度已失效，请重新选择（${p.series}）`);
    for (const m of metrics ?? [])
      if (!schema.some(s => s.alias === m && s.role === 'metric'))
        issues.push(`指标 ${m} 已失效，请重新选择`);
  }
  return issues;
}
export interface AnalysisProjection {
  plan: DeepReadonly<AnalysisPlan>;
  issues: string[];
  /** Concise capability reason, supplied at the same validation boundary. */
  issueSummary?: string;
  points: { [key: string]: string | number | null }[];
  series: {
    key: string;
    title: string;
    tooltipTitle?: string;
    alias: string;
    unit?: string;
  }[];
  x?: DeepReadonly<AnalysisResultColumn>;
  continuous: boolean;
}
/** Project admitted rows without summing, averaging, or inventing groups. */
export function projectAnalysis(
  plan: DeepReadonly<AnalysisPlan>,
  rows: DeepReadonly<readonly AnalysisRow[]>,
  presentation: DeepReadonly<AnalysisPresentation>,
): AnalysisProjection {
  const issues = validateAnalysisPresentation(presentation, plan.schema);
  const result: AnalysisProjection = {
    plan,
    issues,
    points: [],
    series: [],
    continuous: false,
  };
  if (issues.length) return result;
  const visualization = getAnalysisVisualization(presentation.layout)!;
  const displayed = presentation.columns.map(c => ({
    ...plan.schema.find(s => s.alias === c.alias)!,
    ...(c.width === undefined ? {} : { width: c.width }),
  }));
  result.plan = {
    ...plan,
    schema: [
      ...displayed,
      ...plan.schema.filter(s => !displayed.some(c => c.alias === s.alias)),
    ],
  };
  if (presentation.layout === 'table') return result;
  const fail = (issue: string, summary = issue) => {
    issues.push(issue);
    result.issueSummary = summary;
    return result;
  };
  const dimensions = plan.schema.filter(c => c.role === 'dimension');
  const metrics = resolveAnalysisMetricAliases(plan.schema, presentation).map(
    alias => plan.schema.find(column => column.alias === alias)!,
  );
  if (
    !metrics.length ||
    metrics.some(c => c.valueType !== 'number' || c.aggregation === 'ANY')
  )
    return fail(
      '请选择数值聚合指标；ANY 代表值可用于维度标签或表格展示',
      '缺少数值聚合指标',
    );
  if (
    presentation.layout !== 'metric' &&
    new Set(
      metrics.map(
        c =>
          c.unit ??
          (c.aggregation === 'COUNT' || c.format === 'count'
            ? 'count'
            : 'unspecified'),
      ),
    ).size > 1
  )
    return fail('指标单位不兼容，请选择相同单位的指标或查看表格');
  if (
    presentation.stacked &&
    visualization.stacked &&
    metrics.some(
      c =>
        c.aggregation !== 'SUM' &&
        c.aggregation !== 'COUNT' &&
        c.format !== 'count',
    )
  )
    return fail('堆叠需要可相加的 SUM 或 COUNT 指标，请关闭堆叠或选择其他指标');
  if (rows.length > 500)
    return fail(
      '图表最多展示 500 个分组，请缩小查询范围或查看表格',
      '超过 500 个分组',
    );
  if (presentation.layout === 'metric') {
    if (dimensions.length || rows.length > 1)
      return fail(
        '指标卡需要无分组结果，请移除分组或查看表格',
        '仅支持无分组结果',
      );
    result.series = metrics.map((m, i) => ({
      key: `s${i}`,
      title: m.title,
      alias: m.alias,
      unit: m.unit,
    }));
    if (rows.length)
      result.points = [
        Object.fromEntries(
          metrics.map((m, i) => [`s${i}`, rows[0][m.alias] as number | null]),
        ),
      ];
    return result;
  }
  const { x, series: split } = resolveAnalysisAxes(dimensions, presentation);
  if (!x || x === split || dimensions.some(c => c !== x && c !== split))
    return fail(
      '图表需要一个横轴维度及可选的一个系列维度，请调整维度或查看表格',
      '需要 1–2 个分组维度',
    );
  result.x = x;
  result.continuous =
    visualization.continuous &&
    (x.valueType === 'datetime' || x.valueType === 'number');
  if (visualization.continuous && !result.continuous)
    return fail(
      '折线和面积图需要连续的数值或时间横轴，请选择柱状图或调整维度',
      '需要时间或数值轴',
    );
  if (
    result.continuous &&
    rows.some(
      r =>
        typeof r[x.alias] !== 'number' ||
        !Number.isFinite(r[x.alias]) ||
        (x.valueType === 'datetime' &&
          !Number.isFinite(new Date(r[x.alias] as number).getTime())),
    )
  )
    return fail(
      '连续坐标包含无值或无效日期，请过滤该分组或查看表格',
      '轴包含无效值',
    );
  if (presentation.layout === 'pie' && (split || metrics.length !== 1))
    return fail(
      split
        ? '饼图仅支持一个分组维度，请调整查询分组'
        : `饼图只支持一个指标，当前选择了 ${metrics.length} 个，请取消多余指标`,
      split ? '仅支持单个分组维度' : '仅支持单个指标',
    );
  if (
    presentation.layout === 'pie' &&
    rows.some(
      r =>
        typeof r[metrics[0].alias] === 'number' &&
        (r[metrics[0].alias] as number) < 0,
    )
  )
    return fail('饼图不支持负数，请使用柱状图或表格', '不支持负数');
  if (
    presentation.layout === 'pie' &&
    rows.some(r => r[metrics[0].alias] === null)
  )
    return fail(
      '饼图包含无值，无法表达占比，请使用柱状图或表格',
      '指标包含空值',
    );
  if (
    presentation.layout === 'pie' &&
    rows.length > 0 &&
    rows.every(r => r[metrics[0].alias] === 0)
  )
    return fail(
      '饼图指标全部为零，无法表达占比，请使用柱状图或表格',
      '指标全部为零',
    );
  if (presentation.layout === 'pie' && rows.length > 24)
    return fail(
      '饼图最多展示 24 个扇区，请缩小查询范围或查看表格',
      '超过 24 个分组',
    );
  if (
    presentation.layout === 'pie' &&
    metrics.some(
      c =>
        c.aggregation !== 'SUM' &&
        c.aggregation !== 'COUNT' &&
        c.format !== 'count',
    )
  )
    return fail(
      `${metrics.map(column => `${column.title}（${column.aggregation ?? '未知聚合'}）`).join('、')}不能用于占比，请选择 SUM 或 COUNT 指标`,
      '需要可相加指标',
    );
  // Resolve labels per dimension identity across all series; conflicting names fall back to ID.
  const dimensionLabels = new Map<
    string,
    Map<string, { text: string; detail: string }>
  >();
  for (const binding of plan.schema
    .filter(c => c.labelFor)
    .map(c => ({ dimension: c.labelFor!, metric: c.alias }))) {
    const dimension = dimensions.find(c => c.alias === binding.dimension);
    if (!dimension) return fail('维度显示字段引用无效');
    const metric = plan.schema.find(c => c.alias === binding.metric)!;
    const names = new Map<string, Set<string>>();
    const ids = new Map<string, string>();
    for (const row of rows) {
      const key = analysisRowKey(row, [dimension]);
      ids.set(
        key,
        formatAnalysisValue(row[dimension.alias], dimension, plan.timeZone),
      );
      const values = names.get(key) ?? new Set<string>();
      const raw = row[metric.alias];
      values.add(
        raw === null || raw === undefined || String(raw).trim() === ''
          ? ''
          : formatAnalysisValue(raw, metric, plan.timeZone),
      );
      names.set(key, values);
    }
    const resolved = new Map(
      [...names].map(([key, values]) => [
        key,
        values.size === 1 && !values.has('') ? [...values][0] : ids.get(key)!,
      ]),
    );
    const counts = new Map<string, number>();
    for (const name of resolved.values())
      counts.set(name, (counts.get(name) ?? 0) + 1);
    dimensionLabels.set(
      binding.dimension,
      new Map(
        [...resolved].map(([key, name]) => {
          const id = ids.get(key)!;
          const detail = name === id ? id : `${name} · ${id}`;
          return [key, { text: counts.get(name)! > 1 ? detail : name, detail }];
        }),
      ),
    );
  }
  const splitValues = split
    ? [
        ...new Map(
          rows.map(row => [analysisRowKey(row, [split]), row[split.alias]]),
        ).entries(),
      ]
    : [['', null] as const];
  splitValues.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  if (splitValues.length * metrics.length > 12)
    return fail(
      '图表最多展示 12 个系列，请缩小系列范围或减少指标',
      '超过 12 个系列',
    );
  for (const [identity, value] of splitValues)
    for (const m of metrics)
      result.series.push({
        key: `s${result.series.length}`,
        title: split
          ? `${dimensionLabels.get(split.alias)?.get(identity)?.text ?? formatAnalysisValue(value, split, plan.timeZone)} · ${m.title}`
          : m.title,
        tooltipTitle: split
          ? `${dimensionLabels.get(split.alias)?.get(identity)?.detail ?? formatAnalysisValue(value, split, plan.timeZone)} · ${m.title}`
          : m.title,
        alias: m.alias,
        unit: m.unit,
      });
  const byX = new Map<string, AnalysisProjection['points'][number]>();
  const tuples = new Set<string>();
  for (const row of rows) {
    const tuple = analysisRowKey(row, dimensions);
    if (tuples.has(tuple))
      return fail(
        '结果包含重复分组，请重新运行查询或查看表格',
        '结果包含重复分组',
      );
    tuples.add(tuple);
    const key = analysisRowKey(row, [x]);
    let point = byX.get(key);
    if (!point) {
      point = {
        identity: key,
        label: dimensionLabels.get(x.alias)?.get(key)?.text ?? null,
        tooltipLabel: dimensionLabels.get(x.alias)?.get(key)?.detail ?? null,
        x:
          result.continuous || x.valueType === 'datetime'
            ? (row[x.alias] as number)
            : row[x.alias] === null
              ? null
              : String(row[x.alias]),
      };
      for (const s of result.series) point[s.key] = null;
      byX.set(key, point);
    }
    const splitIndex = split
      ? splitValues.findIndex(([id]) => id === analysisRowKey(row, [split]))
      : 0;
    metrics.forEach((m, i) => {
      point![`s${splitIndex * metrics.length + i}`] = row[m.alias] as
        number | null;
    });
  }
  result.points = [...byX.values()];
  // Recharts stacks Number(null) as zero; reject missing combinations instead of inventing measurements.
  if (
    presentation.stacked &&
    visualization.stacked &&
    result.points.some(point =>
      result.series.some(series => point[series.key] === null),
    )
  )
    return fail('堆叠包含空值或缺失系列组合，请关闭堆叠或查看表格');
  if (result.continuous)
    result.points.sort((a, b) => (a.x as number) - (b.x as number));
  return result;
}
