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
import type {
  AnalysisSession,
  ViewDefinition,
} from '../contracts/viewModel.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { describeConfiguredFilter } from '../filter/describeConfiguredFilter.js';
import {
  SortDirection,
  AggregationGroupType as G,
  AggregationMetricType as M,
  AggregationExpressionType as E,
  DerivedExpressionType as D,
  HavingExpressionType as H,
  ComparisonOperator as C,
  FilterOperator as Op,
  type FilterExpression,
  type HavingExpression,
  type AggregationExpression,
  type DerivedExpression,
} from '@ahoo-wang/fetcher-wow';
import { describeFilter } from '../filter/filterSummary.js';

// HAVING uses the existing filter vocabulary only for display, never for execution.
function havingDisplayFilter(
  value: DeepReadonly<HavingExpression>,
): FilterExpression {
  switch (value.type) {
    case H.CONDITION:
      return {
        op: (
          {
            [C.EQ]: Op.EQ,
            [C.NE]: Op.NE,
            [C.GT]: Op.GT,
            [C.GTE]: Op.GTE,
            [C.LT]: Op.LT,
            [C.LTE]: Op.LTE,
          } as const
        )[value.operator],
        field: value.metric,
        value: value.value,
      };
    case H.BETWEEN:
      return {
        op: Op.BETWEEN,
        field: value.metric,
        lowerBound: value.lower,
        upperBound: value.upper,
      };
    case H.IN:
      return { op: Op.IN, field: value.metric, values: [...value.values] };
    case H.IS_NULL:
      return {
        op: value.negated ? Op.IS_NOT_NULL : Op.IS_NULL,
        field: value.metric,
      };
    case H.AND:
    case H.OR:
      return {
        op: value.type === H.AND ? Op.AND : Op.OR,
        operands: value.operands.map(havingDisplayFilter),
      };
  }
}

function describeExpression(
  expression: DeepReadonly<AggregationExpression | DerivedExpression>,
  fieldLabel: (field: string) => string,
  metricLabel: (alias: string) => string,
): string {
  switch (expression.type) {
    case E.FIELD:
      return fieldLabel(expression.field);
    case D.METRIC_REF:
      return metricLabel(expression.metric);
    case E.CONSTANT:
    case D.CONSTANT:
      return String(expression.value);
    case E.BINARY:
    case D.BINARY:
      return `(${describeExpression(expression.left, fieldLabel, metricLabel)} ${{ ADD: '+', SUBTRACT: '−', MULTIPLY: '×', DIVIDE: '÷' }[expression.operator]} ${describeExpression(expression.right, fieldLabel, metricLabel)})`;
  }
}

