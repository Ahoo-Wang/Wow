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

import {
  ANALYSIS_VISUALIZATIONS,
  getAnalysisVisualization,
} from './analysisVisualizations.js';
import { cn } from '../lib/utils.js';
import { Button } from '../components/ui/button.js';
import { Checkbox } from '../components/ui/checkbox.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import { useMemo, useId } from 'react';
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';
import type { AnalysisPlan, AnalysisRow } from './analysisModel.js';
import {
  resolveAnalysisAxes,
  resolveAnalysisMetricAliases,
  pruneAnalysisPresentation,
} from './analysisPresentation.js';
import {
  initialDisplayMapping,
  inferCapabilities,
  resolveMappings,
  validateMapping,
} from './analysisDisplaySelection.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
import { projectAnalysis } from './analysisProjection.js';
import { AnalysisVisualizationPicker } from './AnalysisVisualizationPicker.js';

export interface AnalysisPresentationEditorProps {
  value: DeepReadonly<AnalysisPresentation>;
  plan?: DeepReadonly<AnalysisPlan>;
  onChange(next: AnalysisPresentation): void;
  disabled?: boolean;
  /** Show notices for standalone editors; a surrounding result view can own them instead. */
  showIssues?: boolean;
  /** Table is inspected via the result mode switch; select a chart before configuring it. */
  chartOnly?: boolean;
  rows?: DeepReadonly<readonly AnalysisRow[]>;
}
/** Display-only controls over the executed schema. The host owns querying and stale guards. */
export function AnalysisPresentationEditor({
  value,
  plan,
  onChange,
  disabled,
  showIssues = true,
  chartOnly = false,
  rows = [],
}: AnalysisPresentationEditorProps) {
  const mappingDescriptionId = useId();
  const visualization = getAnalysisVisualization(value.layout);
  const StyleContainer = chartOnly ? 'details' : 'div';
  const locked = disabled || !plan;
  const result = useMemo(
    () => (plan ? { plan, rows } : undefined),
    [plan, rows],
  );
  const capabilities = useMemo(
    () => (chartOnly && result ? inferCapabilities(result) : undefined),
    [chartOnly, result],
  );
  const mappings = useMemo(
    () =>
      chartOnly
        ? capabilities?.find(item => item.type === value.layout)
        : result
          ? resolveMappings(value.layout, result)
          : undefined,
    [chartOnly, capabilities, value.layout, result],
  );
  const dimensions = plan?.schema.filter(c => c.role === 'dimension') ?? [];
  const candidates = mappings?.candidates ?? [];
  const axisAliases = new Set(candidates.map(candidate => candidate.x));
  const applicable = value.x
    ? candidates.filter(candidate => candidate.x === value.x)
    : candidates;
  const metricAliases = new Set(
    applicable.flatMap(candidate => candidate.metrics ?? []),
  );
  const resolvedSelection = resolveAnalysisMetricAliases(
    plan?.schema ?? [],
    value,
  );
  const metrics =
    plan?.schema.filter(
      c =>
        c.role === 'metric' &&
        c.valueType === 'number' &&
        c.aggregation !== 'ANY' &&
        (!chartOnly ||
          metricAliases.has(c.alias) ||
          resolvedSelection.includes(c.alias)),
    ) ?? [];
  const selected = resolvedSelection.filter(alias =>
    metrics.some(metric => metric.alias === alias),
  );
  const retained = cloneSnapshot<AnalysisPresentation>(value);
  if (!Array.isArray(retained.columns)) retained.columns = [];
  const staleReferences =
    !!plan &&
    [
      ...retained.columns.map(column => column.alias),
      ...(value.x ? [value.x] : []),
      ...(value.series ? [value.series] : []),
      ...(Array.isArray(value.metrics) ? value.metrics : []),
    ].some(alias => !plan.schema.some(column => column.alias === alias));
  const axes = resolveAnalysisAxes(dimensions, value);
  const x = axes.x?.alias;
  const issues =
    showIssues && !chartOnly && plan
      ? projectAnalysis(plan, rows, value).issues
      : [];
  const options = dimensions
    .filter(c => !chartOnly || axisAliases.has(c.alias))
    .map(c => ({ value: c.alias, label: c.title }));
  const mappingIssues =
    chartOnly && result && value.layout !== 'table'
      ? validateMapping(value.layout, value, result)
      : [];
  const update = (patch: Partial<AnalysisPresentation>) => {
    if (!locked)
      onChange({
        ...retained,
        ...patch,
      });
  };
  const changeLayout = (layout: AnalysisPresentation['layout']) => {
    if (!locked)
      onChange(
        chartOnly && value.layout === 'table'
          ? initialDisplayMapping(value, plan!, layout, rows)
          : { ...retained, layout },
      );
  };
  return (
    <div
      className="fve:flex fve:min-w-0 fve:flex-col fve:gap-3"
      aria-label="图表设置"
    >
      <div
        className={cn(
          'fve:gap-3',
          chartOnly
            ? 'fve:grid fve:grid-cols-1'
            : 'fve:flex fve:flex-wrap fve:items-end',
        )}
      >
        {chartOnly ? (
          <AnalysisVisualizationPicker
            value={value.layout}
            capabilities={capabilities}
            disabled={locked}
            onChange={changeLayout}
          />
        ) : (
          <FilterSelect
            label="图表类型"
            options={ANALYSIS_VISUALIZATIONS}
            value={value.layout}
            disabled={locked}
            onValueChange={changeLayout}
          />
        )}
        {chartOnly && value.layout !== 'table' && (
          <h3
            data-slot="analysis-mapping-heading"
            tabIndex={-1}
            className="fve:mt-2 fve:text-sm fve:font-medium"
          >
            数据映射
          </h3>
        )}
        {mappingIssues.length > 0 && (
          <p role="status" className="fve:text-xs fve:text-destructive">
            {mappingIssues.join('；')}
          </p>
        )}
        {visualization?.axes && (
          <>
            <label className="fve:flex fve:min-w-0 fve:flex-col fve:gap-1">
              <span className="fve:text-xs fve:text-muted-foreground">
                {visualization.continuous ? '连续轴' : '分类'}
              </span>
              <FilterSelect
                label="横轴维度"
                options={options}
                value={x}
                disabled={locked}
                onValueChange={next =>
                  update({
                    x: next,
                    series:
                      value.series && next !== value.series
                        ? value.series
                        : dimensions.length === 2
                          ? dimensions.find(column => column.alias !== next)
                              ?.alias
                          : undefined,
                  })
                }
              />
            </label>
            {visualization?.series && dimensions.length > 1 && (
              <label className="fve:flex fve:min-w-0 fve:flex-col fve:gap-1">
                <span className="fve:text-xs fve:text-muted-foreground">
                  拆分系列
                </span>
                <FilterSelect
                  label="系列维度"
                  placeholder="不拆分系列"
                  options={dimensions
                    .filter(
                      c =>
                        c.alias !== x &&
                        (!chartOnly ||
                          applicable.some(
                            candidate => candidate.series === c.alias,
                          )),
                    )
                    .map(c => ({ value: c.alias, label: c.title }))}
                  value={axes.series?.alias}
                  disabled={locked}
                  onClear={
                    dimensions.length === 2
                      ? undefined
                      : () => update({ series: undefined })
                  }
                  onValueChange={series => update({ series })}
                />
              </label>
            )}
          </>
        )}

        {value.layout !== 'table' && (
          <fieldset
            className="fve:m-0 fve:flex fve:min-w-0 fve:flex-wrap fve:gap-3 fve:border-0 fve:p-0"
            disabled={locked}
          >
            <legend className="fve:mb-1 fve:text-xs fve:text-muted-foreground">
              显示指标
            </legend>
            {metrics.map((metric, index) => (
              <label
                key={metric.alias}
                className="fve:flex fve:items-center fve:gap-2 fve:text-sm"
              >
                <Checkbox
                  aria-describedby={
                    chartOnly && !metricAliases.has(metric.alias)
                      ? `${mappingDescriptionId}-${index}`
                      : undefined
                  }
                  aria-invalid={chartOnly && !metricAliases.has(metric.alias)}
                  checked={selected.includes(metric.alias)}
                  disabled={
                    locked ||
                    (chartOnly &&
                      !metricAliases.has(metric.alias) &&
                      !selected.includes(metric.alias))
                  }
                  onCheckedChange={checked =>
                    update({
                      metrics: checked
                        ? visualization?.donut
                          ? [metric.alias]
                          : [...selected, metric.alias]
                        : selected.filter(alias => alias !== metric.alias),
                    })
                  }
                />
                {metric.title}
                {chartOnly && !metricAliases.has(metric.alias) && (
                  <span
                    aria-hidden="true"
                    id={`${mappingDescriptionId}-${index}`}
                    className="fve:text-xs fve:text-destructive"
                  >
                    {result
                      ? (validateMapping(
                          value.layout,
                          { ...value, metrics: [metric.alias] },
                          result,
                        )[0] ?? '当前映射不可用')
                      : '当前映射不可用'}
                  </span>
                )}
              </label>
            ))}
          </fieldset>
        )}
        {(visualization?.orientation ||
          visualization?.stacked ||
          visualization?.donut) && (
          <StyleContainer>
            {chartOnly && (
              <summary className="fve:cursor-pointer fve:text-sm">
                样式设置
              </summary>
            )}
            <div className="fve:mt-3 fve:flex fve:flex-col fve:gap-3">
              {' '}
              {visualization?.orientation && (
                <label className="fve:flex fve:min-w-0 fve:flex-col fve:gap-1">
                  <span className="fve:text-xs fve:text-muted-foreground">
                    方向
                  </span>
                  <FilterSelect
                    label="柱状图方向"
                    options={[
                      { value: 'vertical', label: '纵向' },
                      { value: 'horizontal', label: '横向' },
                    ]}
                    value={value.orientation ?? 'vertical'}
                    disabled={locked}
                    onValueChange={orientation => update({ orientation })}
                  />
                </label>
              )}
              {visualization?.stacked && (
                <label className="fve:flex fve:items-center fve:gap-2 fve:text-sm">
                  <Checkbox
                    checked={value.stacked ?? false}
                    disabled={locked}
                    onCheckedChange={stacked => update({ stacked })}
                  />
                  堆叠
                </label>
              )}
              {visualization?.donut && (
                <label className="fve:flex fve:items-center fve:gap-2 fve:text-sm">
                  <Checkbox
                    checked={value.donut ?? false}
                    disabled={locked}
                    onCheckedChange={donut => update({ donut })}
                  />
                  环形
                </label>
              )}
            </div>
          </StyleContainer>
        )}
      </div>
      {staleReferences && (
        <Button
          variant="outline"
          disabled={locked}
          onClick={() => {
            if (locked) return;
            const repaired = pruneAnalysisPresentation(
              value,
              dimensions,
              plan!.schema.filter(column => column.role === 'metric'),
            );
            onChange(
              value.layout === 'table'
                ? repaired
                : initialDisplayMapping(repaired, plan!, value.layout, rows),
            );
          }}
        >
          按当前结果修复失效映射
        </Button>
      )}
      {issues.length > 0 && (
        <p role="status" className="fve:text-sm fve:text-muted-foreground">
          {issues.join('；')}。数据表仍可查看。
        </p>
      )}
    </div>
  );
}
