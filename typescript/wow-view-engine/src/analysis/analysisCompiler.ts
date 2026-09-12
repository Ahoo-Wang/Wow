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

import { effectiveSortAliases } from './analysisSort.js';
import {
  ANALYSIS_LIMITS,
  analysisGroupValueType,
} from './analysisCapabilities.js';
import { MAX_ANALYSIS_ELEMENTS } from './analysisModel.js';
import {
  aggregation,
  AggregationGroupType as Group,
  AggregationMetricType as Metric,
  AggregationExpressionType,
  AggregationExpressionOperator,
  type AggregationExpression,
  type AggregationElement,
  type ElementFilterExpression,
  AggregationFunction,
  type AggregationDateUnit,
  SortDirection,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
} from '@ahoo-wang/fetcher-wow';
import { compileFilterConfiguration } from '../filter/filterConfigurationCompiler.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import { validateTimeZone } from '../lib/timeZone.js';
import { copy, message } from '../lib/snapshot.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisComponentConfig,
  AnalysisNumericExpression,
  AnalysisCompileContext,
  AnalysisComponentCompileContext,
  AnalysisCompileResult,
  AnalysisResultColumn,
  AnalysisViewConfig,
} from './analysisModel.js';
function requireValue(valid: unknown, text: string): asserts valid {
  if (!valid) throw new TypeError(text);
}
/** Resolve only a host-declared scope; root filter fields remain on the original context. */
export function analysisScopeContext(
  config: DeepReadonly<AnalysisViewConfig>,
  context: AnalysisCompileContext,
): AnalysisCompileContext {
  if (!config.scope) return context;
  const scopes = context.capability.scopes?.filter(
    scope => scope.id === config.scope?.id,
  );
  requireValue(scopes?.length === 1, '未授权或重复的 Elements 范围');
  const scope = scopes[0];
  return {
    ...context,
    fields: scope.fields,
    capability: { ...scope.capability, limits: context.capability.limits },
  };
}

/** Bounded numeric editor tree. Incomplete text is retained by config but rejected here. */
export function compileAnalysisExpression(
  expression: DeepReadonly<AnalysisNumericExpression>,
  fn: AggregationFunction,
  context: AnalysisCompileContext,
): AggregationExpression {
  let nodes = 0;
  const visit = (
    node: DeepReadonly<AnalysisNumericExpression>,
    depth: number,
  ): AggregationExpression => {
    requireValue(
      ++nodes <= 256 && depth <= 8 && node && typeof node === 'object',
      '数值表达式过深或过大',
    );
    switch (node.type) {
      case AggregationExpressionType.FIELD:
        requireValue(
          context.fields.some(
            field => field.field === node.field && field.type === 'number',
          ) &&
            context.capability.fields.some(
              field =>
                field.field === node.field && field.functions.includes(fn),
            ),
          '表达式字段或函数未授权',
        );
        return aggregation.field(node.field);
      case AggregationExpressionType.CONSTANT: {
        const value =
          typeof node.value === 'string' &&
          /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(node.value)
            ? Number(node.value)
            : node.value;
        requireValue(
          typeof value === 'number' && Number.isFinite(value),
          '数值常量无效',
        );
        return aggregation.constant(value);
      }
      case AggregationExpressionType.BINARY:
        requireValue(
          Object.values(AggregationExpressionOperator).includes(node.operator),
          '数值运算符无效',
        );
        return {
          type: node.type,
          operator: node.operator,
          left: visit(node.left, depth + 1),
          right: visit(node.right, depth + 1),
        };
      default:
        throw new TypeError('数值表达式类型无效');
    }
  };
  requireValue(Object.values(AggregationFunction).includes(fn), '数值函数无效');
  return visit(expression, 1);
}

