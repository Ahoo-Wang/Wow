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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type FieldOption,
  type FilterNode,
  type RecordViewConfig,
} from '@ahoo-wang/wow-view-engine';

/**
 * The CRM service the customer scenes start on: a local port-forward of the
 * dev cluster's `crm-service`, since a browser outside the cluster cannot
 * resolve its in-cluster name. Inside the cluster the service answers at
 * `http://crm-service.dev.svc.cluster.local`. Each story takes the address as
 * its `host` arg, so the Controls panel can point it anywhere without a
 * restart; set `STORYBOOK_WOW_CRM_HOST` to change where it starts.
 */
export const DEFAULT_CRM_HOST: string =
  import.meta.env.STORYBOOK_WOW_CRM_HOST ?? 'http://localhost:8085';

export const CUSTOMER = 'customer';

/** The CRM service's `Customer` aggregate, as its query resources name it. */
export const CUSTOMER_AGGREGATE = 'customer';

// Wow's names for what the analysis side may group and compute by.
const { TERMS, HISTOGRAM, DATE_HISTOGRAM } = AggregationGroupType;
const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { HOUR, DAY, WEEK, MONTH } = AggregationDateUnit;

/** What a customer's decision-maker contact's account is, in the CRM's words. */
const ACCOUNT_STATUS: FieldOption[] = [
  { value: 'NONE', label: '未开通' },
  { value: 'OPENING', label: '开通中', tone: 'warning' },
  { value: 'OPENED', label: '已开通', tone: 'success' },
  { value: 'FAILED', label: '开通失败', tone: 'danger' },
];

/**
 * The columns, ending in the one time the view is ordered by. The row key
 * leads, as the table pins it first whatever the order says. What fits a
 * 1440-wide screen without scrolling sideways: one time and not both, and
 * the level on the card and in the picker rather than here — on the dev
 * service every customer is B, a column that says nothing.
 */
function columns(time: 'firstEventTime' | 'eventTime') {
  return [
    'state.id',
    'state.name',
    'state.status',
    'state.ownerId',
    'state.basicInfo.industry',
    'state.contacts',
    'tenantId',
    time,
  ].map(field => ({ field }));
}

function recordView(
  filter: FilterNode[],
  overrides: Partial<RecordViewConfig> = {},
): RecordViewConfig {
  return {
    kind: 'record',
    filter: { op: 'and', children: filter },
    filterMode: 'simple',
    refresh: { interval: null },
    sort: [{ field: 'firstEventTime', direction: 'DESC' }],
    pageSize: 20,
    layout: 'table',
    summaries: [],
    table: { columns: columns('firstEventTime') },
    // A card is scanned for who the customer is and who looks after it: the
    // name titles it, its standing and its owner lead the body.
    card: {
      title: 'state.name',
      fields: [
        'state.status',
        'state.ownerId',
        'state.salesInfo.level',
        'state.basicInfo.industry',
        'state.contacts',
        'eventTime',
      ],
    },
    ...overrides,
  };
}

/**
 * A count of customers by `groups`. Its chart plots the first group whichever
 * layout the view opens in, so switching to the chart needs no repair first.
 */
function analysisView(
  overrides: Partial<AnalysisViewConfig> & Pick<AnalysisViewConfig, 'groups'>,
): AnalysisViewConfig {
  return {
    kind: 'analysis',
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    metrics: [{ alias: 'count', type: 'COUNT', label: '客户数' }],
    sort: [{ alias: 'count', direction: 'DESC' }],
    limit: 20,
    layout: 'table',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: {
        x: overrides.groups[0].alias,
        series: [{ metric: 'count' }],
      },
    },
    ...overrides,
  };
}

/** A customer nobody owns sits in the public pool, 公海 in the CRM's words. */
const IN_POOL: FilterNode = {
  field: 'state.ownerId',
  operator: 'IS_NULL',
  value: null,
};