/** All labels and values describe the successful execution, never the working query. */
export function AnalysisResultSummary({
  result,
  definition,
  compilers,
}: {
  result: NonNullable<AnalysisSession['result']>;
  definition: DeepReadonly<ViewDefinition>;
  compilers: FilterCompilerRegistry;
}) {
  const { config, plan, rows, receivedAt } = result;
  const scope = definition.analysis?.scopes?.find(
    scope => scope.id === config.scope?.id,
  );
  const filter =
    describeConfiguredFilter(
      config.filters.root,
      definition.fields,
      definition.allowedOperators,
      compilers,
      plan.timeZone,
    )?.text ?? '全部记录';
  return (
    <section
      aria-label="执行口径"
      className="fve:flex fve:flex-col fve:gap-2 fve:rounded-md fve:bg-muted/50 fve:p-3 fve:text-xs fve:text-muted-foreground"
    >
      <p>筛选：{filter}</p>
      <p>
        分组：{config.dimensions.map(item => item.title).join('、') || '不分组'}{' '}
        · 指标：
        {config.metrics
          .map(item => {
            const aggregation = plan.schema.find(
              column => column.alias === item.alias,
            )?.aggregation;
            const labels: Record<string, string> = {
              SUM: '求和',
              AVG: '平均值',
              MIN: '最小值',
              MAX: '最大值',
              STDDEV: '标准差',
              VARIANCE: '方差',
              COUNT: '计数',
              DISTINCT_COUNT: '去重计数（精确性取决于后端）',
              PERCENTILE: '百分位（近似）',
              DERIVED: '指标公式',
              ANY: '代表值',
            };
            return `${item.title}${aggregation ? `（${labels[aggregation] ?? aggregation}）` : ''}`;
          })
          .join('、')}
      </p>
      <p>
        本地接收：{new Date(receivedAt).toLocaleString('zh-CN')} · 已返回{' '}
        {rows.length} 行
        {plan.query.limit !== undefined
          ? ` · 返回上限 ${plan.query.limit} 行`
          : ''}
      </p>
      {plan.query.limit !== undefined && rows.length >= plan.query.limit && (
        <p>达到返回上限，可能仍有其他分组。</p>
      )}
      <details>
        <summary className="fve:cursor-pointer">查看本次口径</summary>
        <p>
          统计对象：{scope?.label ?? config.scope?.id ?? '根记录'} · 时区：
          {plan.timeZone ?? 'UTC'}
        </p>
        {plan.query.elements?.map((element, index) => (
          <p key={index}>
            元素 {index + 1}：
            {config.scope?.filters[index]
              ? (describeConfiguredFilter(
                  config.scope.filters[index].root,
                  scope?.elements[index]?.fields ?? [],
                  definition.allowedOperators,
                  compilers,
                  plan.timeZone,
                )?.text ?? '全部记录')
              : element.path}
          </p>
        ))}
        {plan.query.groupBy?.map(group => (
          <p key={group.alias}>
            {`${plan.schema.find(column => column.alias === group.alias)?.title ?? group.alias} ${
              group.type === G.TERMS
                ? group.missingKey === undefined
                  ? '缺失值：不参与分组'
                  : `缺失值归入：${group.missingKey}`
                : group.type === G.HISTOGRAM
                  ? `分桶间隔：${group.interval}`
                  : `日期单位：${group.unit} · 时区：${group.timeZone ?? plan.timeZone ?? 'UTC'} · 日期空桶补齐：${group.dense ? '开启' : '关闭'}`
            }`}
          </p>
        ))}
        {plan.query.metrics.map(metric =>
          'expression' in metric ? (
            <p key={metric.alias} className="fve:break-words">
              {`${plan.schema.find(column => column.alias === metric.alias)?.title ?? metric.alias} ${metric.type === M.PERCENTILE ? `P${metric.percentile}` : metric.type === M.DERIVED ? '公式' : '统计表达式'}：${describeExpression(
                metric.expression,
                field =>
                  (scope?.fields ?? definition.fields).find(
                    item => item.field === field,
                  )?.label ?? field,
                alias =>
                  plan.schema.find(column => column.alias === alias)?.title ??
                  alias,
              )}`}
            </p>
          ) : null,
        )}
        {plan.query.metrics.map(metric =>
          'filter' in metric && metric.filter ? (
            <p key={metric.alias}>
              {plan.schema.find(column => column.alias === metric.alias)
                ?.title ?? metric.alias}{' '}
              统计条件：
              {
                describeFilter(
                  metric.filter,
                  scope?.fields ?? definition.fields,
                  { timeZone: plan.timeZone },
                  false,
                ).text
              }
            </p>
          ) : null,
        )}
        {plan.query.having && (
          <p>
            结果筛选：
            {
              describeFilter(
                havingDisplayFilter(plan.query.having),
                plan.schema
                  .filter(column => column.role === 'metric')
                  .map(column => ({
                    field: column.alias,
                    label: column.title,
                    type: 'number',
                  })),
                {},
                false,
              ).text
            }
          </p>
        )}
        <p>
          服务端排序：
          {plan.query.sort
            ?.map(
              item =>
                `${plan.schema.find(column => column.alias === item.field)?.title ?? item.field} ${item.direction === SortDirection.DESC ? '降序' : '升序'}`,
            )
            .join('、') || '未指定'}
        </p>
      </details>
      <p>数据表用于核对本次聚合返回结果，不代表业务全量数据。</p>
    </section>
  );
}
