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

import { Fetcher } from '@ahoo-wang/fetcher';
import {
  SnapshotQueryClient,
  EventStreamQueryClient,
  AggregationGroupType,
  FilterOperator,
  AggregationFunction,
  SortDirection,
  AggregationDateUnit,
} from '@ahoo-wang/fetcher-wow';
import {
  adaptWowAnalysisSchema,
  createFilterConfiguration,
  type ViewDefinition,
  type AnalysisViewInstance,
  type RecordData,
  type RecordViewDefinition,
  type ViewInstanceList,
  readRecordValue,
} from '@ahoo-wang/fetcher-view-engine';

const labels: Record<string, string> = {
  'state.status': '补偿状态',
  'state.recoverable': '可恢复性',
  'state.retryState.retries': '已重试次数',
  'state.retrySpec.maxRetries': '最大重试次数',
  'state.executeAt': '执行时间',
  firstEventTime: '首次记录时间',
  createTime: '事件批次时间',
  body: '事件条目',
  'body.name': '事件类型',
  eventTime: '最后事件时间',
  'state.function.contextName': '业务上下文',
  'state.function.processorName': '处理器',
  'state.function.name': '处理函数',
};
export interface CompensationAnalysisConnectionOptions {
  baseURL: string;
  kind?: 'record' | 'analysis';
  model?: 'SNAPSHOT' | 'EVENT_STREAM';
  fetcher?: Fetcher;
  signal?: AbortSignal;
  now?: number;
}
/** Application-level connection: all transport/auth remains in the existing Fetcher/Wow client. */
export async function connectCompensation({
  baseURL,
  kind = 'analysis',
  model = 'SNAPSHOT',
  fetcher,
  signal,
  now = Date.now(),
}: CompensationAnalysisConnectionOptions) {
  if (kind === 'record' && model !== 'SNAPSHOT')
    throw new Error('数据视图需要补偿快照 Schema');
  const url = new URL(baseURL);
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  )
    throw new Error('服务地址必须是无登录信息和查询参数的 HTTP(S) 地址');
  const transport =
    fetcher ??
    new Fetcher({ baseURL: baseURL.replace(/\/$/, ''), timeout: 25000 });
  const resource = model === 'SNAPSHOT' ? 'snapshot' : 'event';
  const response = await transport.get(`/execution_failed/${resource}/schema`, {
    signal,
  });
  const rawSchema: unknown = await response.json();
  const fieldLabels =
    kind === 'record'
      ? { ...labels, aggregateId: '补偿记录 ID', version: '版本' }
      : labels;
  const schema = adaptWowAnalysisSchema(rawSchema, {
    labels: fieldLabels,
    units: {
      'state.retryState.retries': '次',
      'state.retrySpec.maxRetries': '次',
    },
  });
  if (schema.model !== model) throw new Error(`补偿示例需要 ${model} Schema`);
  const fields = schema.fields.filter(field =>
    Object.prototype.hasOwnProperty.call(fieldLabels, field.field),
  );
  const optionLabels: Record<string, string> = {
    FAILED: '失败',
    PREPARED: '等待补偿',
    SUCCEEDED: '已成功',
    RECOVERABLE: '可恢复',
    UNRECOVERABLE: '不可恢复',
    UNKNOWN: '未知',
  };
  for (const field of fields) {
    if (field.options)
      field.options = field.options.map(option => ({
        ...option,
        label: optionLabels[String(option.value)] ?? option.label,
      }));
  }
  const eventNames: Record<string, string> = {
    execution_failed_created: '创建失败记录',
    compensation_prepared: '准备补偿',
    execution_failed_applied: '记录执行失败',
    execution_success_applied: '记录执行成功',
    retry_spec_applied: '更新重试策略',
    recoverable_marked: '标记可恢复性',
    function_changed: '变更处理函数',
  };
  const eventName = schema.capability.scopes
    ?.find(scope => scope.id === 'body')
    ?.fields.find(field => field.field === 'name');
  if (eventName)
    eventName.options = Object.entries(eventNames).map(([value, label]) => ({
      value,
      label,
    }));
  for (const field of schema.capability.fields) {
    if (
      field.field === 'state.retryState.retries' ||
      field.field === 'state.retrySpec.maxRetries'
    )
      field.numberFormat = { maximumFractionDigits: 2 };
  }
  if (kind === 'record') {
    const recordFields = fields.map(field => {
      const capabilities = readRecordValue(
        rawSchema as RecordData,
        `root.properties.${field.field.split('.').join('.properties.')}.capabilities`,
      );
      return {
        ...field,
        sortable: Array.isArray(capabilities) && capabilities.includes('SORT'),
        cellRenderer: {
          name: field.options
            ? 'status'
            : field.type === 'datetime'
              ? 'date-time'
              : field.type === 'number'
                ? 'number'
                : 'text',
        },
        summaryFunctions: [],
      };
    });
    if (!recordFields.some(field => field.field === 'aggregateId'))
      throw new Error('补偿快照缺少可展示的 aggregateId');
    const definition: RecordViewDefinition = {
      id: 'compensation-snapshot-records',
      title: '补偿记录',
      sourceId: 'compensation-snapshot',
      timeZone: 'Asia/Shanghai',
      fields: recordFields,
      record: { rowKey: 'aggregateId', allowedLayouts: ['table'] },
    };
    const columns = [
      'aggregateId',
      'state.status',
      'state.recoverable',
      'state.retryState.retries',
      'state.function.contextName',
      'state.function.processorName',
      'state.function.name',
      'state.executeAt',
      'firstEventTime',
      'eventTime',
    ];
    const instances: ViewInstanceList = {
      defaultInstanceId: 'records',
      instances: [
        {
          id: 'records',
          definitionId: definition.id,
          title: '补偿记录快照',
          kind: 'record',
          scope: { type: 'public', source: 'system' },
          revision: 'system-v1',
          config: {
            filters: createFilterConfiguration({
              id: 'root',
              component: { name: 'builtin' },
              operator: FilterOperator.MATCH_ALL,
              props: {},
            }),
            sort: ['eventTime', 'aggregateId']
              .filter(path =>
                recordFields.some(
                  field => field.field === path && field.sortable,
                ),
              )
              .map(field => ({ field, direction: SortDirection.DESC })),
            pagination: { mode: 'paged', size: 20 },
            presentation: {
              layout: 'table',
              table: {
                columns: columns
                  .filter(path =>
                    recordFields.some(field => field.field === path),
                  )
                  .map(field => ({
                    id: field,
                    field,
                    kind: 'field',
                    width: field === 'aggregateId' ? 260 : 180,
                  })),
              },
            },
          },
        },
      ],
    };
    return {
      model,
      definition,
      instances,
      source: new SnapshotQueryClient<RecordData>({
        fetcher: transport,
        basePath: '/execution_failed',
      }),
    };
  }
  const available = new Set(fields.map(field => field.field));
  const definition: ViewDefinition = {
    id: `compensation-${resource}-analysis`,
    title: model === 'SNAPSHOT' ? '补偿执行分析' : '补偿事件分析',
    sourceId: `compensation-${resource}`,
    timeZone: 'Asia/Shanghai',
    fields,
    analysis: {
      ...schema.capability,
      fields: schema.capability.fields.filter(field =>
        available.has(field.field),
      ),
    },
  };
  const source =
    model === 'SNAPSHOT'
      ? new SnapshotQueryClient<RecordData>({
          fetcher: transport,
          basePath: '/execution_failed',
        })
      : new EventStreamQueryClient<RecordData>({
          fetcher: transport,
          basePath: '/execution_failed',
        });
  const capability = (field: string) =>
    definition.analysis!.fields.find(item => item.field === field);
  const numeric = capability('state.retryState.retries')?.functions.includes(
    AggregationFunction.AVG,
  );
  const status = capability('state.status')?.groups.includes(
    AggregationGroupType.TERMS,
  );
  const timeField = model === 'SNAPSHOT' ? 'firstEventTime' : 'createTime';
  const temporal =
    capability(timeField)?.groups.includes(
      AggregationGroupType.DATE_HISTOGRAM,
    ) &&
    fields
      .find(field => field.field === timeField)
      ?.operators?.includes(FilterOperator.GTE);
  const all = createFilterConfiguration({
    id: 'root',
    component: { name: 'builtin' },
    operator: FilterOperator.MATCH_ALL,
    props: {},
  });
  const count = {
    id: 'count',
    alias: 'records',
    title: model === 'SNAPSHOT' ? '补偿记录数' : '事件流批次数',
    component: { name: 'count' },
    props: {},
  };
  const instances: AnalysisViewInstance[] = [];
  const make = (
    id: string,
    title: string,
    config: AnalysisViewInstance['config'],
  ) =>
    instances.push({
      id,
      definitionId: definition.id,
      title,
      kind: 'analysis',
      revision: 'system-v1',
      scope: { type: 'public', source: 'system' },
      config,
    });
  make('overview', model === 'SNAPSHOT' ? '补偿概览' : '事件批次概览', {
    filters: all,
    dimensions: [],
    metrics: [
      count,
      ...(numeric
        ? [
            {
              id: 'avg',
              alias: 'avgRetries',
              title: '平均重试次数',
              field: 'state.retryState.retries',
              component: { name: 'numeric' },
              props: { function: AggregationFunction.AVG },
            },
          ]
        : []),
    ],
    sort: [],
    limit: 1,
    presentation: { layout: 'metric', columns: [] },
  });
  if (status)
    make('status', '补偿状态分布', {
      filters: all,
      dimensions: [
        {
          id: 'status',
          field: 'state.status',
          alias: 'status',
          title: '补偿状态',
          component: { name: 'terms' },
          props: {},
        },
      ],
      metrics: [count],
      sort: [{ alias: 'records', direction: SortDirection.DESC }],
      limit: 12,
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'status',
        metrics: ['records'],
        orientation: 'horizontal',
      },
    });
  if (temporal)
    make('trend', model === 'SNAPSHOT' ? '新增记录趋势' : '事件批次趋势', {
      filters: createFilterConfiguration({
        id: 'recent',
        field: timeField,
        component: { name: 'builtin' },
        operator: FilterOperator.GTE,
        props: { value: now - 30 * 86400000 },
      }),
      dimensions: [
        {
          id: 'day',
          field: timeField,
          alias: 'day',
          title: model === 'SNAPSHOT' ? '首次记录日期' : '批次创建日期',
          component: { name: 'date-histogram' },
          props: { unit: AggregationDateUnit.DAY },
        },
      ],
      metrics: [count],
      sort: [{ alias: 'day', direction: SortDirection.ASC }],
      limit: 40,
      presentation: {
        layout: 'line',
        columns: [],
        x: 'day',
        metrics: ['records'],
      },
    });
  if (
    capability('state.retryState.retries')?.groups.includes(
      AggregationGroupType.HISTOGRAM,
    )
  )
    make('retries', '重试次数分布', {
      filters: all,
      dimensions: [
        {
          id: 'retries',
          field: 'state.retryState.retries',
          alias: 'retries',
          title: '已重试次数',
          component: { name: 'histogram' },
          props: { interval: 1 },
        },
      ],
      metrics: [count],
      sort: [{ alias: 'retries', direction: SortDirection.ASC }],
      limit: 100,
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'retries',
        metrics: ['records'],
      },
    });
  const eventScope = definition.analysis?.scopes?.find(
    scope =>
      scope.id === 'body' &&
      scope.capability.fields.some(
        field =>
          field.field === 'name' &&
          field.groups.includes(AggregationGroupType.TERMS),
      ),
  );
  if (model === 'EVENT_STREAM' && eventScope)
    make('event-types', '事件类型分布', {
      filters: all,
      scope: { id: eventScope.id, filters: eventScope.elements.map(() => all) },
      dimensions: [
        {
          id: 'event',
          field: 'name',
          alias: 'event',
          title: '事件类型',
          component: { name: 'terms' },
          props: {},
        },
      ],
      metrics: [{ ...count, title: '事件条数' }],
      sort: [{ alias: 'records', direction: SortDirection.DESC }],
      limit: 20,
      presentation: {
        layout: 'bar',
        columns: [],
        x: 'event',
        metrics: ['records'],
        orientation: 'horizontal',
      },
    });
  return {
    model,
    definition,
    source,
    instances: {
      instances,
      defaultInstanceId: instances.some(instance => instance.id === 'status')
        ? 'status'
        : instances.some(instance => instance.id === 'event-types')
          ? 'event-types'
          : 'overview',
    },
  };
}
