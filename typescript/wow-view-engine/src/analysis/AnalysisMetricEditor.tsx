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
  AggregationFunction,
  AggregationExpressionType,
  DerivedExpressionType as D,
  FilterOperator,
} from '@ahoo-wang/fetcher-wow';
import { Button } from '../components/ui/button.js';
import { Input } from '../components/ui/input.js';
import { FilterPanel } from '../filter/FilterPanel.js';
import { createFilterConfiguration } from '../filter/filterConfiguration.js';
import { Choice } from './AnalysisComponentChoice.js';
import { AnalysisExpressionEditor } from './AnalysisExpressionEditor.js';
import { AnalysisDerivedExpressionEditor } from './AnalysisDerivedExpressionEditor.js';
import { supportsAnalysisValueField } from './analysisExpressions.js';
import { compileAnalysisExpression } from './analysisCompiler.js';
import { analysisMetricFilterContext } from './analysisMetricFilter.js';
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';
import type {
  AnalysisComponentConfig,
  AnalysisCompileContext,
} from './analysisModel.js';
import type {
  FilterExtensions,
  FilterPanelProps,
} from '../filter/filterReactTypes.js';
interface Props {
  value: DeepReadonly<AnalysisComponentConfig>;
  appliedValue?: FilterPanelProps['appliedValue'];
  context: AnalysisCompileContext;
  previousMetrics: DeepReadonly<readonly AnalysisComponentConfig[]>;
  onChange(value: AnalysisComponentConfig): void;
  label: string;
  disabled?: boolean;
  extensions?: FilterExtensions;
  filterContext?: unknown;
  elementScope?: boolean;
  editors?: FilterPanelProps['editors'];
  onFilterValidityChange?(valid: boolean): void;
}
export function AnalysisMetricEditor({
  value: item,
  appliedValue,
  context,
  previousMetrics,
  onChange,
  label,
  disabled,
  extensions,
  filterContext,
  elementScope = false,
  editors,
  onFilterValidityChange,
}: Props) {
  const component = item.component.name;
  const capability = context.capability.fields.find(
    f => f.field === item.field,
  );
  function update(patch: Partial<AnalysisComponentConfig>) {
    if (!disabled)
      onChange({ ...cloneSnapshot<AnalysisComponentConfig>(item), ...patch });
  }
  const fields = context.fields.filter(f => {
    const cap = context.capability.fields.find(c => c.field === f.field);
    return component === 'numeric'
      ? f.type === 'number' && !!cap?.functions.length
      : component === 'distinct-count'
        ? supportsAnalysisValueField(
            f.field,
            { kind: 'distinct-count' },
            context,
          )
        : component === 'percentile'
          ? f.type === 'number' && cap?.percentile === true
          : component === 'any'
            ? cap?.any
            : false;
  });
  // Formula inputs use the same UI; capabilities still depend on the metric policy.
  const expressionContext =
    component === 'numeric'
      ? context
      : {
          ...context,
          capability: {
            ...context.capability,
            fields: context.capability.fields.map(f => ({
              ...f,
              functions: supportsAnalysisValueField(
                f.field,
                {
                  kind:
                    component === 'distinct-count'
                      ? 'distinct-count'
                      : 'percentile',
                },
                context,
                true,
              )
                ? Object.values(AggregationFunction)
                : [],
            })),
          },
        };
  const metricContext = analysisMetricFilterContext(context, elementScope);
  return (
    <div className="fve:flex fve:min-w-0 fve:flex-col fve:gap-3">
      {component !== 'count' && component !== 'derived' && !item.expression && (
        <Choice
          label={`${label} 字段`}
          caption="统计字段"
          disabled={disabled}
          value={item.field}
          options={fields.map(f => ({ value: f.field, label: f.label }))}
          onChange={field =>
            update({
              field,
              props:
                component === 'percentile'
                  ? { percentile: item.props.percentile }
                  : {},
            })
          }
        />
      )}
      {['numeric', 'distinct-count', 'percentile'].includes(component) &&
        context.capability.expressions && (
          <Choice
            label={`${label} 输入`}
            value={item.expression ? 'expression' : 'field'}
            options={[
              { value: 'field', label: '字段' },
              {
                value: 'expression',
                label: '公式',
              },
            ]}
            disabled={disabled}
            onChange={mode =>
              update(
                mode === 'expression'
                  ? {
                      field: undefined,
                      expression: {
                        type: AggregationExpressionType.FIELD,
                        field: item.field ?? '',
                      },
                    }
                  : {
                      expression: undefined,
                      field:
                        item.expression?.type ===
                        AggregationExpressionType.FIELD
                          ? item.expression.field
                          : undefined,
                    },
              )
            }
          />
        )}
      {component === 'numeric' && (
        <Choice
          label={`${label} 函数`}
          value={
            typeof item.props.function === 'string'
              ? item.props.function
              : undefined
          }
          options={(item.expression
            ? Object.values(AggregationFunction).filter(fn => {
                try {
                  compileAnalysisExpression(item.expression!, fn, context);
                  return true;
                } catch {
                  return false;
                }
              })
            : (capability?.functions ?? [])
          ).map(value => ({
            value,
            label:
              (
                {
                  SUM: '求和',
                  AVG: '平均值',
                  MIN: '最小值',
                  MAX: '最大值',
                  STDDEV: '标准差',
                  VARIANCE: '方差',
                } as Record<string, string>
              )[value] ?? value,
          }))}
          disabled={disabled}
          onChange={fn =>
            update({
              props: {
                ...item.props,
                function: fn,
              },
            })
          }
        />
      )}
      {['numeric', 'distinct-count', 'percentile'].includes(component) &&
        item.expression && (
          <AnalysisExpressionEditor
            value={item.expression}
            context={expressionContext}
            label={`${label} 公式`}
            function={item.props.function as AggregationFunction}
            disabled={disabled}
            onChange={expression => update({ expression })}
          />
        )}

      {component === 'percentile' && (
        <>
          <label>
            百分位
            <Input
              aria-label={`${label} 百分位`}
              inputMode="decimal"
              disabled={disabled}
              value={
                typeof item.props.percentile === 'number' ||
                typeof item.props.percentile === 'string'
                  ? item.props.percentile
                  : ''
              }
              onChange={e =>
                update({ props: { ...item.props, percentile: e.target.value } })
              }
            />
          </label>
          <div className="fve:flex fve:gap-2">
            {[50, 95, 99].map(p => (
              <Button
                key={p}
                variant="outline"
                disabled={disabled}
                onClick={() =>
                  update({ props: { ...item.props, percentile: p } })
                }
              >
                P{p}
              </Button>
            ))}
          </div>
          <p>百分位为近似统计值。</p>
        </>
      )}
      {component === 'distinct-count' && (
        <p>
          统计不同的非空值；精确性取决于后端，不能将各组去重计数相加作为总数。
        </p>
      )}
      {component === 'derived' && (
        <>
          <AnalysisDerivedExpressionEditor
            value={item.derivedExpression ?? { type: D.CONSTANT, value: '' }}
            metrics={previousMetrics}
            label={`${label} 公式`}
            disabled={disabled}
            onChange={derivedExpression => update({ derivedExpression })}
          />
          <Choice
            label={`${label} 展示格式`}
            value={
              typeof item.props.displayFormat === 'string'
                ? item.props.displayFormat
                : 'number'
            }
            options={[
              { value: 'number', label: '数字' },
              { value: 'percent', label: '百分比' },
            ]}
            disabled={disabled}
            onChange={displayFormat =>
              update({ props: { ...item.props, displayFormat } })
            }
          />
          <p>空操作数或除零时显示无值。</p>
        </>
      )}
      {(item.filters ||
        (component !== 'derived' &&
          context.capability.features?.metricFilters)) && (
        <details>
          <summary>统计条件</summary>
          {item.filters ? (
            <>
              {component !== 'derived' ? (
                <FilterPanel
                  value={item.filters}
                  appliedValue={appliedValue}
                  fields={metricContext.fields}
                  allowedOperators={metricContext.allowedOperators}
                  timeZone={metricContext.timeZone}
                  disabled={disabled}
                  extensions={extensions}
                  editors={editors}
                  context={filterContext}
                  onValidityChange={onFilterValidityChange}
                  showQueryAction={false}
                  onApply={() => {}}
                  onChange={filters => update({ filters })}
                />
              ) : (
                <p>派生指标不支持统计条件，请移除旧条件以保留现有公式。</p>
              )}
              <Button
                disabled={disabled}
                variant="ghost"
                onClick={() => update({ filters: undefined })}
              >
                移除统计条件
              </Button>
            </>
          ) : (
            <Button
              disabled={disabled}
              variant="outline"
              onClick={() =>
                update({
                  filters: createFilterConfiguration({
                    id: crypto.randomUUID(),
                    component: { name: 'builtin' },
                    operator: FilterOperator.MATCH_ALL,
                    props: {},
                  }),
                })
              }
            >
              添加统计条件
            </Button>
          )}
        </details>
      )}
    </div>
  );
}
