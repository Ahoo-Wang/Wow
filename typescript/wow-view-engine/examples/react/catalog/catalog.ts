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
  FilterOperator as Op,
  type FilterExpression,
  type PagedQueryRequest,
  type PagedList,
} from '@ahoo-wang/fetcher-wow';
import {
  createFilterConfiguration,
  newFilterNode,
  resolveRecordPresentation,
  type RecordData,
  type RecordQuerySource,
  type ViewDefinition,
  type ViewInstanceList,
} from '@ahoo-wang/fetcher-view-engine';

export const products = [
  ['bag', '苔绿通勤托特包', '出行', 169, 42],
  ['lamp', '奶油蘑菇台灯', '家居', 299, 18],
  ['chair', '雾蓝休闲椅', '家居', 899, 6],
  ['bottle', '森林保温杯', '出行', 129, 86],
  ['bag', '赤陶周末旅行袋', '出行', 239, 12],
  ['lamp', '雾蓝阅读灯', '家居', 359, 0],
  ['chair', '苔绿餐椅', '家居', 699, 24],
  ['bottle', '赤陶随行杯', '出行', 99, 35],
  ['bag', '雾蓝轻量购物袋', '出行', 89, 120],
  ['lamp', '苔绿床头灯', '家居', 259, 9],
  ['chair', '赤陶书房椅', '家居', 799, 0],
  ['bottle', '雾蓝运动水壶', '出行', 159, 63],
].map(([kind, name, category, price, stock], i) => ({
  id: `SKU-${String(i + 1).padStart(3, '0')}`,
  name: String(name),
  category: String(category),
  price: Number(price),
  stock: Number(stock),
  status: i % 4 === 1 ? 'draft' : 'published',
  favorite: false,
  cover: new URL(`./${kind}-${i % 3}.svg?no-inline`, import.meta.url).href,
}));
export const definition: ViewDefinition = {
  id: 'product-catalog',
  sourceId: 'products',
  title: '生活选品库',
  rowKey: 'id',
  allowedLayouts: ['table', 'card'],
  allowedOperators: [Op.MATCH_ALL, Op.AND, Op.EQ],
  fields: [
    { field: 'id', label: '商品编号', type: 'string', operators: [] },
    {
      field: 'name',
      label: '商品名称',
      type: 'string',
      operators: [],
      sortable: true,
    },
    { field: 'cover', label: '商品封面', type: 'string', operators: [] },
    {
      field: 'category',
      label: '分类',
      type: 'string',
      operators: [Op.EQ],
      options: [
        { value: '家居', label: '家居' },
        { value: '出行', label: '出行' },
      ],
    },
    {
      field: 'price',
      label: '售价',
      type: 'number',
      operators: [],
      sortable: true,
      numberFormat: { style: 'currency', currency: 'CNY' },
      cellRenderer: { name: 'number' },
    },
    {
      field: 'stock',
      label: '库存',
      type: 'number',
      operators: [],
      sortable: true,
    },
    {
      field: 'status',
      label: '销售状态',
      type: 'string',
      operators: [Op.EQ],
      options: [
        { value: 'published', label: '已上架' },
        { value: 'draft', label: '待上架' },
      ],
      cellRenderer: {
        name: 'status',
        options: {
          tones: [
            { value: 'published', tone: 'success' },
            { value: 'draft', tone: 'warning' },
          ],
        },
      },
    },
    { field: 'favorite', label: '已收藏', type: 'boolean', operators: [Op.EQ] },
  ],
  recordActions: {
    row: { name: 'product-actions' },
    toolbar: { name: 'product-batch' },
  },
  defaultPresentation: {
    card: {
      title: { id: 'name', field: 'name' },
      cover: { field: 'cover' },
      fields: [
        { id: 'price', field: 'price' },
        { id: 'stock', field: 'stock' },
        { id: 'status', field: 'status' },
      ],
      actions: {},
    },
    table: {
      columns: [
        ...['id', 'name', 'category', 'price', 'stock', 'status'].map(
          field => ({ id: field, field, kind: 'field' as const }),
        ),
        { id: 'actions', kind: 'actions' },
      ],
    },
  },
};
export const views: ViewInstanceList = {
  defaultInstanceId: 'all-products',
  instances: [
    {
      id: 'all-products',
      definitionId: definition.id,
      kind: 'record',
      title: '全部商品',
      scope: { type: 'personal' },
      config: {
        filters: createFilterConfiguration(newFilterNode(Op.MATCH_ALL)),
        sort: [],
        pagination: { mode: 'paged', size: 20 },
        presentation: resolveRecordPresentation(definition, 'card'),
      },
    },
  ],
};
// ponytail: this local story only implements its declared EQ/AND filters and paged sorting; use a business QueryApi for more operators.
function matches(row: RecordData, filter: FilterExpression): boolean {
  if (filter.op === Op.MATCH_ALL) return true;
  if (filter.op === Op.AND)
    return filter.operands.every(item => matches(row, item));
  if (
    filter.op === Op.EQ &&
    ['category', 'status', 'favorite'].includes(filter.field)
  )
    return row[filter.field] === filter.value;
  throw new Error('商品示例不支持该筛选条件');
}
export function createCatalog() {
  let records: RecordData[] = structuredClone(products);
  const source: RecordQuerySource = {
    async paged<T extends Partial<RecordData> = RecordData>(
      query: PagedQueryRequest,
      _attributes?: Record<string, unknown>,
      controller?: AbortController,
    ): Promise<PagedList<T>> {
      controller?.signal.throwIfAborted();
      if (!('filter' in query) || Object.keys(query.projection ?? {}).length)
        throw new Error('商品示例只支持分页筛选与排序');
      const rows = records.filter(row => matches(row, query.filter));
      for (const sort of [...(query.sort ?? [])].reverse()) {
        if (!['name', 'price', 'stock'].includes(sort.field))
          throw new Error('不支持的排序字段');
        rows.sort(
          (a, b) =>
            (sort.direction === 'DESC' ? -1 : 1) *
            (typeof a[sort.field] === 'number'
              ? Number(a[sort.field]) - Number(b[sort.field])
              : String(a[sort.field]).localeCompare(String(b[sort.field]))),
        );
      }
      const { index = 1, size = 20 } = query.pagination ?? {};
      return {
        total: rows.length,
        list: structuredClone(rows.slice((index - 1) * size, index * size)),
      } as PagedList<T>;
    },
  };
  return {
    source,
    update(
      ids: readonly (string | number)[],
      patch: { status?: string; favorite?: boolean },
    ) {
      if (!ids.length || ids.some(id => !records.some(row => row.id === id)))
        throw new Error('请选择有效商品');
      records = records.map(row =>
        ids.includes(String(row.id)) ? { ...row, ...patch } : row,
      );
    },
  };
}
