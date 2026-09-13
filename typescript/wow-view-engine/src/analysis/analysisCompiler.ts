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
  compileAnalysisExpression,
  compileAnalysisValueExpression,
  compileAnalysisDerivedExpression,
  validateAnalysisDerivedExpression,
  analysisNumber,
  expressionUnit,
  derivedUnit,
  type AnalysisUnit,
} from './analysisExpressions.js';
export { compileAnalysisExpression } from './analysisExpressions.js';
import { compileAnalysisHaving } from './analysisHaving.js';
import {
  analysisMetricFilterContext,
  compileAnalysisMetricFilter,
} from './analysisMetricFilter.js';
import { sameJsonState } from '../lib/snapshot.js';
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
import {
  FilterConfigurationError,
  validateFilterJson,
} from '../filter/filterConfigurationValidation.js';
import { validateTimeZone } from '../lib/timeZone.js';
import { copy, message } from '../lib/snapshot.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisComponentConfig,
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
    capability: {
      ...scope.capability,
      features: context.capability.features,
      limits: context.capability.limits,
    },
  };
}

function builtin(
  item: DeepReadonly<AnalysisComponentConfig>,
  context: AnalysisComponentCompileContext,
  metricsById: ReadonlyMap<string, AggregationMetric>,
): AggregationGroup | AggregationMetric {
  const { field, alias, props } = item;
  switch (item.component.name) {
    case 'terms':
      return aggregation.terms(
        field!,
        alias,
        props.missingKey as string | undefined,
      );
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
        dense: props.dense as boolean | undefined,
      });
    case 'any':
      return aggregation.any(field!, alias);
    case 'count':
      return aggregation.count(alias);
    case 'distinct-count':
    case 'percentile': {
      const expression = item.expression ?? aggregation.field(field!);
      return item.component.name === 'distinct-count'
        ? aggregation.distinctCount(
            expression as Parameters<typeof aggregation.distinctCount>[0],
            alias,
          )
        : aggregation.percentile(
            expression as Parameters<typeof aggregation.percentile>[0],
            analysisNumber(props.percentile),
            alias,
          );
    }
    case 'derived':
      return aggregation.derived(
        compileAnalysisDerivedExpression(item.derivedExpression!, metricsById, {
          nodes: 0,
        }),
        alias,
      );
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
    const metricsById = new Map<string, AggregationMetric>();
    const metricsByAlias = new Map<string, AggregationMetric>();
    const valueBudget = { nodes: 0 },
      derivedBudget = { nodes: 0 };
    const metricUnits = new Map<string, AnalysisUnit>();
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
        requireValue(
          role === 'metric' ||
            (item.filters === undefined &&
              item.derivedExpression === undefined),
          '分组不能设置指标条件或派生公式',
        );
        const output = builtin(item, { ...scoped, role }, metricsById);
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
              requireValue(
                output.missingKey === undefined ||
                  (scoped.capability.features?.missingKey === true &&
                    field.type === 'string'),
                '缺失值归组未授权或不是字符串字段',
              );
              result = aggregation.terms(
                output.field,
                item.alias,
                output.missingKey,
              );
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
              requireValue(
                output.dense === undefined ||
                  (scoped.capability.features?.dense === true &&
                    typeof output.dense === 'boolean' &&
                    (!output.dense || config.dimensions.length === 1)),
                '日期补桶未授权或不是唯一日期维度',
              );
              result = aggregation.dateHistogram(output.field, {
                alias: item.alias,
                unit: output.unit,
                timeZone: context.timeZone,
                dense: output.dense,
              });
              valueType = 'datetime';
              break;
            default:
              throw new TypeError('组件未产生分组');
          }
          groups.push(result);
        } else {
          requireValue(
            output.type === Metric.DERIVED ||
              item.derivedExpression === undefined,
            '非派生指标不能设置派生公式',
          );
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
              const expression = compileAnalysisValueExpression(
                output.expression,
                { kind: 'numeric', function: output.function },
                scoped,
                valueBudget,
              );
              result = {
                type: Metric.NUMERIC,
                function: output.function,
                expression,
                alias: item.alias,
              };
              break;
            }
            case Metric.DISTINCT_COUNT:
            case Metric.PERCENTILE: {
              const key =
                output.type === Metric.DISTINCT_COUNT
                  ? 'distinctCount'
                  : 'percentile';
              requireValue(
                scoped.capability.features?.[key] === true,
                '指标能力未授权',
              );
              requireValue(
                item.expression
                  ? scoped.capability.expressions && item.field === undefined
                  : output.expression?.type ===
                      AggregationExpressionType.FIELD &&
                      output.expression.field === item.field,
                '数值表达式未授权或字段绑定不一致',
              );
              const expression = compileAnalysisValueExpression(
                output.expression,
                {
                  kind:
                    output.type === Metric.DISTINCT_COUNT
                      ? 'distinct-count'
                      : 'percentile',
                },
                scoped,
                valueBudget,
              );
              result =
                output.type === Metric.DISTINCT_COUNT
                  ? aggregation.distinctCount(expression, item.alias)
                  : aggregation.percentile(
                      expression,
                      output.percentile,
                      item.alias,
                    );
              nullable = output.type !== Metric.DISTINCT_COUNT;
              break;
            }
            case Metric.DERIVED:
              requireValue(
                scoped.capability.features?.derived === true &&
                  item.field === undefined &&
                  item.expression === undefined &&
                  item.filters === undefined &&
                  !('filter' in output),
                '派生指标未授权或设置了记录过滤/字段',
              );
              result = aggregation.derived(
                validateAnalysisDerivedExpression(
                  output.expression,
                  metricsByAlias,
                  derivedBudget,
                ),
                item.alias,
              );
              break;
            default:
              throw new TypeError('组件未产生数值指标');
          }
          if (result.type !== Metric.DERIVED) {
            const supplied = 'filter' in output ? output.filter : undefined;
            if (item.filters !== undefined || supplied !== undefined) {
              requireValue(
                scoped.capability.features?.metricFilters === true,
                '指标筛选能力未授权',
              );
              const filterContext = analysisMetricFilterContext(
                scoped,
                !!config.scope,
              );
              const compiled =
                item.filters === undefined
                  ? undefined
                  : compileFilterConfiguration(
                      item.filters,
                      filterContext.fields,
                      filterContext.allowedOperators,
                      filterContext.filterCompilers,
                      filterContext.timeZone,
                    );
              requireValue(
                !compiled?.errors.length,
                compiled?.errors.map(error => error.message).join('；') ??
                  '指标条件无效',
              );
              const normalized =
                supplied === undefined
                  ? undefined
                  : compileAnalysisMetricFilter(
                      supplied,
                      scoped,
                      !!config.scope,
                    );
              requireValue(
                supplied === undefined ||
                  !compiled ||
                  sameJsonState(normalized, compiled.expression),
                '组件指标条件与配置冲突',
              );
              const predicate = compiled?.expression ?? normalized;
              requireValue(predicate !== undefined, '指标条件无效');
              result = { ...result, filter: predicate };
            }
          }
          metrics.push(result);
          metricsById.set(item.id, result);
          metricsByAlias.set(result.alias, result);
        }
        const valueExpression =
          'expression' in result && result.type !== Metric.DERIVED
            ? result.expression
            : undefined;
        const formatField =
          valueExpression?.type === AggregationExpressionType.FIELD
            ? valueExpression.field
            : item.field;
        let numberFormat: AnalysisResultColumn['numberFormat'] =
          scoped.capability.fields.find(
            field => field.field === formatField,
          )?.numberFormat;
        let unit: AnalysisUnit =
          result.type === Metric.COUNT ? null : cap?.unit;
        if (valueExpression)
          unit =
            result.type === Metric.DISTINCT_COUNT
              ? null
              : expressionUnit(valueExpression, scoped);
        if (
          result.type === Metric.COUNT ||
          result.type === Metric.DISTINCT_COUNT
        )
          numberFormat = undefined;
        if (
          result.type === Metric.NUMERIC &&
          result.function === AggregationFunction.VARIANCE
        ) {
          unit = unit ? `${unit}·${unit}` : unit;
          if (numberFormat) {
            numberFormat = { ...numberFormat };
            delete numberFormat.style;
            delete numberFormat.currency;
            delete numberFormat.currencyDisplay;
            delete numberFormat.currencySign;
            delete numberFormat.unit;
            delete numberFormat.unitDisplay;
          }
        }
        if (result.type === Metric.DERIVED) {
          unit = derivedUnit(result.expression, metricUnits);
          requireValue(
            item.props.displayFormat === undefined ||
              item.props.displayFormat === 'number' ||
              item.props.displayFormat === 'percent',
            '派生指标展示格式无效',
          );
          requireValue(
            item.props.displayFormat !== 'percent' || unit === null,
            '只有已确认无量纲的指标才能显示为百分比',
          );
          numberFormat =
            item.props.displayFormat === 'percent'
              ? { style: 'percent', maximumFractionDigits: 2 }
              : undefined;
        }
        if (role === 'metric') metricUnits.set(item.alias, unit);
        schema.push({
          id: item.id,
          alias: item.alias,
          title: item.title,
          role,
          valueType,
          nullable,
          numberFormat,
          unit: unit ?? undefined,
          ...((role === 'dimension' || result.type === Metric.ANY) &&
          field?.options
            ? {
                options: field.options.map(option => ({
                  value: option.value,
                  label: option.label,
                })),
              }
            : {}),
          ...(result.type === Group.TERMS ||
          result.type === Group.HISTOGRAM ||
          result.type === Group.DATE_HISTOGRAM
            ? {
                group: {
                  type: result.type,
                  ...(result.type === Group.HISTOGRAM
                    ? { interval: result.interval }
                    : result.type === Group.DATE_HISTOGRAM
                      ? { unit: result.unit, timeZone: result.timeZone }
                      : {}),
                },
              }
            : {
                aggregation:
                  result.type === Metric.NUMERIC
                    ? result.function
                    : result.type,
              }),
          ...(valueType === 'datetime'
            ? { format: 'datetime' }
            : result.type === Metric.COUNT ||
                result.type === Metric.DISTINCT_COUNT
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
    let having: AggregationQuery['having'];
    if (config.having !== undefined) {
      requireValue(
        scoped.capability.features?.having === true && groups.length > 0,
        '结果筛选未授权或缺少分组',
      );
      try {
        having = compileAnalysisHaving(config.having, metricsById);
      } catch (error) {
        return {
          errors: [
            {
              id:
                error instanceof FilterConfigurationError
                  ? error.id
                  : (config.having.id ?? ''),
              message: message(error),
            },
          ],
        };
      }
    }
    const query: AggregationQuery = {
      filter: filterResult.expression,
      ...(having === undefined ? {} : { having }),
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
