/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import {
  createFilterConfiguration,
  readRecordValue,
  type RecordData,
  type RecordQuerySource,
  type RecordViewDefinition,
  type ViewFieldDefinition,
  type ViewHost,
  type ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';
import {
  FilterOperator as Op,
  type FilterExpression,
  type PagedQueryRequest,
  type PagedList,
} from '@ahoo-wang/fetcher-wow';

const definition: RecordViewDefinition = {
  id: 'readiness',
  sourceId: 'readiness',
  title: '数据视图验收',
  timeZone: 'Asia/Shanghai',
  allowedOperators: [Op.MATCH_ALL, Op.AND, Op.EQ, Op.GTE, Op.BETWEEN, Op.IN],
  fields: (
    [
      {
        field: 'meta.id',
        label: '编号',
        type: 'string',
        operators: [Op.EQ],
        cellRenderer: { name: 'measured-text' },
      },
      {
        field: 'customer.name',
        label: '客户',
        type: 'string',
        operators: [Op.EQ],
        cellRenderer: { name: 'link', options: { hrefField: 'customer.href' } },
      },
      {
        field: 'state.amount',
        label: '金额',
        type: 'number',
        operators: [Op.GTE, Op.EQ, Op.BETWEEN],
        numberFormat: { style: 'currency', currency: 'CNY' },
        cellRenderer: { name: 'number' },
      },
      {
        field: 'state.status',
        label: '状态',
        type: 'string',
        operators: [Op.IN],
        editor: { name: 'multi-select' },
        options: [
          { value: 'pending', label: '待处理' },
          { value: 'done', label: '已完成' },
        ],
        cellRenderer: { name: 'status' },
      },
      {
        field: 'tags',
        label: '标签',
        type: 'array',
        operators: [Op.IN],
        editor: { name: 'multi-select' },
        options: [
          { value: '重要', label: '重要' },
          { value: '直营', label: '直营' },
        ],
        cellRenderer: { name: 'tags' },
      },
      {
        field: 'created',
        label: '创建时间',
        type: 'datetime',
        operators: [Op.BETWEEN],
        editor: { name: 'datetime-range' },
        cellRenderer: { name: 'date-time' },
      },
      ...Array.from({ length: 94 }, (_, index) => ({
        field: `metric.n${index}`,
        label: `指标 ${index + 1}`,
        type: 'number' as const,
        operators: [Op.GTE, Op.EQ],
        cellRenderer: { name: 'number' },
      })),
    ] satisfies ViewFieldDefinition[]
  ).map((field, index) => ({
    ...field,
    sortable: true,
    group:
      index < 6 ? '基本信息' : `指标组 ${Math.floor((index - 6) / 10) + 1}`,
  })),
  record: { allowedLayouts: ['table', 'card'], rowKey: 'meta.id' },
};
const instances: ViewInstanceList = {
  defaultInstanceId: 'all',
  instances: [
    {
      id: 'all',
      definitionId: definition.id,
      kind: 'record',
      revision: 'initial',
      title: '验收订单',
      scope: { type: 'personal' },
      config: {
        filters: createFilterConfiguration({
          id: 'amount',
          operator: Op.GTE,
          component: { name: 'builtin' },
          field: 'state.amount',
          props: { value: 0 },
        }),
        sort: [],
        pagination: { mode: 'paged', size: 100 },
        presentation: {
          layout: 'table',
          table: {
            columns: definition.fields.slice(0, 30).map(field => ({
              id: field.field,
              kind: 'field',
              field: field.field,
              width: 160,
            })),
          },
        },
      },
    },
  ],
};
const rows: RecordData[] = Array.from({ length: 200 }, (_, index) => ({
  meta: { id: `R-${String(index + 1).padStart(3, '0')}` },
  customer: { name: `客户 ${index + 1}`, href: `/customers/${index + 1}` },
  state: { amount: index * 10, status: index % 2 ? 'done' : 'pending' },
  tags: index % 2 ? ['直营'] : ['重要', '直营'],
  created: 1788825600000 + index * 1000,
  metric: Object.fromEntries(
    Array.from({ length: 94 }, (_, n) => [`n${n}`, index + n]),
  ),
}));

// Development fixture: only the operators advertised above are accepted, never a full query backend.
function matches(row: RecordData, filter: FilterExpression): boolean {
  if (filter.op === Op.MATCH_ALL) return true;
  if (filter.op === Op.AND)
    return filter.operands.every(item => matches(row, item));
  if (!('field' in filter)) throw new Error('验收数据源不支持此操作符');
  const value = readRecordValue(row, filter.field);
  if (filter.op === Op.EQ) return Object.is(value, filter.value);
  if (filter.op === Op.GTE)
    return (
      typeof value === 'number' &&
      typeof filter.value === 'number' &&
      value >= filter.value
    );
  if (filter.op === Op.BETWEEN)
    return (
      typeof value === 'number' &&
      typeof filter.lowerBound === 'number' &&
      typeof filter.upperBound === 'number' &&
      value >= filter.lowerBound &&
      value <= filter.upperBound
    );
  if (filter.op === Op.IN)
    return (Array.isArray(value) ? value : [value]).some(item =>
      filter.values.some(candidate => Object.is(candidate, item)),
    );
  throw new Error('验收数据源不支持此操作符');
}

export function createReadinessService() {
  const metrics = {
    queries: 0,
    active: 0,
    aborted: 0,
    subscriptions: 0,
    cellCommits: 0,
  };
  const control = { fail: false, empty: false, slow: false };
  const source: RecordQuerySource = {
    async paged<T extends Partial<RecordData> = RecordData>(
      query: PagedQueryRequest,
      _attributes?: Record<string, unknown>,
      controller?: AbortController,
    ): Promise<PagedList<T>> {
      if (!('filter' in query))
        throw new Error('验收数据源要求已编译的 filter');
      metrics.queries++;
      metrics.active++;
      try {
        await new Promise<void>((resolve, reject) => {
          const signal = controller?.signal;
          signal?.throwIfAborted();
          function abort() {
            clearTimeout(timer);
            metrics.aborted++;
            reject(signal?.reason);
          }
          const timer = setTimeout(
            () => {
              signal?.removeEventListener('abort', abort);
              resolve();
            },
            control.slow ? 2000 : 1,
          );
          signal?.addEventListener('abort', abort, { once: true });
        });
        if (control.fail) {
          control.fail = false;
          throw new Error('验收查询失败，请重试');
        }
        const selected = control.empty
          ? []
          : rows.filter(row => matches(row, query.filter));
        for (const sort of [...(query.sort ?? [])].reverse()) {
          selected.sort((a, b) => {
            const left = readRecordValue(a, sort.field),
              right = readRecordValue(b, sort.field);
            const compared =
              typeof left === 'number' && typeof right === 'number'
                ? left - right
                : String(left).localeCompare(String(right));
            return sort.direction === 'DESC' ? -compared : compared;
          });
        }
        const { index = 1, size = 100 } = query.pagination ?? {};
        return {
          list: structuredClone(
            selected.slice((index - 1) * size, index * size),
          ) as T[],
          total: selected.length,
        };
      } finally {
        metrics.active--;
      }
    },
  };
  const host: ViewHost = {
    resolveSource: () => source,
    permission: {
      getInstance: () => ({
        save: false,
        saveAsPersonal: false,
        saveAsShared: false,
      }),
      subscribe() {
        metrics.subscriptions++;
        return () => {
          metrics.subscriptions--;
        };
      },
    },
  };
  return {
    definition,
    instances,
    host,
    metrics,
    control,
    recordCellCommit() {
      metrics.cellCommits++;
    },
  };
}
export type ReadinessService = ReturnType<typeof createReadinessService>;
