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

import { useState, type FormEvent } from 'react';
import { Button } from '@ahoo-wang/fetcher-view-engine/react';
import { customers, defaultDraft, products } from './fixtures.js';
import {
  quantityOf,
  type OrderDraft,
  type OrderSnapshot,
  type QuantityLine,
} from './model.js';
import { actionLabels, type Action, type Command } from './service.js';
export const currency = (amount: number) =>
  new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(
    amount,
  );
function dateValue(value: number) {
  if (!Number.isFinite(value)) return '';
  return new Date(value + 8 * 3600000).toISOString().slice(0, 10);
}
export function OrderForms({
  commandType,
  order,
  busy,
  onSubmit,
  onCancel,
}: {
  commandType: Action;
  order?: OrderSnapshot;
  busy: boolean;
  onSubmit(command: Command): Promise<void>;
  onCancel(): void;
}) {
  const state = order?.state;
  const [draft, setDraft] = useState<OrderDraft>(() =>
    state
      ? {
          ...defaultDraft(),
          customerId: state.customerId,
          customer: state.customer,
          ownerId: state.ownerId,
          region: state.region,
          terms: state.terms,
          dueAt: state.dueAt,
          paymentDueAt: state.paymentDueAt,
          items: state.items.map(i => ({
            id: i.id,
            sku: i.productId,
            name: i.productName,
            quantity: i.quantity,
            unitPriceCents: Math.round(i.price * 100),
          })),
        }
      : defaultDraft(),
  );
  const [error, setError] = useState('');
  const [shipmentId, setShipmentId] = useState(
    state?.shipments[state.shipments.length - 1]?.id ?? '',
  );
  const [returnId, setReturnId] = useState(
    state?.returns.find(r =>
      commandType === 'approveReturn'
        ? !r.approved
        : r.approved &&
          r.requested.some(l => quantityOf(r.received, l.itemId) < l.quantity),
    )?.id ?? '',
  );
  const isDraft = commandType === 'create' || commandType === 'edit';
  const moneyField = (
    {
      receive: 'amountDue',
      refund: 'refundDue',
      invoice: 'invoiceDue',
      credit: 'creditDue',
    } as const
  )[commandType as 'receive'];
  const quantityAction = [
    'prepare',
    'ship',
    'receipt',
    'restock',
    'requestReturn',
    'receiveReturn',
  ].includes(commandType);
  function maxQuantity(item: NonNullable<typeof state>['items'][number]) {
    if (commandType === 'prepare') return item.remainingToShip - item.prepared;
    if (commandType === 'ship') return item.prepared;
    if (commandType === 'requestReturn')
      return item.signed - item.returnRequested;
    if (commandType === 'receiveReturn') {
      const ret = state!.returns.find(r => r.id === returnId);
      return ret
        ? quantityOf(ret.requested, item.id) - quantityOf(ret.received, item.id)
        : 0;
    }
    const shipment = state!.shipments.find(s => s.id === shipmentId);
    if (!shipment) return 0;
    const receipts = state!.receipts.filter(r => r.shipmentId === shipmentId);
    return commandType === 'restock'
      ? quantityOf(
          receipts.flatMap(r => r.rejected),
          item.id,
        ) -
          quantityOf(
            state!.returnedToWarehouse
              .filter(r => r.shipmentId === shipmentId)
              .flatMap(r => r.lines),
            item.id,
          )
      : quantityOf(shipment.lines, item.id) -
          quantityOf(
            receipts.flatMap(r => [...r.accepted, ...r.rejected]),
            item.id,
          );
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    const data = new FormData(event.currentTarget);
    const orderId = order?.aggregateId ?? '';
    const lines: QuantityLine[] = (state?.items ?? [])
      .map(i => ({
        itemId: i.id,
        quantity: Number(data.get(`qty-${i.id}`) ?? 0),
      }))
      .filter(l => l.quantity !== 0);
    const rejected: QuantityLine[] = (state?.items ?? [])
      .map(i => ({
        itemId: i.id,
        quantity: Number(data.get(`reject-${i.id}`) ?? 0),
      }))
      .filter(l => l.quantity !== 0);
    let command: Command;
    if (commandType === 'create') command = { type: 'create', draft };
    else if (commandType === 'edit') command = { type: 'edit', orderId, draft };
    else if (
      commandType === 'receive' ||
      commandType === 'refund' ||
      commandType === 'invoice' ||
      commandType === 'credit'
    ) {
      const text = String(data.get('amount'));
      if (!/^\d+(\.\d{1,2})?$/.test(text)) {
        setError('金额最多两位小数');
        return;
      }
      command = {
        type: commandType,
        orderId,
        amountCents: Math.round(Number(text) * 100),
        reference: String(data.get('reference')),
      };
    } else if (commandType === 'reject' || commandType === 'cancel')
      command = {
        type: commandType,
        orderId,
        reason: String(data.get('reason')),
      };
    else if (commandType === 'prepare' || commandType === 'requestReturn')
      command = { type: commandType, orderId, lines };
    else if (commandType === 'ship')
      command = {
        type: 'ship',
        orderId,
        lines,
        tracking: String(data.get('tracking')),
      };
    else if (commandType === 'receipt')
      command = {
        type: 'receipt',
        orderId,
        shipmentId,
        accepted: lines,
        rejected,
      };
    else if (commandType === 'restock')
      command = { type: 'restock', orderId, shipmentId, lines };
    else if (commandType === 'approveReturn')
      command = { type: 'approveReturn', orderId, returnId };
    else if (commandType === 'receiveReturn')
      command = { type: 'receiveReturn', orderId, returnId, lines };
    else if (
      commandType === 'submit' ||
      commandType === 'approve' ||
      commandType === 'release' ||
      commandType === 'reconcile' ||
      commandType === 'close'
    )
      command = { type: commandType, orderId };
    else return;
    try {
      await onSubmit(command);
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    }
  }
  return (
    <form onSubmit={submit} className="sales-form">
      <fieldset disabled={busy} className="sales-form-body">
        {isDraft ? (
          <>
            <div className="sales-fields">
              <label>
                客户
                <select
                  aria-label="客户"
                  value={draft.customerId}
                  onChange={e => {
                    const i = Number(e.target.value.slice(9));
                    setDraft({
                      ...draft,
                      customerId: e.target.value,
                      customer: customers[i],
                      creditAllowed: true,
                      overdue: i === 8,
                    });
                  }}
                >
                  {customers.map((c, i) => (
                    <option key={c} value={`customer-${i}`}>
                      {c}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                负责人
                <select
                  value={draft.ownerId}
                  onChange={e =>
                    setDraft({ ...draft, ownerId: e.target.value })
                  }
                >
                  {['林晨', '顾嘉', '陈宁'].map(v => (
                    <option key={v}>{v}</option>
                  ))}
                </select>
              </label>
              <label>
                结算方式
                <select
                  value={draft.terms}
                  onChange={e =>
                    setDraft({
                      ...draft,
                      terms: e.target.value as OrderDraft['terms'],
                    })
                  }
                >
                  <option value="prepaid">全额预付</option>
                  <option value="credit">月结账期</option>
                </select>
              </label>
              <label>
                承诺交期
                <input
                  required
                  type="date"
                  value={dateValue(draft.dueAt)}
                  onChange={e =>
                    setDraft({
                      ...draft,
                      dueAt: Date.parse(`${e.target.value}T10:00:00+08:00`),
                    })
                  }
                />
              </label>
              <label>
                应收到期日
                <input
                  required
                  type="date"
                  value={dateValue(draft.paymentDueAt)}
                  onChange={e =>
                    setDraft({
                      ...draft,
                      paymentDueAt: Date.parse(
                        `${e.target.value}T10:00:00+08:00`,
                      ),
                    })
                  }
                />
              </label>
            </div>
            {draft.items.map((item, index) => (
              <div className="sales-item-form" key={item.id}>
                <label>
                  商品 {index + 1}
                  <select
                    value={item.sku}
                    onChange={e =>
                      setDraft({
                        ...draft,
                        items: draft.items.map((i, n) =>
                          n === index
                            ? {
                                ...i,
                                ...products.find(
                                  p => p.sku === e.target.value,
                                )!,
                              }
                            : i,
                        ),
                      })
                    }
                  >
                    {products.map(p => (
                      <option key={p.sku} value={p.sku}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  数量 {index + 1}
                  <input
                    required
                    type="number"
                    min="1"
                    step="1"
                    value={item.quantity}
                    onChange={e =>
                      setDraft({
                        ...draft,
                        items: draft.items.map((i, n) =>
                          n === index
                            ? { ...i, quantity: Number(e.target.value) }
                            : i,
                        ),
                      })
                    }
                  />
                </label>
                <label>
                  单价 {index + 1}
                  <input
                    required
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={item.unitPriceCents / 100}
                    onChange={e =>
                      setDraft({
                        ...draft,
                        items: draft.items.map((i, n) =>
                          n === index
                            ? {
                                ...i,
                                unitPriceCents: Math.round(
                                  Number(e.target.value) * 100,
                                ),
                              }
                            : i,
                        ),
                      })
                    }
                  />
                </label>
                <Button
                  type="button"
                  variant="ghost"
                  disabled={draft.items.length === 1}
                  onClick={() =>
                    setDraft({
                      ...draft,
                      items: draft.items.filter(i => i.id !== item.id),
                    })
                  }
                >
                  删除商品 {index + 1}
                </Button>
              </div>
            ))}
            <Button
              type="button"
              variant="outline"
              onClick={() =>
                setDraft({
                  ...draft,
                  items: [
                    ...draft.items,
                    { id: crypto.randomUUID(), ...products[0], quantity: 1 },
                  ],
                })
              }
            >
              添加商品
            </Button>
            <p aria-label="订单合计">
              订单合计{' '}
              <strong>
                {currency(
                  draft.items.reduce(
                    (s, i) => s + i.unitPriceCents * i.quantity,
                    0,
                  ) / 100,
                )}
              </strong>
            </p>
          </>
        ) : (
          <p>
            {order?.aggregateId} · {state?.customer}
          </p>
        )}
        {moneyField && state && (
          <div className="sales-fields">
            <label>
              本次金额（元）
              <input
                name="amount"
                aria-label="本次金额"
                required
                type="number"
                min="0.01"
                max={state[moneyField]}
                step="0.01"
                defaultValue={state[moneyField]}
              />
            </label>
            <label>
              凭证号
              <input
                name="reference"
                required
                placeholder="填写银行或票据凭证号"
              />
            </label>
          </div>
        )}
        {(commandType === 'reject' || commandType === 'cancel') && (
          <label>
            原因
            <textarea name="reason" required />
          </label>
        )}
        {(commandType === 'receipt' || commandType === 'restock') && (
          <label>
            发货记录
            <select
              value={shipmentId}
              onChange={e => setShipmentId(e.target.value)}
            >
              {state?.shipments.map(s => (
                <option key={s.id} value={s.id}>
                  {s.tracking}
                </option>
              ))}
            </select>
          </label>
        )}
        {(commandType === 'approveReturn' ||
          commandType === 'receiveReturn') && (
          <label>
            退货申请
            <select
              value={returnId}
              onChange={e => setReturnId(e.target.value)}
            >
              {state?.returns.map((r, i) => (
                <option key={r.id} value={r.id}>
                  退货申请 {i + 1}
                  {r.approved ? ' · 已审核' : ' · 待审核'}
                </option>
              ))}
            </select>
          </label>
        )}
        {quantityAction &&
          state?.items.map(item =>
            commandType === 'receipt' ? (
              <ReceiptQuantities
                key={`${item.id}:${shipmentId}`}
                itemId={item.id}
                name={item.productName}
                maximum={Math.max(0, maxQuantity(item))}
              />
            ) : (
              <div
                className="sales-fields"
                key={`${item.id}:${shipmentId}:${returnId}`}
              >
                <label>
                  {item.productName} · 本次数量（可处理 {maxQuantity(item)}）
                  <input
                    aria-label={`本次数量 ${item.productName}`}
                    name={`qty-${item.id}`}
                    type="number"
                    min="0"
                    max={maxQuantity(item)}
                    step="1"
                    defaultValue={Math.max(0, maxQuantity(item))}
                  />
                </label>
              </div>
            ),
          )}
        {commandType === 'ship' && (
          <label>
            运单号
            <input name="tracking" required />
          </label>
        )}
        <p className="sales-muted">
          {commandType === 'release'
            ? '预付订单须收齐；账期订单须授权有效且无逾期。'
            : commandType === 'close'
              ? '关闭后只能查看。请确认交付、售后、收付款和票据已核对完毕。'
              : ''}
        </p>
      </fieldset>
      {error && <p role="alert">{error}</p>}
      <div className="sales-form-actions">
        <Button
          type="button"
          variant="outline"
          disabled={busy}
          onClick={onCancel}
        >
          {order ? '返回详情' : '取消创建'}
        </Button>
        <Button type="submit" disabled={busy}>
          {busy
            ? '正在处理…'
            : commandType === 'create'
              ? '确认创建'
              : `确认${actionLabels[commandType]}`}
        </Button>
      </div>
    </form>
  );
}

function ReceiptQuantities({
  itemId,
  name,
  maximum,
}: {
  itemId: string;
  name: string;
  maximum: number;
}) {
  const [quantities, setQuantities] = useState({
    accepted: maximum,
    rejected: 0,
  });
  function change(field: 'accepted' | 'rejected', raw: string) {
    const value = Math.max(0, Math.min(maximum, Number(raw) || 0));
    const other = field === 'accepted' ? 'rejected' : 'accepted';
    setQuantities(previous => ({
      ...previous,
      [field]: value,
      [other]: Math.min(previous[other], maximum - value),
    }));
  }
  return (
    <div className="sales-fields">
      {(['accepted', 'rejected'] as const).map(field => (
        <label key={field}>
          {field === 'accepted' ? '签收数量' : '拒收数量'} {name}
          <input
            name={`${field === 'accepted' ? 'qty' : 'reject'}-${itemId}`}
            type="number"
            min="0"
            max={maximum}
            step="1"
            value={quantities[field]}
            onChange={event => change(field, event.target.value)}
          />
        </label>
      ))}
    </div>
  );
}
