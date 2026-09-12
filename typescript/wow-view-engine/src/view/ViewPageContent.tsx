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

import { AnalysisView } from '../analysis/AnalysisView.js';
import { PanelLeftOpenIcon } from 'lucide-react';
import { useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Button } from '../components/ui/button.js';
import { cn } from '../lib/utils.js';
import { RecordView, type RecordViewProps } from '../record/RecordView.js';
import { useViewExpansion, ViewExpansionContext } from './viewExpansion.js';
import { ViewManager } from './ViewManager.js';
import { ViewConflict } from './ViewConflict.js';
import { ViewInstanceActions } from './ViewInstanceActions.js';
import {
  groupViewInstances,
  ViewInstanceSwitcher,
  ViewSidebar,
} from './ViewNavigation.js';

import type { ViewEngine } from '../engine/ViewEngine.js';
import type { ViewExtensions } from './viewReactTypes.js';

import { useViewCapabilities } from './useViewCapabilities.js';

export interface ViewPageContentProps {
  engine: ViewEngine;
  extensions?: ViewExtensions;
  filterContext?: unknown;
  className?: string;
  initialSidebarCollapsed?: boolean;
  /** Record-only presentation and business-operation controls. */
  record?: Pick<
    RecordViewProps,
    | 'selectable'
    | 'autoRefreshPaused'
    | 'renderToolbar'
    | 'renderCard'
    | 'renderPagination'
  >;
}
/** Compose a caller-owned engine into the same page UI without transferring lifecycle ownership. */
export function ViewPageContent({
  engine,
  initialSidebarCollapsed = false,
  className,
  extensions,
  filterContext,
  record,
}: ViewPageContentProps) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const capabilities = useViewCapabilities(engine);
  const pageRef = useRef<HTMLDivElement>(null);
  const expansion = useViewExpansion(pageRef, state.status === 'ready');
  const [collapsed, setCollapsed] = useState(initialSidebarCollapsed);
  const sidebarToggleRef = useRef<HTMLButtonElement>(null);
  const restoreSidebarFocus = useRef(false);
  useLayoutEffect(() => {
    if (restoreSidebarFocus.current) {
      restoreSidebarFocus.current = false;
      sidebarToggleRef.current?.focus();
    }
  }, [collapsed]);
  function toggleSidebar() {
    restoreSidebarFocus.current = true;
    setCollapsed(value => !value);
  }
  const [managerOpen, setManagerOpen] = useState(false);
  const switcherTrigger = useRef<HTMLButtonElement>(null);
  const managerReturnFocus = useRef<HTMLElement>(null);
  const [actionError, setActionError] = useState<{
    instanceId: string | null;
    message: string;
  } | null>(null);
  const [configurationPanels, setConfigurationPanels] = useState(
    () => new Map<string, boolean>(),
  );
  const id = state.selectedInstanceId;
  const session = id ? state.sessions[id] : undefined;
  function run(action: () => void | Promise<void>) {
    const instanceId = engine.getSnapshot().selectedInstanceId;
    setActionError(null);
    try {
      void Promise.resolve(action()).catch(error =>
        setActionError({
          instanceId,
          message: error instanceof Error ? error.message : '操作失败',
        }),
      );
    } catch (error) {
      setActionError({
        instanceId,
        message: error instanceof Error ? error.message : '操作失败',
      });
    }
  }
  if (state.status === 'idle' || state.status === 'loading')
    return (
      <div className="fve-root fve:p-4" role="status">
        正在加载视图…
      </div>
    );
  if (state.status === 'error')
    return (
      <div className="fve-root fve:flex fve:flex-col fve:items-start fve:gap-3 fve:p-4">
        <p role="alert">{state.error}</p>
        <Button variant="outline" onClick={() => run(() => engine.load())}>
          重新加载视图
        </Button>
      </div>
    );
  const currentActionError =
    actionError?.instanceId === id ? actionError.message : null;
  const groups = groupViewInstances(state);
  const navigation = {
    groups,
    selectedId: id,
    onSelect: (next: string) => run(() => engine.selectInstance(next)),
    onManage: (trigger: HTMLElement | null) => {
      managerReturnFocus.current = trigger;
      setManagerOpen(true);
    },
  };
  const toolbarStart = (
    <div className="fve:flex fve:min-w-0 fve:flex-wrap fve:items-center fve:gap-2">
      {collapsed && (
        <Button
          variant="ghost"
          size="icon"
          className="fve:hidden fve:@min-[64rem]/view-page:inline-flex"
          aria-label="展开视图列表"
          ref={sidebarToggleRef}
          onClick={toggleSidebar}
        >
          <PanelLeftOpenIcon aria-hidden="true" />
        </Button>
      )}
      <h1
        className={cn(
          'fve:text-lg fve:font-semibold',
          !collapsed && 'fve:@min-[64rem]/view-page:hidden',
        )}
      >
        {state.definition?.title}
      </h1>
      <div
        className={cn(
          'fve:max-w-full',
          !collapsed && 'fve:@min-[64rem]/view-page:hidden',
        )}
      >
        <ViewInstanceSwitcher
          {...navigation}
          triggerRef={switcherTrigger}
          managerOpen={managerOpen}
        />
      </div>
      {session && (
        <h2
          className={cn(
            'fve:text-sm fve:font-medium fve:text-foreground',
            collapsed
              ? 'fve:hidden'
              : 'fve:hidden fve:@min-[64rem]/view-page:inline',
          )}
        >
          {session.instance.title}
        </h2>
      )}
      {session && (
        <ViewInstanceActions
          key={session.instance.id}
          engine={engine}
          session={session}
          run={run}
        />
      )}
    </div>
  );
  return (
    <ViewExpansionContext.Provider value={expansion}>
      <div
        ref={pageRef}
        className={cn(
          'fve-root fve:@container/view-page fve:flex fve:min-w-0 fve:gap-4 fve:bg-background fve:p-3 fve:text-foreground',
          className,
        )}
        data-slot="view-page"
      >
        {/* Keep management mounted when the popup closes or the selected record view changes. */}
        <div className="fve:absolute">
          <ViewManager
            engine={engine}
            groups={groups}
            open={managerOpen}
            onOpenChange={setManagerOpen}
            finalFocus={() => {
              const target = managerReturnFocus.current;
              return target?.isConnected && target.getClientRects().length
                ? target
                : switcherTrigger.current;
            }}
          />
        </div>
        {!collapsed && (
          <ViewSidebar
            {...navigation}
            title={state.definition?.title ?? ''}
            toggleRef={sidebarToggleRef}
            onCollapse={toggleSidebar}
          />
        )}
        <main
          tabIndex={-1}
          aria-label="视图工作区"
          className="fve:flex fve:min-w-0 fve:flex-1 fve:flex-col fve:gap-3"
        >
          {!session && <header>{toolbarStart}</header>}
          {Object.entries(state.pendingCreates).map(([sourceId, pending]) => (
            <div
              key={sourceId}
              role="alert"
              aria-label={`待核对另存：${pending.instance.title}`}
              className="fve:flex fve:flex-wrap fve:items-center fve:gap-2 fve:rounded-lg fve:border fve:p-3 fve:text-sm"
            >
              <span>
                {pending.instance.title}：{pending.writeError}
              </span>
              <Button
                variant="outline"
                disabled={!capabilities.instances[sourceId]?.reload}
                onClick={() => {
                  void engine.reloadInstance(sourceId).catch(() => {});
                }}
              >
                核对另存结果
              </Button>
            </div>
          ))}
          {(state.error || currentActionError || session?.writeError) && (
            <div
              role="alert"
              className="fve:flex fve:flex-wrap fve:items-center fve:gap-2 fve:rounded-lg fve:border fve:border-destructive/30 fve:p-3 fve:text-sm fve:text-destructive"
            >
              <span>
                {session?.writeError || state.error || currentActionError}
              </span>
              {session?.writeError &&
                capabilities.instances[session.instance.id]?.reload && (
                  <Button
                    variant="outline"
                    onClick={() =>
                      run(() => engine.reloadInstance(session.instance.id))
                    }
                  >
                    重新加载并保留编辑
                  </Button>
                )}
            </div>
          )}
          {session?.conflict && (
            <ViewConflict
              key={`conflict-${id}`}
              engine={engine}
              session={session}
            />
          )}
          {
            <AnalysisView
              engine={engine}
              extensions={extensions}
              filterContext={filterContext}
              toolbarStart={toolbarStart}
              configurationOpen={configurationPanels.get(id!)}
              onConfigurationOpenChange={open =>
                setConfigurationPanels(panels => new Map(panels).set(id!, open))
              }
            />
          }
          {session?.kind === 'record' ? (
            <RecordView
              key={id}
              engine={engine}
              {...record}
              extensions={extensions}
              filterContext={filterContext}
              configurationOpen={configurationPanels.get(id!) ?? true}
              onConfigurationOpenChange={open =>
                setConfigurationPanels(panels => new Map(panels).set(id!, open))
              }
              toolbarStart={toolbarStart}
            />
          ) : !session ? (
            <div className="fve:rounded-lg fve:border fve:border-dashed fve:p-10 fve:text-center fve:text-sm fve:text-muted-foreground">
              {state.openingInstanceId
                ? '正在打开视图实例…'
                : state.instanceIds.length
                  ? '请选择一个视图实例'
                  : '暂无可用视图，请由宿主配置视图实例'}
            </div>
          ) : null}
        </main>
      </div>
    </ViewExpansionContext.Provider>
  );
}
