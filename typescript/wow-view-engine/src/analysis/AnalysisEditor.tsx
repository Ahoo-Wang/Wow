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
  ANALYSIS_LIMITS,
  analysisGroupValueType,
} from './analysisCapabilities.js';
import { effectiveSortAliases } from './analysisSort.js';
import {
  analysisOutputs,
  dateLabels,
  groupNames,
  names,
} from './analysisEditorLabels.js';
import { AnalysisEditorBoundary, Choice } from './AnalysisComponentChoice.js';
import type { AggregationGroupType as Group } from '@ahoo-wang/fetcher-wow';
import {
  AggregationFunction,
  AggregationExpressionType,
} from '@ahoo-wang/fetcher-wow';
import { useState, useRef, useLayoutEffect } from 'react';
import type { FilterExtensions } from '../filter/filterReactTypes.js';
import type { AnalysisExtensions } from './analysisReactTypes.js';
import { ChevronDownIcon, GripVerticalIcon, PlusIcon } from 'lucide-react';
import { Button } from '../components/ui/button.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../components/ui/popover.js';
import { Input } from '../components/ui/input.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import {
  analysisScopeContext,
  compileAnalysisExpression,
} from './analysisCompiler.js';
import { AnalysisExpressionEditor } from './AnalysisExpressionEditor.js';
import { AnalysisScopeEditor } from './AnalysisScopeEditor.js';
import { AnalysisSortEditor } from './AnalysisSortEditor.js';
import { useListOrder } from '../lib/useListOrder.js';
import { OverlayScope } from '../lib/OverlayScope.js';
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';
import type { FilterValidationError } from '../filter/filterModel.js';
import type {
  AnalysisViewConfig,
  AnalysisCompileContext,
  AnalysisComponentConfig,
} from './analysisModel.js';
export interface AnalysisEditorProps {
  value: DeepReadonly<AnalysisViewConfig>;
  /** Last successfully applied configuration; used as the element filter undo baseline. */
  appliedValue?: DeepReadonly<AnalysisViewConfig>;
  context: AnalysisCompileContext;
  onChange(value: AnalysisViewConfig): void;
  disabled?: boolean;
  /** Whether the owning configuration surface is visible; closes transient editors when false. */
  visible?: boolean;
  errors?: readonly FilterValidationError[];
  extensions?: AnalysisExtensions & FilterExtensions;
  filterContext?: unknown;
  /** Combined validity of currently mounted element-scope filter editors. */
  onFilterValidityChange?(valid: boolean): void;
}

