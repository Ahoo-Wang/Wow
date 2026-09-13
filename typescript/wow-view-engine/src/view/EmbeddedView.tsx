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
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import {
  ExternalLinkIcon,
  RefreshCwIcon,
  SlidersHorizontalIcon,
} from 'lucide-react';
import type {
  ViewEngine,
  ViewPosition,
  DataViewPosition,
} from '../engine/ViewEngine.js';
import type { ViewDefinition, ViewInstance } from '../contracts/viewModel.js';
import type { ViewEngineBinding } from '../react/useViewEngine.js';
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';
import type { FilterConfiguration } from '../filter/filterModel.js';
import { message } from '../lib/snapshot.js';
import { cn } from '../lib/utils.js';
import { Button } from '../components/ui/button.js';
import { FilterPanel } from '../filter/FilterPanel.js';
import { DashboardView } from '../dashboard/DashboardView.js';
import { DataViewContent, useDataViewSession } from './DataViewContent.js';
import type { ViewExtensions } from './viewReactTypes.js';

export interface EmbeddedViewProps extends ViewEngineBinding {
  instanceId: string;
  /** Contextual title; distinguish repeated embeddings of the same saved view. */
  title?: string;
  filterContext?: unknown;
  className?: string;
  onOpenView?(identity: {
    instanceId: string;
    definitionId: string;
  }): void | Promise<void>;
}