/**
 * `customer` as the CRM service's own query schema describes it
 * (`GET /customer/snapshot/schema`), converted by hand: every field here is
 * one the schema lists, with the operators, sorting and aggregation its
 * capabilities admit, and no more. The schema titles most fields in Chinese,
 * and those titles are the labels here where they read as an operator would
 * say them; the grouping and the choice of what a CRM operator needs are
 * this definition's.
 *
 * What the schema says, and what follows from it:
 * - no field declares a full-text capability, so there is no search box; a
 *   customer is found by its name, code or id with `CONTAINS`/`EQ`, which
 *   every text field admits (`LITERAL_MATCH`, `EXACT_MATCH`);
 * - only the snapshot's own times (`firstEventTime`, `eventTime`) are
 *   `TEMPORAL_EPOCH`, so only they bucket by date. The timeline's times are
 *   epoch milliseconds too (their description says so) but carry no
 *   semantic type: they compare, sort and take an earliest and a latest, and
 *   a relative window on them is resolved here into a plain range;
 * - the arrays of objects (`contacts`, `deliveryAddresses`) are
 *   `ELEMENT_SCOPE`: a condition on a contact is an element match, and an
 *   analysis of contacts expands the array and counts contacts.
 *
 * Left out on purpose:
 * - the snapshot's bookkeeping — `snapshotTime`, `deleted`, `eventId`,
 *   `contextName`, `aggregateName` (one value on every customer), the root
 *   `ownerId` and `spaceId` (empty on every customer), and `operator` and
 *   `firstOperator`, which the service writes as `(0)` for nearly every
 *   change. `version` stays: it is how many changes a customer has had;
 * - `state.extend`, an open map whose one declared member is a derived
 *   `empty`, and the root `tags`, an open map of arrays with no member named;
 * - `state.invoices`: bank accounts and taxpayer numbers are not what an
 *   operator filters a customer list by, and the detail panel shows them;
 * - `state.businessLicenseUrl`, the verification's `passedAt` and
 *   `reviewer`, and the financial terms (`commissionRate`, `prepayPercent`,
 *   `latestPayPercent`, `additionalContract`, `allowDirectDelivery`,
 *   `paymentMethod`): a finance view's fields, not a customer console's;
 * - a contact's `gender`, an integer code the schema names no values for,
 *   and the ids a contact carries for the IAM account it opens.
 */
