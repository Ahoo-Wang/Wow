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

import { PlusIcon } from 'lucide-react';
import { compileBuiltinFilter } from '@ahoo-wang/fetcher-view-engine';
import {
  Button,
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverTitle,
  FilterSelect,
  InputGroupInput,
  NumberCell,
  type CellRendererProps,
  type FilterEditorProps,
  type GlobalActionsRendererProps,
  type RowActionsRendererProps,
  type ToolbarActionsRendererProps,
  type ViewExtensions,
} from '@ahoo-wang/fetcher-view-engine/react';
import { useOrderOperations } from './OrderOperations.js';

function CreateOrder({ refresh, querying }: GlobalActionsRendererProps) {
  const { service, busy, run } = useOrderOperations();
  return (
    <Button
      aria-label="创建订单"
      title="创建订单"
      size="icon-sm"
      disabled={busy || querying}
      onClick={() =>
        run(
          async () => `已创建订单 ${(await service.createOrder()).id}`,
          refresh,
        )
      }
    >
      <PlusIcon aria-hidden="true" />
    </Button>
  );
}
export function OrderRowActions({
  record,
  rowKey,
  refresh,
}: Pick<RowActionsRendererProps, 'record' | 'rowKey' | 'refresh'>) {
  const { service, busy, run } = useOrderOperations();
  return (
    <>
      <Popover>
        <PopoverTrigger
          render={
            <Button
              variant="outline"
              size="sm"
              aria-label={`查看订单 ${rowKey}`}
            />
          }
        >
          查看详情
        </PopoverTrigger>
        <PopoverContent aria-label={`订单详情 ${rowKey}`}>
          <PopoverTitle>订单详情 {String(rowKey)}</PopoverTitle>
          <dl className="fve:grid fve:gap-2">
            <div>
              <dt>客户</dt>
              <dd>{String(record.customer)}</dd>
            </div>
            <div>
              <dt>订单编号</dt>
              <dd>{String(rowKey)}</dd>
            </div>
            <div>
              <dt>金额</dt>
              <dd>
                <NumberCell
                  value={
                    typeof record.amount === 'number' ? record.amount : null
                  }
                  format={{ style: 'currency', currency: 'CNY' }}
                />
              </dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{record.status === 'processed' ? '已处理' : '待处理'}</dd>
            </div>
          </dl>
        </PopoverContent>
      </Popover>
      <Button
        variant="ghost"
        size="sm"
        aria-label={`处理订单 ${rowKey}`}
        disabled={busy || record.status === 'processed'}
        onClick={() => {
          // Renderer inputs are readonly snapshots. Edit a copy for the business write.
          const draft = { ...record };
          draft.status = 'processed';
          run(async () => {
            await service.saveOrder(draft);
            return `已处理订单 ${rowKey}`;
          }, refresh);
        }}
      >
        处理
      </Button>
    </>
  );
}
function ProcessOrders({
  selectedRowKeys,
  querying,
  refresh,
}: ToolbarActionsRendererProps) {
  const { service, busy, run } = useOrderOperations();
  return (
    <Button
      variant="outline"
      disabled={busy || querying || !selectedRowKeys.length}
      onClick={() => {
        const keys = [...selectedRowKeys];
        run(async () => {
          await service.processOrders(keys);
          return `已处理 ${keys.length} 笔订单`;
        }, refresh);
      }}
    >
      批量处理
    </Button>
  );
}
function OrderStatus({
  props,
  disabled,
  onChange,
  onValidityChange,
}: FilterEditorProps) {
  const value = typeof props.selectedId === 'string' ? props.selectedId : null;
  return (
    <>
      <FilterSelect
        label="订单状态"
        placeholder="不限"
        value={value}
        disabled={disabled}
        options={[
          { value: 'pending', label: '待处理' },
          { value: 'processed', label: '已处理' },
        ]}
        onClear={() => onChange({ ...props, selectedId: undefined })}
        onValueChange={selectedId => {
          onValidityChange(true);
          onChange({ ...props, selectedId });
        }}
        inline
      />
      <InputGroupInput
        aria-label="状态显示名称"
        placeholder="显示名称（不影响查询）"
        value={typeof props.displayLabel === 'string' ? props.displayLabel : ''}
        disabled={disabled}
        onChange={event =>
          onChange({ ...props, displayLabel: event.target.value })
        }
      />
    </>
  );
}
function Money({ value, field }: CellRendererProps) {
  return typeof value === 'number' ? (
    <span aria-label={`金额 ${value.toFixed(2)} 元`}>
      <NumberCell value={value} format={field.numberFormat} />
    </span>
  ) : (
    <span>—</span>
  );
}
export const orderExtensions: ViewExtensions = {
  globalActions: { 'create-order': CreateOrder },
  rowActions: { 'process-order': OrderRowActions },
  toolbarActions: { 'process-orders': ProcessOrders },
  filters: {
    'order-status': {
      component: OrderStatus,
      modes: ['simple', 'advanced'],
      compile: (props, context) =>
        compileBuiltinFilter({ value: props.selectedId }, context),
      clear: props => ({ ...props, selectedId: undefined }),
    },
  },
  cells: { money: Money },
};