/** Owns an isolated browsing position; the supplied engine remains caller-owned. */
export function EmbeddedView({ engine, error, ...props }: EmbeddedViewProps) {
  if (!engine)
    return (
      <div className="fve-root fve:p-4" role={error ? 'alert' : 'status'}>
        {error ?? '正在加载嵌入视图…'}
      </div>
    );
  return <OwnedEmbeddedView {...props} engine={engine} />;
}
function OwnedEmbeddedView({
  engine,
  instanceId,
  title,
  extensions,
  filterContext,
  className,
  onOpenView,
}: Omit<EmbeddedViewProps, 'engine' | 'error'> & { engine: ViewEngine }) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const instance =
    state.status === 'ready' && state.instanceIds.includes(instanceId)
      ? state.sessions[instanceId]?.baseline
      : undefined;
  const definition = state.definition;
  const [attempt, setAttempt] = useState(0);
  const [owned, setOwned] = useState<{
    engine: ViewEngine;
    instance: DeepReadonly<ViewInstance>;
    definition: DeepReadonly<ViewDefinition>;
    position?: ViewPosition;
    error?: string;
  } | null>(null);
  useEffect(() => {
    if (!instance || !definition || state.status !== 'ready') return;
    let live = true;
    let position: ViewPosition | undefined;
    try {
      position = engine.openPosition(
        cloneSnapshot<ViewInstance>(instance),
        cloneSnapshot<ViewDefinition>(definition),
        { queryPolicy: 'queue' },
      );
      const start =
        position.kind === 'dashboard'
          ? position.runtime.resume()
          : position.kind === 'record'
            ? position.commands.refresh()
            : position.commands.run();
      const current = position;
      void start.catch(reason => {
        if (!live) return;
        try {
          const snapshot = current.getSnapshot();
          const reported =
            'panels' in snapshot
              ? snapshot.error || snapshot.validation.length
              : snapshot.queryError || snapshot.validation.length;
          if (reported) return;
        } catch {
          return;
        }
        setOwned({
          engine,
          instance,
          definition,
          position: current,
          error: message(reason),
        });
      });
      // eslint-disable-next-line react-hooks/set-state-in-effect -- publish the externally allocated position and pair it with effect cleanup.
      setOwned({ engine, instance, definition, position });
      return () => {
        live = false;
        current.dispose();
      };
    } catch (reason) {
      position?.dispose();
      setOwned({ engine, instance, definition, error: message(reason) });
    }
  }, [engine, instance, definition, state.status, attempt]);
  const active =
    owned?.engine === engine &&
    owned.instance === instance &&
    owned.definition === definition
      ? owned
      : null;
  const [navigationError, setNavigationError] = useState<{
    positionId: string;
    message: string;
  } | null>(null);
  function open() {
    const positionId = active?.position?.identity.id;
    if (!positionId || !instance) return;
    const failed = (reason: unknown) =>
      setNavigationError({ positionId, message: message(reason) });
    setNavigationError(null);
    try {
      void Promise.resolve(
        onOpenView?.({ instanceId, definitionId: instance.definitionId }),
      ).catch(failed);
    } catch (reason) {
      failed(reason);
    }
  }
  if (state.status === 'error')
    return (
      <div role="alert">
        {state.error}
        <Button
          onClick={() => {
            void engine.load().catch(() => {});
          }}
        >
          重试加载
        </Button>
      </div>
    );
  if (state.status !== 'ready') return <p role="status">正在加载嵌入视图…</p>;
  if (!instance) return <p role="alert">嵌入视图不存在或当前不可访问。</p>;
  if (active?.error)
    return (
      <div role="alert">
        {active.error}
        <Button onClick={() => setAttempt(value => value + 1)}>重试嵌入</Button>
      </div>
    );
  if (!active?.position || !definition)
    return <p role="status">正在准备嵌入视图…</p>;
  const position = active.position;
  const displayTitle = title?.trim() || instance.title;
  const heading = (
    <div className="fve:flex fve:min-w-0 fve:items-center fve:gap-2">
      <h2
        className="fve:min-w-0 fve:truncate fve:font-semibold"
        title={displayTitle}
      >
        {displayTitle}
      </h2>
      {onOpenView && (
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`打开完整视图：${displayTitle}`}
          onClick={open}
        >
          <ExternalLinkIcon aria-hidden="true" />
        </Button>
      )}
    </div>
  );
  return (
    <section
      className={cn('fve-root fve:min-w-0', className)}
      aria-label={`嵌入视图：${displayTitle}`}
    >
      {navigationError?.positionId === position.identity.id && (
        <p role="alert">{navigationError.message}</p>
      )}
      {position.kind === 'dashboard' ? (
        <DashboardView
          key={position.identity.id}
          runtime={position.runtime}
          title={displayTitle}
          extensions={extensions}
          filterContext={filterContext}
          toolbarStart={heading}
        />
      ) : (
        <EmbeddedDataView
          key={position.identity.id}
          position={position}
          definition={definition}
          engine={engine}
          extensions={extensions}
          filterContext={filterContext}
          heading={heading}
          label={displayTitle}
        />
      )}
    </section>
  );
}
function EmbeddedDataView({
  position,
  definition,
  engine,
  extensions,
  filterContext,
  heading,
  label,
}: {
  position: DataViewPosition;
  definition: DeepReadonly<ViewDefinition>;
  engine: ViewEngine;
  extensions?: ViewExtensions;
  filterContext?: unknown;
  heading: ReactNode;
  label: string;
}) {
  const session = useDataViewSession(position);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [draft, setDraft] = useState<DeepReadonly<FilterConfiguration> | null>(
    null,
  );
  const [valid, setValid] = useState(true);
  const [operationError, setOperationError] = useState<string | null>(null);
  async function refresh() {
    setOperationError(null);
    try {
      if (
        position.kind === 'analysis' &&
        position.getSnapshot().queryStatus !== 'success'
      )
        await position.commands.run();
      else await position.commands.refresh();
    } catch (reason) {
      try {
        if (!position.getSnapshot().queryError)
          setOperationError(message(reason));
      } catch {
        /* Position lifetime ended. */
      }
    }
  }
  if (!session) return null;
  const querying =
    session.queryStatus === 'loading' || session.queryStatus === 'waiting';
  return (
    <div className="fve:flex fve:min-w-0 fve:flex-col fve:gap-3">
      <header className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2">
        {heading}
        <div className="fve:flex fve:gap-2">
          <Button
            variant="outline"
            aria-pressed={filtersOpen}
            onClick={() => setFiltersOpen(value => !value)}
          >
            <SlidersHorizontalIcon aria-hidden="true" />
            筛选
          </Button>
          <Button
            variant="outline"
            disabled={querying}
            onClick={() => {
              void refresh();
            }}
          >
            <RefreshCwIcon aria-hidden="true" />
            刷新
          </Button>
        </div>
      </header>
      {operationError && <p role="alert">{operationError}</p>}
      <FilterPanel
        ariaLabel={`${label}筛选器`}
        value={
          draft ??
          (session.kind === 'record'
            ? session.filterDraft
            : session.instance.config.filters)
        }
        appliedValue={
          session.kind === 'record'
            ? session.filterBaseline
            : (session.result?.config.filters ??
              session.baseline.config.filters)
        }
        fields={definition.fields}
        timeZone={definition.timeZone}
        allowedOperators={definition.allowedOperators}
        editors={definition.filterEditors}
        extensions={extensions}
        context={filterContext}
        collapsed={!filtersOpen}
        querying={querying}
        onChange={setDraft}
        onValidityChange={setValid}
        onApply={async () => {
          if (!valid) return;
          if (session.kind === 'record' && position.kind === 'record') {
            position.commands.setFilterDraft(
              draft ?? session.filterDraft,
              true,
            );
            await position.commands.applyFilter();
          } else if (
            session.kind === 'analysis' &&
            position.kind === 'analysis'
          ) {
            position.commands.edit(config => ({
              ...config,
              filters: draft ?? config.filters,
            }));
            position.commands.setFilterValidity(true);
            await position.commands.run();
          }
        }}
      />
      <DataViewContent
        position={position}
        definition={definition}
        extensions={extensions}
        compilers={engine.filterCompilers}
        label={label}
      />
    </div>
  );
}
