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
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import {
  type ViewHost,
  resolveRecordPresentation,
  ViewEngine,
} from '@ahoo-wang/fetcher-view-engine';
import { Dialog } from '@base-ui/react/dialog';
import {
  Button,
  ViewPageContent,
  type RecordCardRenderContext,
} from '@ahoo-wang/fetcher-view-engine/react';
import { createOrderHost } from './host.js';
import { createOrderSource, type QueryOptions } from './querySource.js';
import { createOrderViews, orderDefinition, stageLabels } from './views.js';
import { roles, type Role, type Stage } from './model.js';
import {
  actionLabels,
  actionReason,
  actionRoles,
  createOrderService,
  nextOrderAction,
  type Action,
  type Command,
  type ServiceOptions,
} from './service.js';
import { OrderForms } from './OrderForms.js';
import { OrderDetails } from './OrderDetails.js';
import { OrderContext, orderExtensions } from './OrderExtensions.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import './sales-order.css';
export interface OrderWorkbenchProps extends QueryOptions, ServiceOptions {
  stage?: Stage;
  appearance?: 'light' | 'dark';
  initialRole?: Role;
  failRefreshAfterWrite?: boolean;
  localDefinition?: boolean;
  customRegions?: boolean;
  layout?: 'table' | 'card';
  persistViews?: boolean;
  initialSidebarCollapsed?: boolean;
  scopeKey?: string;
  createViewHost?: (resolveSource: ViewHost['resolveSource']) => ViewHost;
}
const briefs: Record<Stage, string> = {
  all: '从创建一笔订单开始，在岗位间接力完成审核、交付与结算；也可以打开已有订单继续处理。',
  review:
    '核对客户、商品、金额和承诺交期。审核不通过时写明原因，让销售修改后重新提交。',
  release:
    '预付款订单收齐后放行；有效账期客户可以先交付。留意未收齐和逾期客户。',
  delivery:
    '远航科技已备好 6 台显示器，先发出这批，剩余 4 台继续跟进。拒收商品先回仓再重发。',
  settlement:
    '逐笔核对收款、退款、开票与冲减。云杉传媒退回 1 台，需退款和冲减各 1,200 元。',
  aftersales:
    '退货先申请审核，再登记入库；完成退款与票据善后后核对结算，最后关闭订单。',
};
function DeliveryCard({ defaultContent, record }: RecordCardRenderContext) {
  const s = record.state as { remainingToShip: number };
  return (
    <div>
      {defaultContent}
      <p className="sales-card-note">
        交付关注：剩余 {s.remainingToShip} 件待发
      </p>
    </div>
  );
}
export function OrderWorkbench(props: OrderWorkbenchProps) {
  const [generation, setGeneration] = useState(0);
  return (
    <WorkbenchSession
      key={JSON.stringify([
        props.scopeKey,
        props.persistViews,
        props.stage ?? 'all',
        generation,
      ])}
      {...props}
      reset={() => setGeneration(g => g + 1)}
    />
  );
}
function WorkbenchSession({
  stage = 'all',
  appearance = 'light',
  initialRole = 'sales',
  reset,
  localDefinition = false,
  customRegions = false,
  layout = 'table',
  persistViews = false,
  initialSidebarCollapsed = false,
  scopeKey = 'sales-demo',
  createViewHost,
  ...options
}: OrderWorkbenchProps & { reset(): void }) {
  const [role, setRole] = useState<Role>(initialRole);
  const [runtime] = useState(() => {
    const service = createOrderService(options),
      source = createOrderSource(service.read, options),
      store = new Map<string, string | null>();
    const hosts = Object.fromEntries(
      Object.keys(roles).map(r => [
        r,
        createOrderHost(service, r as Role, stage, {
          store,
          source,
          personal: persistViews,
          persist: persistViews,
          scopeKey: `${scopeKey}:${r}`,
        }),
      ]),
    ) as Record<Role, ReturnType<typeof createOrderHost>>;
    return { service, source, hosts };
  });
  const [dialog, setDialog] = useState<{
    action: Action | 'detail';
    id?: string;
    refresh?: () => Promise<void>;
  } | null>(null);
  const popupRef = useRef<HTMLDivElement>(null);
  function showDialog(next: Parameters<typeof setDialog>[0]) {
    // Prevent the popup's deferred focus restoration from stealing typing after a focused control unmounts.
    popupRef.current?.focus();
    setDialog(next);
  }
  const [busy, setBusy] = useState(false),
    busyRef = useRef(false);
  const [notice, setNotice] = useState('');
  const [failure, setFailure] = useState<{
    written: boolean;
    message: string;
    retry(): Promise<void>;
  } | null>(null);
  const [version, setVersion] = useState(0);
  const [failedRefresh, setFailedRefresh] = useState(false);
  const [reopen, setReopen] = useState(0);
  const localViews = useMemo(() => {
    const views = createOrderViews(stage);
    if (layout === 'card')
      for (const v of views.instances)
        v.config.presentation = resolveRecordPresentation(
          orderDefinition,
          'card',
          v.config.presentation,
        );
    return views;
  }, [stage, layout]);
  const [externalHost] = useState(() => createViewHost?.(() => runtime.source));
  const host = externalHost ?? runtime.hosts[role];
  const [owned, setOwned] = useState<{
    engine: ViewEngine;
    role: Role;
    generation: number;
  } | null>(null);
  useEffect(() => {
    const engine = new ViewEngine({
      definitionId: orderDefinition.id,
      host,
      ...(!externalHost && (localDefinition || layout === 'card')
        ? { definition: orderDefinition, instances: localViews }
        : {}),
      filterCompilers: orderExtensions.filters,
    });
    // eslint-disable-next-line react-hooks/set-state-in-effect -- publish the externally owned engine; create afresh on StrictMode effect replay.
    setOwned({ engine, role, generation: reopen });
    void engine.load().catch(() => {});
    return () => engine.dispose();
  }, [host, externalHost, localDefinition, layout, localViews, reopen, role]);
  const engine =
    owned?.role === role && owned.generation === reopen ? owned.engine : null;
  const viewState = useSyncExternalStore(
    engine?.subscribe ?? (() => () => {}),
    engine?.getSnapshot ?? (() => null),
  );
  const viewReady = viewState?.status === 'ready';
  function changeRole(next: Role) {
    if (busy || failure?.written) return;
    setFailure(null);
    setRole(next);
    showDialog(current =>
      current ? { ...current, action: 'detail', refresh: undefined } : null,
    );
  }
  const selected = dialog?.id
    ? runtime.service.read().find(o => o.aggregateId === dialog.id)
    : undefined;
  const selectedFacts = selected
    ? {
        ...selected.state,
        items: selected.state.items.map(i => ({
          id: i.id,
          sku: i.productId,
          name: i.productName,
          quantity: i.quantity,
          unitPriceCents: Math.round(i.price * 100),
        })),
      }
    : undefined;
  const next = selectedFacts ? nextOrderAction(selectedFacts) : null;
  async function execute(command: Command, refresh: () => Promise<void>) {
    if (busyRef.current) return;
    const actor = role,
      requestId = crypto.randomUUID();
    let written = false;
    async function work() {
      if (busyRef.current) return;
      busyRef.current = true;
      setBusy(true);
      setFailure(null);
      try {
        if (!written) {
          const result = await runtime.service.execute(
            command,
            actor,
            requestId,
          );
          written = true;
          setVersion(v => v + 1);
          setNotice(
            `${actionLabels[command.type]}成功 · ${result.map(o => o.aggregateId).join('、')}`,
          );
          if (command.type === 'create')
            showDialog({
              action: 'detail',
              id: result[0].aggregateId,
              refresh,
            });
          else showDialog(d => (d ? { ...d, action: 'detail' } : null));
          if (options.failRefreshAfterWrite && !failedRefresh) {
            runtime.source.failNextRead();
            setFailedRefresh(true);
          }
        }
        await refresh();
      } catch (error) {
        const message = error instanceof Error ? error.message : '操作失败';
        setFailure({
          written,
          message: written ? `操作已完成，列表未刷新：${message}` : message,
          retry: work,
        });
        if (!written) throw error;
      } finally {
        busyRef.current = false;
        setBusy(false);
      }
    }
    await work();
  }
  function batch(
    action: 'batchApprove' | 'batchRelease',
    ids: string[],
    refresh: () => Promise<void>,
  ) {
    void execute({ type: action, orderIds: ids }, refresh).catch(() => {});
  }
  const available = (Object.keys(actionLabels) as Action[]).filter(
    a =>
      !['create', 'batchApprove', 'batchRelease'].includes(a) &&
      actionRoles[a] === role,
  );
  const feedback = (
    <>
      <div role="status" className="sales-notice">
        {busy ? '正在处理订单…' : notice}
      </div>
      {failure && (
        <div role="alert" className="sales-error">
          <p>{failure.message}</p>
          <Button
            variant="outline"
            disabled={busy}
            onClick={() => void failure.retry().catch(() => {})}
          >
            {failure.written ? '重试刷新' : '重试业务操作'}
          </Button>
        </div>
      )}
    </>
  );
  return (
    <div className="fve-root sales-workbench" data-theme={appearance}>
      <header className="sales-header">
        <div>
          <p className="sales-eyebrow">远川办公 · 销售订单中心</p>
          <h2>
            {stage === 'all' ? '每一笔订单，都有下一步' : stageLabels[stage]}
          </h2>
          <p className="sales-muted">{briefs[stage]}</p>
        </div>
        <div className="sales-controls">
          <label>
            演示岗位
            <select
              aria-label="演示岗位"
              value={role}
              disabled={busy || !!failure?.written}
              onChange={e => {
                changeRole(e.target.value as Role);
              }}
            >
              {Object.entries(roles).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <Button variant="outline" onClick={reset} disabled={busy}>
            重置演示
          </Button>
        </div>
      </header>
      <p className="sales-context">
        演示日期：2026 年 9 月 10 日 · 当前岗位：{roles[role]} ·
        故事之间独立初始化；页内视图切换保留订单
      </p>
      <OrderContext.Provider
        value={{
          role,
          busy: busy || !!failure?.written,
          open: (action, id, refresh) => {
            if (!failure?.written) setFailure(null);
            showDialog({ action, id, refresh });
          },
          batch,
        }}
      >
        {engine ? (
          <ViewPageContent
            engine={engine}
            extensions={orderExtensions}
            selectable
            initialSidebarCollapsed={initialSidebarCollapsed}
            autoRefreshPaused={busy}
            renderCard={customRegions ? DeliveryCard : undefined}
            renderToolbar={
              customRegions
                ? context => (
                    <>
                      <p className="sales-card-note">
                        交付关注：先处理已超期且仍未发完的订单。
                      </p>
                      {context.defaultContent}
                    </>
                  )
                : undefined
            }
          />
        ) : (
          <p role="status">正在加载视图…</p>
        )}
      </OrderContext.Provider>
      {!dialog && feedback}
      <details className="sales-dev" open={persistViews || !!externalHost}>
        <summary>开发者：查看接入与保存结果</summary>
        <p>
          ViewDefinition 定义字段和能力，ViewInstance
          保存条件及布局；业务命令由独立宿主校验。视图保存不保存订单数据。
        </p>
        <Button
          variant="outline"
          disabled={busy || !!failure?.written}
          onClick={() => {
            setFailure(null);
            setReopen(v => v + 1);
          }}
        >
          重新打开已保存视图
        </Button>
        {persistViews && (
          <Button
            variant="outline"
            disabled={busy || !!failure?.written}
            onClick={async () => {
              setFailure(null);
              await runtime.hosts[role].reset();
              setReopen(v => v + 1);
            }}
          >
            重置测试服务
          </Button>
        )}
        {externalHost?.permission?.refresh && (
          <Button
            variant="outline"
            onClick={() => void externalHost.permission!.refresh!()}
          >
            同步服务权限
          </Button>
        )}
        <p>本次成功操作版本：{version}</p>
        <pre>
          {JSON.stringify(
            {
              definitionId: orderDefinition.id,
              sourceId: orderDefinition.sourceId,
              extensions: Object.keys(orderExtensions),
            },
            null,
            2,
          )}
        </pre>
      </details>
      <Dialog.Root
        open={dialog !== null}
        onOpenChange={open => {
          if (!open && !busy) showDialog(null);
        }}
      >
        <Dialog.Portal>
          <div className="fve-root sales-dialog-scope" data-theme={appearance}>
            <Dialog.Backdrop className="sales-backdrop" />
            <Dialog.Popup ref={popupRef} className="sales-sheet">
              <header className="sales-sheet-header">
                <Dialog.Title>
                  {dialog?.action === 'create'
                    ? '创建销售订单'
                    : dialog?.action === 'detail'
                      ? `订单详情 ${dialog.id}`
                      : dialog
                        ? actionLabels[dialog.action]
                        : ''}
                </Dialog.Title>
                <Dialog.Description className="sales-muted">
                  {dialog?.action === 'detail'
                    ? '核对订单记录，再选择当前岗位要执行的操作。'
                    : '填写本次业务信息，确认后更新订单。'}
                </Dialog.Description>
                <Dialog.Close
                  render={<Button variant="ghost" />}
                  className="sales-close"
                  disabled={busy}
                  aria-label="关闭详情"
                >
                  关闭
                </Dialog.Close>
              </header>
              {dialog?.action === 'detail' && selected ? (
                <div className="sales-sheet-body">
                  {feedback}
                  <section className="sales-next" aria-label="订单下一步">
                    <div>
                      <strong>
                        {next
                          ? `下一步：${roles[next.role]} · ${actionLabels[next.action]}`
                          : '订单已关闭，全部必要工作已完成'}
                      </strong>
                      {next?.reason && <p>{next.reason}</p>}
                      <p>
                        关闭条件：履约及售后完成、资金与票据结清、完成结算核对。
                      </p>
                    </div>
                    {next && next.role !== role && (
                      <Button
                        disabled={busy || !!failure?.written}
                        onClick={() => changeRole(next.role)}
                      >
                        交给{roles[next.role]}
                      </Button>
                    )}
                    <label>
                      处理岗位
                      <select
                        aria-label="处理岗位"
                        value={role}
                        disabled={busy || !!failure?.written}
                        onChange={event =>
                          changeRole(event.target.value as Role)
                        }
                      >
                        {Object.entries(roles).map(([value, label]) => (
                          <option key={value} value={value}>
                            {label}
                          </option>
                        ))}
                      </select>
                    </label>
                  </section>
                  <section className="sales-actions">
                    <h3>{roles[role]} · 可执行操作</h3>
                    {available.map(action => {
                      const reason = actionReason(selectedFacts!, action, role);
                      return (
                        <div key={action}>
                          <Button
                            variant={
                              next?.action === action ? 'default' : 'outline'
                            }
                            disabled={
                              busy ||
                              !viewReady ||
                              !!failure?.written ||
                              !!reason
                            }
                            onClick={() => showDialog({ ...dialog, action })}
                          >
                            {actionLabels[action]}
                          </Button>
                          {reason && <small>{reason}</small>}
                        </div>
                      );
                    })}
                  </section>
                  <OrderDetails order={selected} />
                </div>
              ) : (
                dialog && (
                  <OrderForms
                    key={`${dialog.action}:${dialog.id}`}
                    commandType={dialog.action as Action}
                    order={selected}
                    busy={busy || !viewReady}
                    onCancel={() =>
                      showDialog(
                        selected ? { ...dialog, action: 'detail' } : null,
                      )
                    }
                    onSubmit={command =>
                      execute(
                        command,
                        dialog.refresh ?? (() => engine!.refresh()),
                      )
                    }
                  />
                )
              )}
            </Dialog.Popup>
          </div>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}
