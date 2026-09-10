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

import { currency } from './OrderForms.js';
import { lifecycleLabels, roles, type OrderSnapshot } from './model.js';
export function OrderDetails({ order }: { order: OrderSnapshot }) {
  const s = order.state;
  return (
    <div className="sales-details">
      <div className="sales-facts">
        <span>{s.customer}</span>
        <span aria-label="订单状态">{lifecycleLabels[s.lifecycle]}</span>
        <span>{s.terms === 'credit' ? '月结账期' : '全额预付'}</span>
        <span>履约：{s.fulfillmentStatus}</span>
        <span>售后：{s.aftersaleStatus}</span>
        <span>关闭：{s.closureStatus}</span>
      </div>
      <dl className="sales-order-context">
        {[
          ['负责人', s.owner],
          ['信用情况', s.creditStatus],
          [
            '承诺交期',
            new Intl.DateTimeFormat('zh-CN', {
              timeZone: 'Asia/Shanghai',
              dateStyle: 'medium',
            }).format(s.dueAt),
          ],
          [
            '应收到期日',
            new Intl.DateTimeFormat('zh-CN', {
              timeZone: 'Asia/Shanghai',
              dateStyle: 'medium',
            }).format(s.paymentDueAt),
          ],
          ['放行条件', s.releaseStatus],
          ['结算核对', s.settlementStatus],
        ].map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      <dl className="sales-totals">
        {[
          ['订单金额', s.totalAmount],
          ['有效应收', s.receivableAmount],
          ['净收款', s.netReceived],
          ['待收款', s.amountDue],
          ['待退款', s.refundDue],
          ['待冲减', s.creditDue],
        ].map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{currency(Number(value))}</dd>
          </div>
        ))}
      </dl>
      <div className="sales-table-scroll">
        <table>
          <caption>商品与履约数量</caption>
          <thead>
            <tr>
              {[
                '商品',
                '订购',
                '单价',
                '待发',
                '待签收',
                '已签收',
                '拒收待处理',
                '已退货',
              ].map(v => (
                <th key={v}>{v}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {s.items.map(i => (
              <tr key={i.id}>
                <td>{i.productName}</td>
                <td>{i.quantity}</td>
                <td>{currency(i.price)}</td>
                <td>{i.remainingToShip}</td>
                <td>{i.pendingReceipt}</td>
                <td>{i.signed}</td>
                <td>{i.rejected}</td>
                <td>{i.returned}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details>
        <summary>收付款与票据记录</summary>
        {(
          [
            ['收款', s.receiptsOfMoney],
            ['退款', s.refunds],
            ['开票', s.invoices],
            ['冲减', s.credits],
          ] as const
        ).map(([label, records]) => (
          <section key={label}>
            <h3>{label}</h3>
            {records.length ? (
              records.map(r => (
                <p key={r.id}>
                  {r.reference} · {currency(r.amountCents / 100)}
                </p>
              ))
            ) : (
              <p className="sales-muted">尚无记录</p>
            )}
          </section>
        ))}
      </details>
      <details>
        <summary>交付与售后记录</summary>
        {s.shipments.map((sh, index) => (
          <section key={sh.id}>
            <h3>
              第 {index + 1} 次发货 · {sh.tracking}
            </h3>
            <p>
              {sh.lines
                .map(
                  l =>
                    `${s.items.find(i => i.id === l.itemId)?.productName} × ${l.quantity}`,
                )
                .join('、')}
            </p>
            {s.receipts
              .filter(r => r.shipmentId === sh.id)
              .map((r, i) => (
                <p key={i}>
                  签收 {r.accepted.reduce((n, l) => n + l.quantity, 0)} · 拒收{' '}
                  {r.rejected.reduce((n, l) => n + l.quantity, 0)}
                </p>
              ))}
          </section>
        ))}
        {s.returns.map((r, i) => (
          <p key={r.id}>
            退货申请 {i + 1} · {r.approved ? '已审核' : '待审核'} · 已入库{' '}
            {r.received.reduce((n, l) => n + l.quantity, 0)}
          </p>
        ))}
      </details>
      <details>
        <summary>操作时间线（{s.history.length}）</summary>
        <ol>
          {s.history.map((h, i) => (
            <li key={i}>
              {new Intl.DateTimeFormat('zh-CN', {
                timeZone: 'Asia/Shanghai',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              }).format(h.at)}{' '}
              · {roles[h.actor]} · {h.action}
              {h.reason ? `：${h.reason}` : ''}
            </li>
          ))}
        </ol>
      </details>
    </div>
  );
}
