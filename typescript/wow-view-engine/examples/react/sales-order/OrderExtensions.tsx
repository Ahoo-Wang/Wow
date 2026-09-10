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

import { createContext, useContext } from 'react';
import { compileBuiltinFilter } from '@ahoo-wang/fetcher-view-engine';
import {
  Button,
  FilterSelect,
  TagsCell,
  InputGroupInput,
  type ViewExtensions,
  type FilterEditorProps,
} from '@ahoo-wang/fetcher-view-engine/react';
import { customers } from './fixtures.js';
import { customerOptions } from './host.js';
import { lifecycleLabels, type Role } from './model.js';
import type { Action } from './service.js';
export const OrderContext = createContext<{
  role: Role;
  busy: boolean;
  open(
    action: Action | 'detail',
    orderId: string | undefined,
    refresh: () => Promise<void>,
  ): void;
  batch(
    action: 'batchApprove' | 'batchRelease',
    ids: string[],
    refresh: () => Promise<void>,
  ): void;
} | null>(null);
function useOrders() {
  const context = useContext(OrderContext);
  if (!context) throw new Error('订单操作需要工作台宿主');
  return context;
}
function RiskFilter({ props, onChange, disabled }: FilterEditorProps) {
  return (
    <FilterSelect
      label="交期风险"
      value={typeof props.risk === 'string' ? props.risk : null}
      options={[
        { value: 'late', label: '已超期未发完' },
        { value: 'soon', label: '三日内待交付' },
      ]}
      disabled={disabled}
      onValueChange={risk => onChange({ ...props, risk })}
      onClear={() => onChange({ ...props, risk: undefined })}
    />
  );
}
function StatusFilter({ props, onChange, disabled }: FilterEditorProps) {
  return (
    <>
      <FilterSelect
        label="订单状态"
        placeholder="不限"
        value={typeof props.selectedId === 'string' ? props.selectedId : null}
        disabled={disabled}
        options={Object.entries(lifecycleLabels).map(([value, label]) => ({
          value,
          label,
        }))}
        onValueChange={selectedId => onChange({ ...props, selectedId })}
        onClear={() => onChange({ ...props, selectedId: undefined })}
      />
      <InputGroupInput
        aria-label="状态显示名称"
        value={typeof props.displayLabel === 'string' ? props.displayLabel : ''}
        onChange={event =>
          onChange({ ...props, displayLabel: event.target.value })
        }
      />
    </>
  );
}
export const orderExtensions: ViewExtensions = {
  optionSources: { customers: customerOptions },
  filters: {
    'order-status': {
      component: StatusFilter,
      modes: ['simple', 'advanced'],
      compile: (props, context) =>
        compileBuiltinFilter({ value: props.selectedId }, context),
      clear: props => ({ ...props, selectedId: undefined }),
    },
    'delivery-risk': {
      component: RiskFilter,
      modes: ['simple', 'advanced'],
      clear: props => ({ ...props, risk: undefined }),
      compile: (props, context) =>
        compileBuiltinFilter({ value: props.risk }, context),
    },
  },
  cells: {
    customer: ({ value }) => (
      <span>
        {customers[Number(String(value).replace('customer-', ''))] ??
          String(value)}
      </span>
    ),
    'order-items': ({ value }) => (
      <TagsCell
        value={
          Array.isArray(value)
            ? value.map(i => `${i.productName} × ${i.quantity}`)
            : []
        }
      />
    ),
    'delivery-progress': ({ record, value }) => {
      const state = record.state as {
        items: { signed: number; quantity: number }[];
      };
      const signed = state.items.reduce((s, i) => s + i.signed, 0),
        total = state.items.reduce((s, i) => s + i.quantity, 0);
      return (
        <span>
          {String(value)} 待发 · {signed} / {total} 已签收
        </span>
      );
    },
  },
  globalActions: {
    'order-create': function Global(props) {
      const { role, busy, open } = useOrders();
      if (role !== 'sales') return null;
      return (
        <Button
          disabled={busy || role !== 'sales'}
          onClick={() => open('create', undefined, props.refresh)}
        >
          创建订单
        </Button>
      );
    },
  },
  toolbarActions: {
    'order-batch': function Batch({ selectedRowKeys, refresh }) {
      const { role, busy, batch } = useOrders();
      if (role !== 'manager') return null;
      return (
        <>
          {(['batchApprove', 'batchRelease'] as const).map(type => (
            <Button
              key={type}
              variant="outline"
              disabled={role !== 'manager' || busy || !selectedRowKeys.length}
              onClick={() => batch(type, selectedRowKeys.map(String), refresh)}
            >
              {type === 'batchApprove' ? '批量审核' : '批量放行'}
            </Button>
          ))}
        </>
      );
    },
  },
  rowActions: {
    'order-detail': function Detail({ rowKey, refresh }) {
      const { open } = useOrders();
      return (
        <Button
          size="sm"
          variant="ghost"
          aria-label={`查看订单 ${rowKey}`}
          onClick={() => open('detail', String(rowKey), refresh)}
        >
          查看与处理
        </Button>
      );
    },
  },
};