export const customerDefinition: DataViewDefinition = {
  id: CUSTOMER,
  title: '快照控制台',
  recordNoun: '客户',
  kind: 'data',
  source: CUSTOMER_AGGREGATE,
  // The pickers list the fields under these, in this order.
  fieldGroups: [
    {
      id: 'identity',
      label: '标识',
      fields: [
        'state.id',
        'state.name',
        'state.basicInfo.alias',
        'state.basicInfo.bizId',
        'state.enterpriseId.name',
        'state.enterpriseId.id',
        'tenantId',
      ],
    },
    {
      id: 'status',
      label: '状态',
      fields: [
        'state.status',
        'state.enterpriseVerification.status',
        'state.financialOnboardingRequested',
      ],
    },
    {
      id: 'sales',
      label: '销售',
      fields: [
        'state.ownerId',
        'state.salesInfo.level',
        'state.salesInfo.status',
        'state.salesInfo.lifecycleStage',
        'state.salesInfo.customerType',
        'state.salesInfo.source',
        'state.salesInfo.saleChannel',
        'state.salesInfo.creditRating',
        'state.salesInfo.departmentId',
        'state.tags',
      ],
    },
    {
      id: 'basic',
      label: '基本信息',
      fields: [
        'state.basicInfo.industry',
        'state.basicInfo.staffNum',
        'state.basicInfo.annualRevenue',
        'state.basicInfo.description',
      ],
    },
    {
      id: 'contact',
      label: '联系人与地址',
      fields: ['state.contacts', 'state.deliveryAddresses'],
    },
    {
      id: 'time',
      label: '时间',
      fields: [
        'firstEventTime',
        'eventTime',
        'version',
        'state.timeline.lastActivityTime',
        'state.timeline.lastContactTime',
        'state.timeline.nextFollowUpTime',
        'state.timeline.contactFrequency',
        'state.timeline.validityStartTime',
        'state.timeline.validityEndTime',
      ],
    },
  ],
  fields: [
    {
      name: 'state.id',
      label: '客户 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    { name: 'state.name', label: '客户名称', kind: 'string', sortable: true },
    {
      name: 'state.basicInfo.alias',
      label: '客户简称',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.basicInfo.bizId',
      label: '客户编码',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    {
      name: 'state.enterpriseId.name',
      label: '所属企业',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.enterpriseId.id',
      label: '企业 ID',
      kind: 'string',
      sortable: true,
      cell: 'copyable',
    },
    // The service is multi-tenant and its customers span tenants, so the
    // tenant is a column and a dimension here, not a scope the host fixes.
    { name: 'tenantId', label: '租户', kind: 'string', sortable: true },
    {
      // ENABLED allows every operation, DISABLED forbids every write.
      name: 'state.status',
      label: '客户状态',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: [
        { value: 'ENABLED', label: '正常', tone: 'success' },
        { value: 'DISABLED', label: '已禁用', tone: 'danger' },
      ],
    },
    {
      name: 'state.enterpriseVerification.status',
      label: '企业认证',
      kind: 'enum',
      sortable: true,
      cell: 'status',
      options: [
        { value: 'NOT_SUBMITTED', label: '未提交' },
        {
          value: 'MANUAL_REVIEW_REQUIRED',
          label: '待人工审核',
          tone: 'warning',
        },
        { value: 'VERIFIED', label: '已认证', tone: 'success' },
        { value: 'REJECTED', label: '已驳回', tone: 'danger' },
      ],
    },
    {
      // Asked of Finance, which is not the same as Finance having opened it.
      name: 'state.financialOnboardingRequested',
      label: '已申请财务准入',
      kind: 'boolean',
      sortable: true,
    },
    // Null while the customer is in the public pool; claiming it sets one,
    // releasing it clears it.
    {
      name: 'state.ownerId',
      label: '负责人',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.level',
      label: '客户等级',
      kind: 'enum',
      sortable: true,
      options: [
        { value: 'A', label: 'A 级' },
        { value: 'B', label: 'B 级' },
        { value: 'C', label: 'C 级' },
        { value: 'D', label: 'D 级' },
      ],
    },
    // The sales classifications are free text in the schema — no values
    // declared — so they stay text, picked from what the service holds.
    {
      name: 'state.salesInfo.status',
      label: '合作状态',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.lifecycleStage',
      label: '生命周期阶段',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.customerType',
      label: '客户类型',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.source',
      label: '客户来源',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.saleChannel',
      label: '销售渠道',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.creditRating',
      label: '信用评级',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.salesInfo.departmentId',
      label: '归属部门',
      kind: 'string',
      sortable: true,
    },
    // An array of strings: matched by entry, never sorted.
    { name: 'state.tags', label: '客户标签', kind: 'array', cell: 'tags' },
    {
      name: 'state.basicInfo.industry',
      label: '所属行业',
      kind: 'string',
      sortable: true,
    },
    {
      name: 'state.basicInfo.staffNum',
      label: '员工人数',
      kind: 'number',
      sortable: true,
      summary: ['SUM', 'AVG', 'MAX'],
    },
    {
      // A DECIMAL the schema gives no unit; shown whole, grouped.
      name: 'state.basicInfo.annualRevenue',
      label: '年营业额',
      kind: 'number',
      sortable: true,
      numberFormat: { maximumFractionDigits: 0 },
      summary: ['SUM', 'AVG', 'MAX'],
    },
    {
      name: 'state.basicInfo.description',
      label: '备注',
      kind: 'string',
      cell: 'text',
    },
    {
      // Read by who they are; the page asks for their names alone.
      name: 'state.contacts',
      label: '联系人',
      kind: 'elementMatch',
      elementTitle: 'name',
      elements: [
        { name: 'name', label: '姓名', kind: 'string' },
        { name: 'title', label: '职位', kind: 'string' },
        { name: 'role', label: '决策角色', kind: 'string' },
        { name: 'isPrimary', label: '首要联系人', kind: 'boolean' },
        { name: 'mobile', label: '手机', kind: 'string' },
        { name: 'phone', label: '电话', kind: 'string' },
        { name: 'email', label: '邮箱', kind: 'string' },
        { name: 'wechat', label: '微信号', kind: 'string' },
        {
          name: 'accountStatus',
          label: '账号开通',
          kind: 'enum',
          options: ACCOUNT_STATUS,
        },
        { name: 'failureCode', label: '开通失败码', kind: 'string' },
      ],
    },
    {
      // Read by the city each goes to.
      name: 'state.deliveryAddresses',
      label: '收货地址',
      kind: 'elementMatch',
      elementTitle: 'city',
      elements: [
        { name: 'province', label: '省份', kind: 'string' },
        { name: 'city', label: '城市', kind: 'string' },
        { name: 'district', label: '区县', kind: 'string' },
        { name: 'detail', label: '详细地址', kind: 'string' },
        { name: 'contact', label: '收货人', kind: 'string' },
        { name: 'phone', label: '联系电话', kind: 'string' },
        { name: 'isDefault', label: '默认地址', kind: 'boolean' },
      ],
    },
    {
      name: 'firstEventTime',
      label: '创建时间',
      kind: 'datetime',
      sortable: true,
    },
    { name: 'eventTime', label: '最近变更', kind: 'datetime', sortable: true },
    {
      // Every change the customer has had, its creation the first.
      name: 'version',
      label: '版本',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.timeline.lastActivityTime',
      label: '最近活动',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'state.timeline.lastContactTime',
      label: '最后联系',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'state.timeline.nextFollowUpTime',
      label: '下次跟进',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'state.timeline.contactFrequency',
      label: '联系频次',
      kind: 'number',
      sortable: true,
    },
    {
      name: 'state.timeline.validityStartTime',
      label: '合作有效期起',
      kind: 'datetime',
      sortable: true,
    },
    {
      name: 'state.timeline.validityEndTime',
      label: '合作有效期止',
      kind: 'datetime',
      sortable: true,
    },
  ],
  record: {
    rowKey: 'state.id',
    paging: 'paged',
    layouts: ['table', 'card'],
    // The service refuses a page reaching past its 10,000th row.
    maxWindow: 10_000,
  },
  // What the schema lets the service aggregate: AGGREGATE_TERMS groups by
  // value, AGGREGATE_NUMERIC bands and computes, AGGREGATE_TEMPORAL buckets
  // by date. The ids that are one per customer — its id, code, name — have
  // terms too, and are left out: a group of one answers nothing.
  analysis: {
    count: true,
    having: true,
    expressions: true,
    // The service refuses an aggregation asking for more than 1,000 groups.
    limits: { maxLimit: 1000 },
    fields: [
      ...[
        'tenantId',
        'state.status',
        'state.enterpriseVerification.status',
        'state.financialOnboardingRequested',
        'state.ownerId',
        'state.salesInfo.level',
        'state.salesInfo.status',
        'state.salesInfo.lifecycleStage',
        'state.salesInfo.customerType',
        'state.salesInfo.source',
        'state.salesInfo.saleChannel',
        'state.salesInfo.creditRating',
        'state.salesInfo.departmentId',
        'state.basicInfo.industry',
        'state.enterpriseId.name',
      ].map(field => ({ field, groups: [TERMS], functions: [] })),
      {
        field: 'state.id',
        groups: [],
        functions: [],
        distinctCount: true,
      },
      ...['state.basicInfo.staffNum', 'state.basicInfo.annualRevenue'].map(
        field => ({
          field,
          groups: [HISTOGRAM],
          functions: [SUM, AVG, MIN, MAX],
          percentile: true,
        }),
      ),
      {
        field: 'version',
        groups: [TERMS, HISTOGRAM],
        functions: [AVG, MIN, MAX],
      },
      ...['firstEventTime', 'eventTime'].map(field => ({
        field,
        groups: [DATE_HISTOGRAM],
        functions: [MIN, MAX],
        dateUnits: [HOUR, DAY, WEEK, MONTH],
      })),
      // Numbers to the service, instants to a reader: an earliest and a
      // latest, never a bucket.
      ...[
        'state.timeline.lastActivityTime',
        'state.timeline.nextFollowUpTime',
      ].map(field => ({ field, groups: [], functions: [MIN, MAX] })),
    ],
    // One chain, as Wow expands it: a second root array would be a level
    // inside the contacts, which the delivery addresses are not. The
    // contacts are the array an operator counts by; the addresses stay a
    // condition.
    elements: [
      {
        path: 'state.contacts',
        aggregations: ['role', 'title', 'accountStatus', 'isPrimary'].map(
          field => ({ field, groups: [TERMS], functions: [] }),
        ),
      },
    ],
  },
  views: [
    { id: 'all', title: '全部客户', config: recordView([]) },
    {
      // What changed since it was created, the latest change first.
      id: 'recent',
      title: '最近变更',
      config: recordView([{ field: 'version', operator: 'GT', value: 1 }], {
        sort: [{ field: 'eventTime', direction: 'DESC' }],
        table: { columns: columns('eventTime') },
      }),
    },
    { id: 'pool', title: '公海客户', config: recordView([IN_POOL]) },
    {
      id: 'disabled',
      title: '已禁用',
      config: recordView([
        { field: 'state.status', operator: 'IN', value: ['DISABLED'] },
      ]),
    },
    {
      id: 'by-owner',
      title: '按负责人分布',
      config: analysisView({
        groups: [
          {
            type: 'TERMS',
            field: 'state.ownerId',
            alias: 'owner',
            // The customers nobody owns are the pool, counted as one.
            missingKey: '（公海）',
            label: '负责人',
          },
        ],
        layout: 'chart',
      }),
    },
    {
      id: 'by-industry',
      title: '按行业分布',
      config: analysisView({
        filter: {
          op: 'and',
          children: [
            {
              field: 'state.basicInfo.industry',
              operator: 'IS_NOT_NULL',
              value: null,
            },
          ],
        },
        groups: [
          {
            type: 'TERMS',
            field: 'state.basicInfo.industry',
            alias: 'industry',
            label: '所属行业',
          },
        ],
        metrics: [
          { alias: 'count', type: 'COUNT', label: '客户数' },
          {
            alias: 'revenue',
            type: 'NUMERIC',
            function: 'SUM',
            expression: {
              type: 'FIELD',
              field: 'state.basicInfo.annualRevenue',
            },
            label: '年营业额合计',
          },
          {
            alias: 'staff',
            type: 'NUMERIC',
            function: 'AVG',
            expression: { type: 'FIELD', field: 'state.basicInfo.staffNum' },
            label: '平均员工人数',
          },
        ],
      }),
    },
    {
      id: 'by-tenant',
      title: '按租户分布',
      config: analysisView({
        groups: [
          { type: 'TERMS', field: 'tenantId', alias: 'tenant', label: '租户' },
        ],
        layout: 'chart',
      }),
    },
    {
      id: 'by-contact-role',
      title: '联系人 · 按决策角色',
      config: analysisView({
        elements: [{ path: 'state.contacts' }],
        groups: [
          {
            type: 'TERMS',
            field: 'state.contacts.role',
            alias: 'role',
            label: '决策角色',
          },
        ],
        metrics: [{ alias: 'count', type: 'COUNT', label: '联系人数' }],
        layout: 'chart',
      }),
    },
    {
      id: 'daily',
      title: '每日新增客户',
      config: analysisView({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'firstEventTime',
            alias: 'day',
            unit: 'DAY',
            label: '日期',
          },
        ],
        sort: [{ alias: 'day', direction: 'DESC' }],
        limit: 30,
      }),
    },
  ],
};

export function crmFetcher(host: string): Fetcher {
  return new Fetcher({ baseURL: host });
}

/**
 * A fresh engine over the CRM service `fetcher` points at. The snapshot
 * query client is the source as it is: `ViewSource` is three of its methods.
 * Saved views live in memory, so they last as long as the story does. The
 * scene is read-only: it sends the service queries and nothing else.
 */
export function createCustomerEngine(fetcher: Fetcher): ViewEngine {
  const source = new SnapshotQueryClient({
    basePath: CUSTOMER_AGGREGATE,
    fetcher,
  });
  return new ViewEngine({
    definitions: [customerDefinition],
    // The service pages at most 100 rows at a time.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store: new MemoryViewStore({ instances: [] }),
    resolveSource: () => source,
  });
}
