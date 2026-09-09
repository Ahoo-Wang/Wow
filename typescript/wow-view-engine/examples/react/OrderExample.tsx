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

import { useState } from 'react';
import {
  LocalStorageViewHost,
  type ViewHost,
  type LocalStorageViewHostOptions,
} from '@ahoo-wang/fetcher-view-engine';
import { Button, ViewPage } from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import { orderDefinition, orderViews } from './orders.js';
import {
  createOrderService,
  type OrderServiceOptions,
} from './orderService.js';
import { OrderOperationsProvider } from './OrderOperations.js';
import { orderExtensions } from './OrderExtensions.js';

export interface OrderExampleProps extends OrderServiceOptions {
  scopeKey?: string;
  appearance?: 'light' | 'dark';
  initialSidebarCollapsed?: boolean;
  /** Development-only persistence of view configuration in this browser. */
  persistViews?: boolean;
  /** Optional development adapter; the example stays independent of its transport. */
  createViewHost?: (resolveSource: ViewHost['resolveSource']) => ViewHost;
}
/** Copy this directory into a React app and render <OrderExample scopeKey="user:tenant:access" />. */
export function OrderExample({
  scopeKey = 'local-user:demo-orders',
  persistViews = false,
  createViewHost,
  ...props
}: OrderExampleProps) {
  return (
    <ScopedOrders
      key={JSON.stringify([scopeKey, persistViews])}
      scopeKey={scopeKey}
      persistViews={persistViews}
      createViewHost={createViewHost}
      {...props}
    />
  );
}
function ScopedOrders({
  scopeKey,
  appearance = 'light',
  initialSidebarCollapsed = true,
  persistViews = false,
  createViewHost,
  ...options
}: OrderExampleProps & { scopeKey: string }) {
  // One local service per access scope; production hosts should enforce the same scope server-side.
  const [service] = useState(() => createOrderService(options));
  const localOptions: LocalStorageViewHostOptions | null = persistViews
    ? {
        scopeKey,
        storage: localStorage,
        serviceKey: 'demo-view-service',
        lock: (name, operation, signal) =>
          navigator.locks.request(name, { signal }, operation),
        definition: orderDefinition,
        instances: orderViews,
        resolveSource: (id: string) => service.host.resolveSource(id),
      }
    : null;
  const [host, setHost] = useState(() =>
    createViewHost
      ? createViewHost(id => service.host.resolveSource(id))
      : localOptions
        ? new LocalStorageViewHost(localOptions)
        : service.host,
  );
  const [generation, setGeneration] = useState(0);
  const [storageError, setStorageError] = useState<string>();
  function reopen() {
    if (createViewHost)
      setHost(createViewHost(id => service.host.resolveSource(id)));
    else if (localOptions) setHost(new LocalStorageViewHost(localOptions));
    setGeneration(value => value + 1);
  }
  return (
    <div
      className="fve-root"
      data-theme={appearance}
      style={{ padding: 12, minWidth: 0, background: 'var(--fve-background)' }}
    >
      <p style={{ marginTop: 0 }}>
        {createViewHost
          ? '开发适配器验证；业务记录由独立订单服务提供。'
          : persistViews
            ? '浏览器保存视图配置；刷新页面后订单数据恢复初始值。'
            : '本地订单演示，修改仅保留在当前页面。'}
      </p>
      {(host instanceof LocalStorageViewHost || createViewHost) && (
        <div className="fve:mb-3 fve:flex fve:gap-2">
          <Button variant="outline" onClick={reopen}>
            重新打开已保存视图
          </Button>
          {host instanceof LocalStorageViewHost ? (
            <Button
              variant="outline"
              onClick={async () => {
                try {
                  await host.reset();
                  setStorageError(undefined);
                  reopen();
                } catch (error) {
                  setStorageError(
                    error instanceof Error ? error.message : '本地存储重置失败',
                  );
                }
              }}
            >
              重置测试服务
            </Button>
          ) : host.permission?.refresh ? (
            <Button
              variant="outline"
              onClick={async () => {
                try {
                  await host.permission!.refresh!();
                  setStorageError(undefined);
                } catch (error) {
                  setStorageError(
                    error instanceof Error ? error.message : '权限同步失败',
                  );
                }
              }}
            >
              同步服务权限
            </Button>
          ) : null}
        </div>
      )}
      {storageError && <p role="alert">{storageError}</p>}
      <OrderOperationsProvider service={service}>
        {busy => (
          <ViewPage
            key={generation}
            scopeKey={scopeKey}
            definitionId={orderDefinition.id}
            {...(!persistViews &&
              !createViewHost && {
                definition: orderDefinition,
                instances: orderViews,
              })}
            host={host}
            extensions={orderExtensions}
            selectable
            autoRefreshPaused={busy}
            initialSidebarCollapsed={initialSidebarCollapsed}
          />
        )}
      </OrderOperationsProvider>
    </div>
  );
}