function ComponentList({
  kind,
  ...props
}: AnalysisEditorProps & { kind: 'dimensions' | 'metrics' }) {
  const { value, context, onChange, disabled, errors = [] } = props;
  const title = kind === 'dimensions' ? '维度' : '指标';
  const items = value[kind];
  const role = kind === 'dimensions' ? 'dimension' : 'metric';
  const metricsUsed =
    value.metrics.length + value.dimensions.filter(item => item.label).length;
  const maxMetrics =
    context.capability.limits?.maxMetrics ?? ANALYSIS_LIMITS.maxMetrics;
  const capabilityByField = new Map(
    context.capability.fields.map(field => [field.field, field]),
  );
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [openedIds, setOpenedIds] = useState<ReadonlySet<string>>(
    () => new Set(),
  );
  if (props.visible === false && expandedId !== null) setExpandedId(null);
  function change(next: DeepReadonly<AnalysisComponentConfig[]>) {
    if (disabled) return;
    const updated = {
      ...cloneSnapshot<AnalysisViewConfig>(value),
      [kind]: cloneSnapshot<AnalysisComponentConfig[]>(next),
    };
    const outputs = analysisOutputs(updated);
    updated.sort = updated.sort.filter(sort =>
      outputs.some(output => output.alias === sort.alias),
    );

    onChange(updated);
  }
  function update(index: number, patch: Partial<AnalysisComponentConfig>) {
    change(
      items.map((item, i) => (i === index ? { ...item, ...patch } : item)),
    );
  }
  const order = useListOrder({
    items,
    onChange: change,
    titleOf: item => item.title,
    orientation: 'horizontal',
    disabled,
  });
  const latest = useRef<{
    items: typeof items;
    change: typeof change;
    disabled: typeof disabled;
  } | null>(null);
  useLayoutEffect(() => {
    latest.current = { items, change, disabled };
    return () => {
      latest.current = null;
    };
  });
  function supportsGroup(
    field: AnalysisCompileContext['fields'][number],
    group: Group,
  ) {
    return (
      analysisGroupValueType(
        field.type,
        group,
        context.timeZone,
        capabilityByField.get(field.field)?.dateUnits,
      ) !== undefined
    );
  }
  const choices =
    kind === 'dimensions'
      ? [
          ...new Set(
            context.fields.flatMap(field =>
              (capabilityByField.get(field.field)?.groups ?? []).filter(group =>
                supportsGroup(field, group),
              ),
            ),
          ),
        ].map(group => groupNames[group])
      : [
          ...(context.capability.count ? ['count'] : []),
          ...(context.capability.fields.some(field => field.any)
            ? ['any']
            : []),
          ...(context.capability.fields.some(field => field.functions.length)
            ? ['numeric']
            : []),
        ];
  choices.push(
    ...Object.keys(props.extensions?.analysis ?? {}).filter(
      name =>
        !names[name] &&
        props.extensions?.analysis?.[name]?.roles?.includes(role),
    ),
  );
  const canAdd =
    !disabled &&
    choices.length > 0 &&
    (kind === 'dimensions'
      ? items.length <
          (context.capability.limits?.maxGroups ?? ANALYSIS_LIMITS.maxGroups) &&
        effectiveSortAliases(value.dimensions, value.sort).size <
          (context.capability.limits?.maxSort ?? ANALYSIS_LIMITS.maxSort)
      : metricsUsed < maxMetrics);
  return (
    <fieldset
      disabled={disabled}
      className="fve:m-0 fve:min-w-0 fve:border-0 fve:p-0"
    >
      <legend className="fve:sr-only">
        {title === '维度' ? '分组维度' : '统计指标'}
      </legend>
      <p id={order.instructionsId} className="fve:sr-only">
        聚焦排序按钮，使用方向键调整顺序，也可拖动。
      </p>
      <div className="fve:flex fve:min-w-0 fve:flex-col fve:gap-2 fve:@min-[32rem]/analysis-editor:flex-row">
        <span
          aria-hidden="true"
          className="fve:w-24 fve:shrink-0 fve:text-sm fve:font-medium fve:@min-[32rem]/analysis-editor:pt-3"
        >
          {title === '维度' ? '分组维度' : '统计指标'}{' '}
          <span className="fve:font-normal fve:text-muted-foreground">
            {items.length}
          </span>
        </span>
        <div className="fve:flex fve:min-w-0 fve:flex-1 fve:flex-wrap fve:items-start fve:gap-2">
          <ol
            {...order.listProps}
            className="fve:m-0 fve:flex fve:min-w-0 fve:max-w-full fve:list-none fve:flex-wrap fve:gap-2 fve:p-0"
          >
            {items.map((item, index) => {
              const label = `${title} ${index + 1}`;
              const issues = errors.filter(error => error.id === item.id);
              const component = item.component.name;
              const registry = props.extensions?.analysis;
              const CustomEditor =
                !names[component] &&
                registry &&
                Object.prototype.hasOwnProperty.call(registry, component) &&
                registry[component]?.roles?.includes(role)
                  ? registry[component].component
                  : undefined;
              const capability = capabilityByField.get(item.field ?? '');
              const readyToEdit = openedIds.has(item.id);
              const fields = readyToEdit
                ? context.fields.filter(field => {
                    const allowed = capabilityByField.get(field.field);
                    return component === 'numeric'
                      ? field.type === 'number' && !!allowed?.functions.length
                      : component === 'any'
                        ? !!allowed?.any
                        : allowed?.groups.some(
                            group =>
                              groupNames[group] === component &&
                              supportsGroup(field, group),
                          );
                  })
                : [];
              return (
                <li
                  key={item.id}
                  className="fve:relative fve:min-w-0 fve:max-w-full fve:rounded-lg fve:border fve:bg-background fve:data-invalid:border-destructive/60 fve:data-dragging:opacity-50"
                  data-invalid={issues.length > 0 || undefined}
                  data-dragging={order.draggedId === item.id || undefined}
                >
                  {(order.dropBoundary === index ||
                    (order.dropBoundary === items.length &&
                      index === items.length - 1)) && (
                    <span
                      aria-hidden="true"
                      data-slot="analysis-drop-indicator"
                      className={`fve:pointer-events-none fve:absolute fve:inset-y-0 fve:w-0.5 fve:bg-primary ${order.dropBoundary === index ? 'fve:-left-1.5' : 'fve:-right-1.5'}`}
                    />
                  )}
                  <div className="fve:flex fve:items-start">
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      className="fve:mt-2 fve:ml-1 fve:shrink-0"
                      {...order.handleProps(index)}
                      aria-label={`排序${label}`}
                      aria-describedby={order.instructionsId}
                    >
                      <GripVerticalIcon aria-hidden="true" />
                    </Button>
                    <Popover
                      open={expandedId === item.id && props.visible !== false}
                      onOpenChange={next => {
                        if (next && props.visible !== false) {
                          setOpenedIds(
                            previous => new Set([...previous, item.id]),
                          );
                          setExpandedId(item.id);
                        } else
                          setExpandedId(current =>
                            current === item.id ? null : current,
                          );
                      }}
                    >
                      <PopoverTrigger
                        aria-label={`编辑${label}`}
                        aria-description={
                          issues.map(issue => issue.message).join('；') ||
                          undefined
                        }
                        render={
                          <Button
                            variant="ghost"
                            className="fve:h-auto fve:min-h-10 fve:min-w-0 fve:max-w-72 fve:gap-2 fve:rounded-md fve:px-3 fve:py-1 fve:text-left"
                          />
                        }
                      >
                        <span className="fve:min-w-0 fve:flex-1">
                          <span
                            className="fve:block fve:truncate fve:text-sm fve:font-medium"
                            title={item.title}
                          >
                            {item.title || `未命名${title}`}
                          </span>
                          <span className="fve:block fve:truncate fve:text-xs fve:text-muted-foreground">
                            {item.expression
                              ? '公式'
                              : context.fields.find(
                                  field => field.field === item.field,
                                )?.label}
                            {item.field || item.expression ? ' · ' : ''}
                            {component === 'numeric'
                              ? ((
                                  {
                                    SUM: '求和',
                                    AVG: '平均值',
                                    MIN: '最小值',
                                    MAX: '最大值',
                                  } as Record<string, string>
                                )[item.props.function as string] ??
                                '选择统计方式')
                              : component === 'date-histogram'
                                ? `按${dateLabels[item.props.unit as string] ?? '未选粒度'}分组`
                                : component === 'histogram'
                                  ? `每 ${(item.props.interval as string | number | undefined) ?? '未设桶宽'} 分桶`
                                  : (names[component] ?? component)}
                            {issues.length > 0 && ' · 待完善'}
                          </span>
                        </span>
                        <ChevronDownIcon
                          aria-hidden="true"
                          className="fve:size-4 fve:shrink-0 fve:text-muted-foreground"
                        />
                      </PopoverTrigger>
                      <PopoverContent
                        keepMounted={openedIds.has(item.id)}
                        align="start"
                        className={
                          item.expression
                            ? 'fve:w-[42rem] fve:max-w-[calc(100vw-2rem)] fve:max-h-[75dvh] fve:overflow-y-auto fve:p-4'
                            : 'fve:w-80 fve:max-w-[calc(100vw-2rem)] fve:max-h-[75dvh] fve:overflow-y-auto fve:p-4'
                        }
                      >
                        {readyToEdit && (
                          <>
                            <PopoverTitle>{title}设置</PopoverTitle>
                            <div className="fve:grid fve:grid-cols-1 fve:gap-3">
                              {component !== 'count' &&
                                !item.expression &&
                                !CustomEditor && (
                                  <Choice
                                    label={`${label} 字段`}
                                    caption={
                                      kind === 'dimensions'
                                        ? '分组字段'
                                        : '统计字段'
                                    }
                                    value={item.field}
                                    options={fields.map(field => ({
                                      value: field.field,
                                      label: field.label,
                                    }))}
                                    disabled={disabled}
                                    invalid={issues.length > 0}
                                    onChange={field =>
                                      update(index, { field, props: {} })
                                    }
                                  />
                                )}
                              {kind === 'dimensions' && (
                                <label className="fve:flex fve:flex-col fve:gap-1 fve:text-sm">
                                  显示字段
                                  <FilterSelect
                                    label={`${label} 显示字段`}
                                    placeholder="使用分组字段"
                                    options={context.fields
                                      .filter(
                                        field =>
                                          capabilityByField.get(field.field)
                                            ?.any,
                                      )
                                      .map(field => ({
                                        value: field.field,
                                        label: field.label,
                                      }))}
                                    value={item.label?.field}
                                    disabled={
                                      disabled ||
                                      (!item.label && metricsUsed >= maxMetrics)
                                    }
                                    onClear={() =>
                                      update(index, { label: undefined })
                                    }
                                    onValueChange={field => {
                                      if (
                                        !item.label &&
                                        metricsUsed >= maxMetrics
                                      )
                                        return;
                                      const baseAlias = `${item.alias}_label`;
                                      let alias =
                                        item.label?.alias ?? baseAlias;
                                      if (!item.label) {
                                        const used = new Set(
                                          analysisOutputs(value).map(
                                            output => output.alias,
                                          ),
                                        );
                                        let suffix = 2;
                                        while (used.has(alias))
                                          alias = `${baseAlias}_${suffix++}`;
                                      }
                                      update(index, {
                                        label: {
                                          field,
                                          alias,
                                          title:
                                            context.fields.find(
                                              f => f.field === field,
                                            )?.label ?? field,
                                        },
                                      });
                                    }}
                                  />
                                </label>
                              )}
                              <Choice
                                label={`${label} 类型`}
                                caption={
                                  kind === 'dimensions'
                                    ? '分组方式'
                                    : '计算方式'
                                }
                                value={component}
                                options={choices.map(value => ({
                                  value,
                                  label: names[value] ?? value,
                                }))}
                                disabled={disabled}
                                invalid={issues.length > 0}
                                onChange={name =>
                                  update(index, {
                                    component: { name },
                                    field:
                                      name === 'count' ? undefined : item.field,
                                    expression: undefined,
                                    props: {},
                                  })
                                }
                              />
                              {!names[component] && !CustomEditor && (
                                <p role="status">
                                  未知组件：{component}
                                  。请选择可用类型修复或删除。
                                </p>
                              )}
                              {component === 'numeric' &&
                                context.capability.expressions && (
                                  <Choice
                                    label={`${label} 输入`}
                                    value={
                                      item.expression ? 'expression' : 'field'
                                    }
                                    options={[
                                      { value: 'field', label: '字段' },
                                      { value: 'expression', label: '公式' },
                                    ]}
                                    disabled={disabled}
                                    onChange={mode =>
                                      update(
                                        index,
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
                                    ? Object.values(AggregationFunction).filter(
                                        fn => {
                                          try {
                                            compileAnalysisExpression(
                                              item.expression!,
                                              fn,
                                              context,
                                            );
                                            return true;
                                          } catch {
                                            return false;
                                          }
                                        },
                                      )
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
                                        } as Record<string, string>
                                      )[value] ?? value,
                                  }))}
                                  disabled={disabled}
                                  onChange={fn =>
                                    update(index, {
                                      props: { ...item.props, function: fn },
                                    })
                                  }
                                />
                              )}
                              {component === 'date-histogram' && (
                                <Choice
                                  label={`${label} 时间粒度`}
                                  value={
                                    typeof item.props.unit === 'string'
                                      ? item.props.unit
                                      : undefined
                                  }
                                  options={(capability?.dateUnits ?? []).map(
                                    value => ({
                                      value,
                                      label: dateLabels[value] ?? value,
                                    }),
                                  )}
                                  disabled={disabled}
                                  onChange={unit =>
                                    update(index, {
                                      props: { ...item.props, unit },
                                    })
                                  }
                                />
                              )}
                              {component === 'histogram' && (
                                <label className="fve:flex fve:min-w-0 fve:max-w-full fve:flex-col fve:gap-1">
                                  桶宽
                                  <Input
                                    disabled={disabled}
                                    inputMode="decimal"
                                    aria-label={`${label} 桶宽`}
                                    aria-invalid={issues.length > 0}
                                    value={String(
                                      (item.props.interval as
                                        string | number | undefined) ?? '',
                                    )}
                                    onChange={event => {
                                      update(index, {
                                        props: {
                                          ...item.props,
                                          interval: event.target.value,
                                        },
                                      });
                                    }}
                                  />
                                </label>
                              )}
                              <label className="fve:flex fve:min-w-0 fve:max-w-full fve:flex-col fve:gap-1">
                                {title}名称
                                <Input
                                  disabled={disabled}
                                  value={item.title}
                                  aria-label={`${label} 名称`}
                                  aria-invalid={issues.length > 0}
                                  onChange={event =>
                                    update(index, { title: event.target.value })
                                  }
                                />
                              </label>
                              {CustomEditor && (
                                <AnalysisEditorBoundary key={component}>
                                  <CustomEditor
                                    value={item}
                                    context={{ ...context, role }}
                                    disabled={disabled}
                                    errors={issues}
                                    onChange={next => {
                                      const current = latest.current;
                                      if (!current || current.disabled) return;
                                      const target = current.items.find(
                                        candidate => candidate.id === item.id,
                                      );
                                      if (
                                        !target ||
                                        target.component.name !== component
                                      )
                                        return;
                                      current.change(
                                        current.items.map(candidate =>
                                          candidate.id === item.id
                                            ? {
                                                ...next,
                                                id: target.id,
                                                alias: target.alias,
                                                component: {
                                                  ...next.component,
                                                  name: component,
                                                },
                                              }
                                            : candidate,
                                        ),
                                      );
                                    }}
                                  />
                                </AnalysisEditorBoundary>
                              )}
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={disabled}
                                aria-label={`删除${label}`}
                                onClick={() => {
                                  const next =
                                    cloneSnapshot<AnalysisViewConfig>(value);
                                  next[kind].splice(index, 1);
                                  next.sort = next.sort.filter(
                                    sort =>
                                      sort.alias !== item.alias &&
                                      sort.alias !== item.label?.alias,
                                  );

                                  if (!disabled) {
                                    setExpandedId(null);
                                    setOpenedIds(previous => {
                                      const retained = new Set(previous);
                                      retained.delete(item.id);
                                      return retained;
                                    });
                                    onChange(next);
                                  }
                                }}
                              >
                                删除
                              </Button>
                            </div>
                            {component === 'numeric' && item.expression && (
                              <AnalysisExpressionEditor
                                value={item.expression}
                                context={context}
                                label={`${label} 公式`}
                                function={
                                  item.props.function as AggregationFunction
                                }
                                disabled={disabled}
                                onChange={expression =>
                                  update(index, { expression })
                                }
                              />
                            )}
                            {component === 'any' && (
                              <p className="fve:text-xs fve:text-muted-foreground">
                                代表值不保证固定，不能作为稳定分组或图表数值。
                              </p>
                            )}
                            {capability?.unit && (
                              <p className="fve:text-xs fve:text-muted-foreground">
                                单位：{capability.unit}
                              </p>
                            )}
                            {issues.map((error, i) => (
                              <p role="alert" key={i}>
                                {error.message}
                              </p>
                            ))}
                            <Button
                              variant="outline"
                              onClick={() => setExpandedId(null)}
                            >
                              完成编辑
                            </Button>
                          </>
                        )}
                      </PopoverContent>
                    </Popover>
                  </div>
                </li>
              );
            })}
          </ol>
          <p role="status" className="fve:sr-only">
            {order.announcement}
          </p>
          <Button
            variant="outline"
            className="fve:mt-2"
            disabled={!canAdd}
            onClick={() => {
              if (!canAdd) return;
              const id = crypto.randomUUID();
              setOpenedIds(previous => new Set([...previous, id]));
              setExpandedId(id);
              change([
                ...items,
                {
                  id,
                  alias: `a_${id.replace(/-/g, '')}`,
                  title: `新${title}`,
                  component: { name: choices[0] },
                  props: {},
                },
              ]);
            }}
          >
            <PlusIcon aria-hidden="true" data-icon="inline-start" />
            添加{title}
          </Button>
        </div>
      </div>
    </fieldset>
  );
}
export function AnalysisEditor(props: AnalysisEditorProps) {
  const { value, onChange, disabled = false, errors = [] } = props;
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const maxLimit =
    props.context.capability.limits?.maxLimit ?? ANALYSIS_LIMITS.maxLimit;
  const invalidLimit =
    typeof value.limit !== 'number' ||
    !Number.isSafeInteger(value.limit) ||
    value.limit < 1 ||
    value.limit > maxLimit;
  function update(patch: Partial<AnalysisViewConfig>) {
    if (!disabled)
      onChange({ ...cloneSnapshot<AnalysisViewConfig>(value), ...patch });
  }
  let context: AnalysisCompileContext;
  try {
    context = analysisScopeContext(value, props.context);
  } catch {
    context = {
      ...props.context,
      fields: [],
      capability: { fields: [], count: false },
    };
  }
  return (
    <OverlayScope visible={props.visible !== false}>
      <section
        className="fve-root fve:@container/analysis-editor fve:flex fve:min-w-0 fve:flex-col fve:gap-2"
        aria-label="分析配置"
      >
        <AnalysisScopeEditor key={value.scope?.id ?? 'root'} {...props} />
        <ComponentList {...props} context={context} kind="dimensions" />
        <ComponentList {...props} context={context} kind="metrics" />
        <details
          className="fve:group fve:rounded-lg fve:border"
          open={advancedOpen || invalidLimit}
        >
          <summary
            className="fve:flex fve:cursor-pointer fve:list-none fve:items-center fve:justify-between fve:gap-2 fve:px-3 fve:py-2 fve:text-sm fve:focus-visible:ring-2 fve:focus-visible:ring-ring fve:[&::-webkit-details-marker]:hidden"
            onClick={event => {
              event.preventDefault();
              setAdvancedOpen(!advancedOpen);
            }}
          >
            <span className="fve:flex fve:flex-wrap fve:items-center fve:gap-x-3 fve:gap-y-1">
              <span className="fve:font-medium">高级设置</span>
              <span className="fve:text-xs fve:text-muted-foreground">
                {value.sort.length ? `${value.sort.length} 项排序` : '默认排序'}{' '}
                · 最多 {value.limit} 行
              </span>
            </span>
            <ChevronDownIcon
              aria-hidden="true"
              className="fve:size-4 fve:shrink-0 fve:group-open:rotate-180"
            />
          </summary>
          <div
            className="fve:flex fve:flex-col fve:gap-4 fve:border-t fve:p-3"
            onFocus={() => setAdvancedOpen(true)}
          >
            <AnalysisSortEditor
              value={value}
              context={props.context}
              disabled={disabled}
              invalidLimit={invalidLimit}
              update={update}
            />
          </div>
        </details>
        {errors
          .filter(
            error =>
              !value.dimensions.some(item => item.id === error.id) &&
              !value.metrics.some(item => item.id === error.id),
          )
          .map((error, index) => (
            <p role="alert" key={index}>
              {error.message}
            </p>
          ))}
      </section>
    </OverlayScope>
  );
}
