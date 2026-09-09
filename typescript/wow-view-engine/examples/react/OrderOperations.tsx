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
  createContext,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Button } from '@ahoo-wang/fetcher-view-engine/react';
import type { OrderService } from './orderService.js';

interface OrderOperations {
  service: OrderService;
  busy: boolean;
  run(operation: () => Promise<string>, refresh: () => Promise<void>): void;
}
const OrderOperationsContext = createContext<OrderOperations | null>(null);
export function useOrderOperations() {
  const value = useContext(OrderOperationsContext);
  if (!value) throw new Error('订单扩展需要 OrderOperationsProvider。');
  return value;
}
export function OrderOperationsProvider({
  service,
  children,
}: {
  service: OrderService;
  children(busy: boolean): ReactNode;
}) {
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [notice, setNotice] = useState('');
  const [failure, setFailure] = useState<{
    message: string;
    retry(): void;
  } | null>(null);
  async function execute(work: () => Promise<void>) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await work();
    } catch (error) {
      setFailure({
        message: error instanceof Error ? error.message : '订单操作失败。',
        retry: () => {
          void execute(work);
        },
      });
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  function run(operation: () => Promise<string>, refresh: () => Promise<void>) {
    let written = false;
    void execute(async () => {
      // A committed write is never replayed when only its scoped refresh needs retrying.
      if (!written) {
        setNotice(await operation());
        written = true;
      }
      await refresh();
    });
  }
  return (
    <OrderOperationsContext.Provider value={{ service, busy, run }}>
      <p role="status" aria-label="订单操作状态">
        {busy ? '正在处理订单…' : notice || '订单操作就绪'}
      </p>
      {failure && (
        <div
          role="alert"
          style={{
            padding: 8,
            marginBottom: 8,
            border: '1px solid var(--fve-destructive)',
          }}
        >
          <p>{failure.message}</p>
          <Button variant="outline" onClick={failure.retry} disabled={busy}>
            重试订单操作
          </Button>
        </div>
      )}
      {children(busy)}
    </OrderOperationsContext.Provider>
  );
}