function expressionUnit(
  expression: AggregationExpression,
  context: AnalysisCompileContext,
): string | undefined {
  if (expression.type === AggregationExpressionType.FIELD)
    return context.capability.fields.find(
      field => field.field === expression.field,
    )?.unit;
  if (expression.type === AggregationExpressionType.CONSTANT) return undefined;
  const left = expressionUnit(expression.left, context),
    right = expressionUnit(expression.right, context);
  if (
    expression.operator === AggregationExpressionOperator.ADD ||
    expression.operator === AggregationExpressionOperator.SUBTRACT
  )
    return left === right ? left : undefined;
  if (expression.operator === AggregationExpressionOperator.MULTIPLY)
    return left && right ? `${left}·${right}` : (left ?? right);
  return right
    ? left === right
      ? undefined
      : `${left ?? '1'}/${right}`
    : left;
}
function builtin(
  item: DeepReadonly<AnalysisComponentConfig>,
  context: AnalysisComponentCompileContext,
): AggregationGroup | AggregationMetric {
  const { field, alias, props } = item;
  switch (item.component.name) {
    case 'terms':
      return aggregation.terms(field!, alias);
    case 'histogram':
      return aggregation.histogram(field!, {
        alias,
        interval:
          typeof props.interval === 'string' &&
          /^(?:\d+|\d*\.\d+)(?:[eE][+-]?\d+)?$/.test(props.interval)
            ? Number(props.interval)
            : (props.interval as number),
      });
    case 'date-histogram':
      return aggregation.dateHistogram(field!, {
        alias,
        unit: props.unit as AggregationDateUnit,
        timeZone: context.timeZone,
      });
    case 'any':
      return aggregation.any(field!, alias);
    case 'count':
      return aggregation.count(alias);
    case 'numeric': {
      const fn = props.function as AggregationFunction;
      requireValue(
        Object.values(AggregationFunction).includes(fn),
        '请选择数值函数',
      );
      return {
        type: Metric.NUMERIC,
        function: fn,
        expression: item.expression
          ? compileAnalysisExpression(item.expression, fn, context)
          : aggregation.field(field!),
        alias,
      };
    }
    default: {
      requireValue(
        context.compilers &&
          Object.prototype.hasOwnProperty.call(
            context.compilers,
            item.component.name,
          ),
        '未知分析组件',
      );
      const compiler = context.compilers[item.component.name];
      requireValue(
        Array.isArray(compiler.roles) && compiler.roles.includes(context.role),
        '分析组件不支持当前角色',
      );
      return compiler.compile(copy(item), context);
    }
  }
}
export function compileAnalysis(
  config: DeepReadonly<AnalysisViewConfig>,
  context: AnalysisCompileContext,
): AnalysisCompileResult {
  const errors: AnalysisCompileResult['errors'] = [];
  try {
    const { presentation: _presentation, ...queryConfig } = config;
    void _presentation;
    validateFilterJson(queryConfig);
    validateTimeZone(context.timeZone);
    // 经由临时数组检查，避免 Array.isArray 的 any[] 谓词经断言收窄退化 config 的静态类型。
    requireValue(
      [config.dimensions, config.metrics, config.sort].every(list =>
        Array.isArray(list),
      ),
      '分析配置列表无效',
    );
    const defaults = ANALYSIS_LIMITS;
    const limits = {
      ...defaults,
      ...Object.fromEntries(
        Object.entries(context.capability.limits ?? {}).filter(
          ([, value]) => value !== undefined,
        ),
      ),
    };
    for (const name of Object.keys(defaults) as (keyof typeof defaults)[])
      requireValue(
        Number.isSafeInteger(limits[name]) &&
          limits[name] > 0 &&
          limits[name] <= defaults[name],
        '分析限制必须为有效的收紧值',
      );
    requireValue(
      config.dimensions.length <= limits.maxGroups &&
        config.metrics.length > 0 &&
        config.metrics.length <= limits.maxMetrics,
      '维度或指标数量超限',
    );
    const limit = config.limit ?? limits.defaultLimit;
    requireValue(
      typeof limit === 'number' &&
        Number.isSafeInteger(limit) &&
        limit > 0 &&
        limit <= limits.maxLimit,
      `最多结果行数必须为 1 至 ${limits.maxLimit} 的整数`,
    );
    const filterResult = compileFilterConfiguration(
      config.filters,
      context.fields,
      context.allowedOperators,
      context.filterCompilers,
      context.timeZone,
    );
    errors.push(...filterResult.errors);
    const scoped = analysisScopeContext(config, context);
    const elements: AggregationElement[] = [];
    if (config.scope) {
      const scope = context.capability.scopes!.find(
        item => item.id === config.scope!.id,
      )!;
      requireValue(
        scope.elements.length > 0 &&
          scope.elements.length <= MAX_ANALYSIS_ELEMENTS &&
          Array.isArray(config.scope.filters) &&
          config.scope.filters.length === scope.elements.length,
        'Elements 范围过滤配置无效',
      );
      scope.elements.forEach((element, index) => {
        const compiled = compileFilterConfiguration(
          config.scope!.filters[index],
          element.fields,
          context.allowedOperators,
          context.filterCompilers,
          context.timeZone,
        );
        errors.push(...compiled.errors);
        elements.push(
          aggregation.element(
            element.path,
            compiled.expression as ElementFilterExpression,
          ),
        );
      });
    }
    const aliases = new Set<string>();
    const ids = new Set<string>();
    const schema: AnalysisResultColumn[] = [];
    const groups: AggregationGroup[] = [];
    const metrics: AggregationMetric[] = [];
    const compile = (
      item: DeepReadonly<AnalysisComponentConfig>,
      role: AnalysisResultColumn['role'],
      errorId?: string,
    ) => {
      try {
        requireValue(
          typeof item.id === 'string' && item.id.trim() && !ids.has(item.id),
          '组件 ID 无效或重复',
        );
        ids.add(item.id);
        requireValue(
          typeof item.title === 'string' &&
            item.title.trim().length > 0 &&
            typeof item.alias === 'string' &&
            !aliases.has(item.alias),
          '输出名称无效或重复',
        );
        aggregation.count(item.alias);
        aliases.add(item.alias);
        requireValue(
          item.component &&
            typeof item.component.name === 'string' &&
            item.props &&
            typeof item.props === 'object' &&
            !Array.isArray(item.props),
          '组件属性无效',
        );
        requireValue(
          role === 'dimension' || item.label === undefined,
          '显示字段只能绑定维度',
        );
        const output = builtin(item, { ...scoped, role });
        requireValue(
          output &&
            typeof output === 'object' &&
            !Array.isArray(output) &&
            output.alias === item.alias,
          '组件必须产生绑定 alias 的单个贡献',
        );
        const field = scoped.fields.find(f => f.field === item.field);
        const cap = scoped.capability.fields.find(f => f.field === item.field);
        let valueType: AnalysisResultColumn['valueType'] = 'number';
        let nullable = true;
        let result: AggregationGroup | AggregationMetric;
        if (role === 'dimension') {
          requireValue(
            'field' in output && output.field === item.field && field && cap,
            '未授权的分组字段',
          );
          requireValue(
            cap.groups.includes(output.type as Group),
            '未授权的分组方式',
          );
          const groupValueType = analysisGroupValueType(
            field.type,
            output.type as Group,
            context.timeZone,
            cap.dateUnits,
          );
          switch (output.type) {
            case Group.TERMS:
              requireValue(groupValueType, 'terms 需要标量字段');
              valueType = groupValueType;
              result = aggregation.terms(output.field, item.alias);
              break;
            case Group.HISTOGRAM:
              requireValue(groupValueType, 'histogram 需要数值字段');
              result = aggregation.histogram(output.field, {
                alias: item.alias,
                interval: output.interval,
              });
              break;
            case Group.DATE_HISTOGRAM:
              requireValue(
                groupValueType && output.timeZone === context.timeZone,
                '日期分桶需要一致的明确时区',
              );
              requireValue(
                cap.dateUnits?.includes(output.unit),
                '未授权的时间粒度',
              );
              result = aggregation.dateHistogram(output.field, {
                alias: item.alias,
                unit: output.unit,
                timeZone: context.timeZone,
              });
              valueType = 'datetime';
              break;
            default:
              throw new TypeError('组件未产生分组');
          }
          groups.push(result);
        } else {
          switch (output.type) {
            case Metric.COUNT:
              requireValue(
                scoped.capability.count && item.field === undefined,
                'COUNT 未授权或绑定了字段',
              );
              result = aggregation.count(item.alias);
              nullable = false;
              break;
            case Metric.ANY:
              requireValue(
                'field' in output &&
                  output.field === item.field &&
                  cap?.any &&
                  field &&
                  ['string', 'number', 'boolean', 'date', 'datetime'].includes(
                    field.type ?? '',
                  ),
                'ANY 需要授权的标量字段',
              );
              valueType =
                field.type === 'date'
                  ? 'string'
                  : (field.type as AnalysisResultColumn['valueType']);
              result = aggregation.any(output.field, item.alias);
              break;
            case Metric.NUMERIC: {
              requireValue(
                Object.values(AggregationFunction).includes(output.function),
                '未授权的数值函数',
              );
              requireValue(
                item.expression
                  ? scoped.capability.expressions && item.field === undefined
                  : output.expression?.type ===
                      AggregationExpressionType.FIELD &&
                      output.expression.field === item.field,
                '数值表达式未授权或字段绑定不一致',
              );
              const expression = compileAnalysisExpression(
                output.expression,
                output.function,
                scoped,
              );
              result = {
                type: Metric.NUMERIC,
                function: output.function,
                expression,
                alias: item.alias,
              };
              break;
            }
            default:
              throw new TypeError('组件未产生数值指标');
          }
          metrics.push(result);
        }
        const formatField =
          result.type === Metric.NUMERIC &&
          result.expression.type === AggregationExpressionType.FIELD
            ? result.expression.field
            : item.field;
        schema.push({
          id: item.id,
          alias: item.alias,
          title: item.title,
          role,
          valueType,
          nullable,
          ...((role === 'dimension' || result.type === Metric.ANY) &&
          field?.options
            ? {
                options: field.options.map(option => ({
                  value: option.value,
                  label: option.label,
                })),
              }
            : {}),
          numberFormat: scoped.capability.fields.find(
            field => field.field === formatField,
          )?.numberFormat,
          ...(result.type === Metric.COUNT
            ? { aggregation: 'COUNT' as const }
            : result.type === Metric.ANY
              ? { aggregation: 'ANY' as const, unit: cap?.unit }
              : result.type === Metric.NUMERIC
                ? {
                    aggregation: result.function,
                    unit: expressionUnit(result.expression, scoped),
                  }
                : {
                    group: {
                      type: result.type,
                      ...(result.type === Group.HISTOGRAM
                        ? { interval: result.interval }
                        : result.type === Group.DATE_HISTOGRAM
                          ? { unit: result.unit, timeZone: result.timeZone }
                          : {}),
                    },
                    unit: cap?.unit,
                  }),
          ...(valueType === 'datetime'
            ? { format: 'datetime' }
            : output.type === Metric.COUNT
              ? { format: 'count' }
              : {}),
        });
      } catch (error) {
        errors.push({ id: errorId ?? item?.id ?? '', message: message(error) });
      }
    };
    config.dimensions.forEach(item => compile(item, 'dimension'));
    config.metrics.forEach(item => compile(item, 'metric'));
    for (const dimension of config.dimensions) {
      if (dimension.label === undefined) continue;
      requireValue(
        dimension.label &&
          typeof dimension.label === 'object' &&
          !Array.isArray(dimension.label),
        '维度显示字段无效',
      );
      const label = dimension.label;
      const before = schema.length;
      let labelId = `${dimension.id}:label`;
      while (ids.has(labelId)) labelId += ':label';
      compile(
        {
          id: labelId,
          component: { name: 'any' },
          field: label.field,
          alias: label.alias,
          title: label.title,
          props: {},
        },
        'metric',
        dimension.id,
      );
      if (schema.length > before)
        schema[schema.length - 1].labelFor = dimension.alias;
    }
    requireValue(
      metrics.length <= limits.maxMetrics,
      '指标与维度显示字段数量超限',
    );

    const sort = config.sort.map(item => {
      requireValue(
        aliases.has(item.alias) &&
          Object.values(SortDirection).includes(item.direction),
        '排序输出无效',
      );
      return { field: item.alias, direction: item.direction };
    });
    requireValue(
      new Set(sort.map(item => item.field)).size === sort.length,
      '排序输出重复',
    );
    requireValue(
      groups.length > 0 || sort.length === 0,
      '无分组分析不支持排序',
    );
    for (const group of groups)
      if (!sort.some(item => item.field === group.alias))
        sort.push({ field: group.alias, direction: SortDirection.ASC });
    requireValue(
      effectiveSortAliases(groups, config.sort).size <= limits.maxSort,
      '有效排序数量超限',
    );
    if (errors.length) return { errors };
    const query: AggregationQuery = {
      filter: filterResult.expression,
      ...(elements.length ? { elements } : {}),
      metrics: metrics as AggregationQuery['metrics'],
      limit,
      ...(groups.length ? { groupBy: groups, sort } : {}),
    };
    return {
      errors,
      plan: copy({
        query,
        schema,
        timeZone: context.timeZone,
      }),
    };
  } catch (error) {
    return { errors: [...errors, { id: '', message: message(error) }] };
  }
}
