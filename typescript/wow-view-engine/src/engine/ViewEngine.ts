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
  DashboardRuntime,
  type DashboardSnapshot,
} from '../dashboard/DashboardRuntime.js';
import type { DashboardTransforms } from '../dashboard/dashboardModel.js';
import { createDashboardSession } from '../dashboard/dashboardSession.js';
import type {
  RecordPresentation,
  RecordCardConfig,
} from '../contracts/viewModel.js';
import type { FieldSort, FilterExpression } from '@ahoo-wang/fetcher-wow';
import type {
  FilterCompilerRegistry,
  FilterConfiguration,
  FilterMode,
} from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  ViewDefinition,
  RecordSession,
  AnalysisSession,
  ViewSource,
  ViewInstance,
  RecordColumn,
  RecordKey,
  SaveAsScope,
  ViewCapabilities,
  ViewEngineOptions,
  ViewEngineState,
  ViewInstancePermissions,
  ViewInstanceConflict,
} from '../contracts/viewModel.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import {
  validateRuntimeLimits,
  beginDiagnostic,
  RuntimeLimitError,
} from '../lib/runtimeLimits.js';
import { RequestRunner } from './RequestRunner.js';
import { AnalysisCommands } from '../analysis/AnalysisCommands.js';
import type { RecordViewConfig } from '../contracts/viewModel.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import { copy, sameJsonState } from '../lib/snapshot.js';
import { withQueryScope } from '../filter/filterScope.js';
import { compileFilterConfiguration } from '../filter/filterConfigurationCompiler.js';
import { validateDashboardExpression } from '../dashboard/dashboardFilters.js';
import type { AnalysisViewConfig } from '../analysis/analysisModel.js';
import { freeze } from '../lib/snapshot.js';
import { EngineScope } from './EngineScope.js';
import { SessionStore } from './SessionStore.js';
import { InstanceWork } from './InstanceWork.js';
import { RecordSummaries } from '../record/engine/RecordSummaries.js';
import { ViewQueries } from './ViewQueries.js';
import { RecordQueries } from '../record/engine/RecordQueries.js';
import { RecordEdits } from '../record/engine/RecordEdits.js';
import { ViewLoader } from './ViewLoader.js';
import { ViewReload } from './ViewReload.js';
import { ViewPersistence } from './ViewPersistence.js';
import { ViewServiceError } from '../contracts/viewServiceContract.js';
import { isSystemSession } from './sessionState.js';
import { ViewManagement } from './ViewManagement.js';
import { definitionPermissionsFor } from './instancePermissions.js';

export interface ViewPositionOptions {
  queryPolicy?: 'reject' | 'queue';
  source?:
    | ViewSource
    | ((controller: AbortController) => ViewSource | Promise<ViewSource>);
}
interface PositionHandle {
  readonly identity: Readonly<{
    id: string;
    instanceId: string;
    definitionId: string;
  }>;
  readonly subscribe: (listener: () => void) => () => void;
  readonly dispose: () => void;
}
export interface RecordViewPosition extends PositionHandle {
  readonly kind: 'record';
  readonly getSnapshot: () => RecordSession;
  readonly commands: ReturnType<ViewEngine['record']>;
}
export interface AnalysisViewPosition extends PositionHandle {
  readonly kind: 'analysis';
  readonly getSnapshot: () => AnalysisSession;
  readonly commands: ReturnType<ViewEngine['analysis']>;
}
export interface DashboardViewPosition extends PositionHandle {
  readonly kind: 'dashboard';
  readonly runtime: DashboardRuntime;
  readonly getSnapshot: () => DashboardSnapshot;
}
export type DataViewPosition = RecordViewPosition | AnalysisViewPosition;
export type ViewPosition = DataViewPosition | DashboardViewPosition;

/** Fixed-scope public facade. Internal services own state, reads and durable writes. */
export class ViewEngine {
  private readonly onDiagnostic: ViewEngineOptions['onDiagnostic'];
  private host: ViewHost;
  private unsubscribePermissions?: () => void;
  private capabilities?: { state: ViewEngineState; value: ViewCapabilities };
  private readonly scope = new EngineScope();
  private readonly store: SessionStore;
  readonly filterCompilers: FilterCompilerRegistry;
  private readonly work = new InstanceWork();
  private readonly summaries: RecordSummaries;
  private readonly queries: RecordQueries;
  private readonly viewQueries: ViewQueries;
  readonly analysisCompilers: NonNullable<
    ViewEngineOptions['analysisCompilers']
  >;
  private readonly analysisCommands: AnalysisCommands;
  private readonly edits: RecordEdits;
  private readonly loader: ViewLoader;
  private readonly reload: ViewReload;
  private readonly persistence: ViewPersistence;
  private readonly management: ViewManagement;
  private readonly dashboards = new Map<string, DashboardRuntime>();
  private readonly dashboardTransforms: DashboardTransforms;
  private readonly runtimeHost: ViewHost;
  private selectedDashboard: string | null = null;
  private synchronizingDashboards = false;

  constructor(options: ViewEngineOptions) {
    this.host = options.host;
    this.onDiagnostic = options.onDiagnostic;
    const limits = validateRuntimeLimits(options.limits);
    const budget = new RequestRunner({
      maxConcurrent: limits.maxConcurrentQueries,
      maxQueued: 48,
      maxTimeoutMs: limits.queryTimeoutMs,
    });
    // Commands resolve the current host at invocation; UI consumes immutable snapshots.
    const host = new Proxy({} as ViewHost, {
      get: (_target, property) => {
        const current = this.host;
        // Reflect.get 返回 any；收窄为 unknown 后按运行时形态分别处理，消除不安全调用。
        const value = Reflect.get(current, property, current) as unknown;
        return typeof value === 'function'
          ? (value as (...args: unknown[]) => unknown).bind(current)
          : value;
      },
    });
    this.runtimeHost = host;
    this.dashboardTransforms = Object.freeze({
      ...options.dashboardTransforms,
    });
    this.filterCompilers = Object.freeze(
      Object.fromEntries(
        Object.entries(options.filterCompilers ?? {}).map(
          ([name, compiler]) => [
            name,
            Object.freeze({
              compile: compiler.compile,
              ...(compiler.clear ? { clear: compiler.clear } : {}),
            }),
          ],
        ),
      ),
    );
    this.analysisCompilers = Object.freeze(
      Object.fromEntries(
        Object.entries(options.analysisCompilers ?? {}).map(
          ([name, compiler]) => {
            if (!compiler || typeof compiler.compile !== 'function')
              throw new Error(`Analysis compiler ${name} must provide compile`);
            if (
              !Array.isArray(compiler.roles) ||
              compiler.roles.length === 0 ||
              new Set(compiler.roles).size !== compiler.roles.length ||
              [...compiler.roles].some(
                role => role !== 'dimension' && role !== 'metric',
              )
            )
              throw new Error(
                `Analysis compiler ${name} roles must be nonempty, unique dimension/metric values`,
              );
            return [
              name,
              Object.freeze({
                roles: Object.freeze([...compiler.roles]),
                compile: compiler.compile,
              }),
            ];
          },
        ),
      ),
    );
    this.store = new SessionStore(
      this.scope,
      this.filterCompilers,
      this.analysisCompilers,
      limits,
    );
    this.summaries = new RecordSummaries(
      this.store,
      this.scope,
      host,
      limits,
      budget,
      options.onDiagnostic,
    );
    this.analysisCommands = new AnalysisCommands(
      this.store,
      this.scope,
      host,
      this.work,
      this.analysisCompilers,
      limits,
      options.onDiagnostic,
      budget,
    );
    this.queries = new RecordQueries(
      this.store,
      this.scope,
      host,
      this.summaries,
      limits,
      budget,
      options.onDiagnostic,
    );
    this.viewQueries = new ViewQueries(
      this.store,
      this.scope,
      this.queries,
      this.analysisCommands,
    );
    this.edits = new RecordEdits(this.store, this.queries, this.summaries);
    this.loader = new ViewLoader(
      this.store,
      this.scope,
      host,
      this.work,
      this.viewQueries,
      this.summaries,
      options,
    );
    this.reload = new ViewReload(
      this.store,
      this.scope,
      host,
      this.work,
      this.viewQueries,
    );
    this.persistence = new ViewPersistence(
      this.store,
      this.scope,
      host,
      this.work,
      this.viewQueries,
    );
    this.management = new ViewManagement(
      this.store,
      this.scope,
      host,
      this.work,
      this.viewQueries,
      this.summaries,
      options.definitionId,
    );
    this.observePermissions();
    this.store.subscribe(() => this.syncDashboards());
  }

  dashboard(id: string): DashboardRuntime {
    this.scope.assertReady();
    let runtime = this.dashboards.get(id);
    if (!runtime || runtime.isDisposed) {
      runtime = new DashboardRuntime(
        this,
        id,
        this.store,
        this.runtimeHost,
        this.dashboardTransforms,
        updater => this.scope.update(updater),
      );
      this.dashboards.set(id, runtime);
    }
    return runtime;
  }

  private syncDashboards(): void {
    if (this.synchronizingDashboards || this.scope.disposed) return;
    this.synchronizingDashboards = true;
    try {
      const state = this.store.getSnapshot();
      if (state.status === 'loading') {
        for (const runtime of this.dashboards.values()) runtime.dispose();
        this.dashboards.clear();
        this.selectedDashboard = null;
        return;
      }
      for (const [id, session] of Object.entries(state.sessions)) {
        if (
          session.kind !== 'dashboard' ||
          !session.createdFromDraft ||
          this.store.find(session.createdFromDraft)
        )
          continue;
        const previousId = session.createdFromDraft;
        const runtime = this.dashboards.get(previousId);
        if (!runtime || this.dashboards.has(id)) continue;
        this.dashboards.delete(previousId);
        this.dashboards.set(id, runtime);
        if (this.selectedDashboard === previousId) this.selectedDashboard = id;
        runtime.adoptSavedDraft(id);
      }
      for (const [id, runtime] of this.dashboards) {
        if (!this.store.find(id)) {
          runtime.dispose();
          this.dashboards.delete(id);
        }
      }
      const id = state.selectedInstanceId;
      const selected =
        id && state.sessions[id]?.kind === 'dashboard' ? id : null;
      if (
        selected !== this.selectedDashboard ||
        (selected && this.dashboards.get(selected)?.isDisposed)
      ) {
        if (this.selectedDashboard)
          this.dashboards.get(this.selectedDashboard)?.suspend();
        this.selectedDashboard = selected;
        if (selected)
          void this.dashboard(selected)
            .resume()
            .catch(error => console.error('仪表盘加载失败', error));
      }
    } finally {
      this.synchronizingDashboards = false;
    }
  }

  private async observe<T>(
    operation: string,
    write: boolean,
    run: () => Promise<T>,
  ): Promise<T> {
    const diagnostic = beginDiagnostic(this.onDiagnostic, 'shared', operation);
    try {
      const expectedSelection = this.scope.selection + 1;
      const pending = run(),
        version = this.scope.version;
      diagnostic('started');
      const result = await pending;
      if (!this.scope.current(version))
        diagnostic(
          write ? 'failed' : this.scope.disposed ? 'cancelled' : 'superseded',
          write ? 'UNKNOWN_OUTCOME' : undefined,
        );
      else if (
        !write &&
        operation === 'select' &&
        this.scope.selection !== expectedSelection
      )
        diagnostic('superseded');
      else diagnostic('succeeded');
      return result;
    } catch (error) {
      diagnostic(
        'failed',
        error instanceof RuntimeLimitError || error instanceof ViewServiceError
          ? error.code
          : 'OPERATION_FAILED',
      );
      throw error;
    }
  }

  getSnapshot = (): ViewEngineState => this.store.getSnapshot();
  /** Observer errors are reported to console.error without interrupting commands or other observers. */
  subscribe = (listener: () => void): (() => void) =>
    this.store.subscribe(listener);

  /** Replace callbacks/policy within the same user/tenant/access scope, preserving sessions. */
  updateHost(host: ViewHost): void {
    this.scope.assertActive();
    if (this.host === host) return;
    this.unsubscribePermissions?.();
    this.host = host;
    this.observePermissions();
    this.store.publish({});
  }

  private observePermissions(): void {
    const host = this.host;
    this.unsubscribePermissions = host.permission?.subscribe?.(() => {
      if (this.host === host && !this.scope.disposed) this.store.publish({});
    });
  }

  /** Cached immutable projection for render-time reads; commands still recheck live policy. */
  getCapabilitiesSnapshot = (): ViewCapabilities => {
    const state = this.store.getSnapshot();
    if (this.capabilities?.state === state) return this.capabilities.value;
    const grants = definitionPermissionsFor(this.host);
    const value: ViewCapabilities = freeze({
      createPersonal:
        !!state.definition?.dashboard &&
        !!this.host.instance?.create &&
        grants?.createPersonal === true,
      createShared:
        !!state.definition?.dashboard &&
        !!this.host.instance?.create &&
        grants?.createShared === true,
      reorder: this.canReorderInstances(),
      setDefault: this.canSetDefaultInstance(),
      instances: Object.fromEntries(
        [
          ...new Set([
            ...state.instanceIds,
            ...Object.keys(state.pendingCreates),
            ...Object.entries(state.sessions)
              .filter(
                ([, session]) =>
                  session.kind === 'dashboard' && !session.persisted,
              )
              .map(([id]) => id),
          ]),
        ].map(id => [
          id,
          {
            permissions: this.getPermissions(id),
            reload: this.canReloadInstance(id),
            retryDelete: this.management.canRetryDeleteInstance(id),
          },
        ]),
      ),
    });
    this.capabilities = { state, value };
    return value;
  };

  analysis(id: string) {
    const editorEpoch = this.store.analysisSession(id).editorEpoch;
    const version = this.scope.version,
      generation = this.store.generation(id);
    const assert = (editing = true) => {
      if (
        !this.scope.current(version) ||
        this.store.generation(id) !== generation
      )
        throw new Error('实例命令已失效');
      const session = this.store.analysisSession(id);
      if (editing && session.editorEpoch !== editorEpoch)
        throw new Error('编辑会话已重置');
    };
    return {
      edit: (
        updater: (
          config: DeepReadonly<AnalysisViewConfig>,
        ) => DeepReadonly<AnalysisViewConfig>,
      ) => {
        assert();
        this.analysisCommands.edit(id, updater);
      },
      start: () => {
        assert();
        return this.analysisCommands.start(id);
      },
      run: async () => {
        assert(false);
        await this.analysisCommands.run(id);
      },
      refresh: async () => {
        assert(false);
        await this.analysisCommands.refresh(id);
      },
      setFilterValidity: (valid: boolean) => {
        if (this.store.find(id)?.editorEpoch !== editorEpoch) return;
        assert();
        if (typeof valid !== 'boolean')
          throw new Error('筛选有效性必须是布尔值');
        this.store.patch(id, { kind: 'analysis', filterValid: valid });
      },
      setSort: async (sort: DeepReadonly<AnalysisViewConfig['sort']>) => {
        assert();
        await this.analysisCommands.setSort(id, sort);
      },
      clearSort: () => {
        assert();
        this.analysisCommands.edit(id, config => ({ ...config, sort: [] }));
      },
      restore: () => {
        assert();
        this.analysisCommands.restore(id);
      },
    };
  }

  record(id: string) {
    const editorEpoch = this.store.find(id)?.editorEpoch;
    const version = this.scope.version,
      generation = this.store.generation(id);
    const assert = (editing = true) => {
      this.scope.assertReady();
      if (
        !this.scope.current(version) ||
        this.store.generation(id) !== generation
      )
        throw new Error('实例命令已失效');
      const session = this.store.recordSession(id);
      if (editing && session.editorEpoch !== editorEpoch)
        throw new Error('编辑会话已重置');
    };
    return {
      edit: (
        updater: (
          config: DeepReadonly<RecordViewConfig>,
        ) => DeepReadonly<RecordViewConfig>,
      ) => {
        assert();
        const session = this.store.recordSession(id);
        const config = this.scope.update(() =>
          updater(session.instance.config),
        );
        validateFilterJson(config);
        this.store.patch(id, {
          kind: 'record',
          instance: { ...session.instance, config: copy(config) },
          filterDraft: copy(config.filters),
        });
      },
      refreshSummary: async () => {
        assert(false);
        await this.summaries.refresh(id);
      },
      applyFilter: async () => {
        assert();
        await this.edits.applyFilter(id);
      },
      setFilterDraft: (
        draft: DeepReadonly<FilterConfiguration>,
        valid?: boolean,
      ) => {
        assert();
        this.edits.setFilterDraft(draft, id, valid);
      },
      setFilterValidity: (valid: boolean) => {
        if (this.store.find(id)?.editorEpoch !== editorEpoch) return;
        assert();
        this.edits.setFilterValidity(valid, id);
      },
      setFilterMode: (mode: FilterMode) => {
        assert();
        this.edits.setFilterMode(mode, id);
      },
      setSort: async (sort: DeepReadonly<FieldSort[]>) => {
        assert();
        await this.edits.setSort(sort, id);
      },
      setLayout: (layout: RecordPresentation['layout']) => {
        assert();
        this.edits.setLayout(layout, id);
      },
      setCardConfig: (card: DeepReadonly<RecordCardConfig>) => {
        assert();
        this.edits.setCardConfig(card, id);
      },
      setColumns: (columns: DeepReadonly<RecordColumn[]>) => {
        assert();
        this.edits.setColumns(columns, id);
      },
      setPage: async (index: number) => {
        assert();
        await this.edits.setPage(index, id);
      },
      setPageSize: async (size: number) => {
        assert();
        await this.edits.setPageSize(size, id);
      },
      nextPage: async () => {
        assert();
        await this.edits.nextPage(id);
      },
      setSelection: (keys: RecordKey[]) => {
        assert(false);
        this.edits.setSelection(keys, id);
      },
      refresh: async (options?: { background?: boolean }) => {
        assert(false);
        await this.queries.refresh(id, options);
      },
      retryQuery: async () => {
        assert(false);
        await this.queries.retry(id);
      },
      restore: async () => {
        assert();
        await this.restore(id);
      },
    };
  }

  /** Creates independent browsing state without selecting an instance or starting requests. */
  openPosition<T extends ViewInstance>(
    instance: T,
    definition: ViewDefinition,
    options?: ViewPositionOptions,
  ): Extract<ViewPosition, { kind: T['kind'] }>;
  openPosition(
    instance: ViewInstance,
    definition: ViewDefinition,
    options: ViewPositionOptions = {},
  ): ViewPosition {
    const id = this.store.openPosition(instance, definition, options);
    const identity = Object.freeze({
      id,
      instanceId: instance.id,
      definitionId: definition.id,
    });
    const version = this.scope.version;
    let closed = false;
    const dispose = () => {
      if (closed || !this.scope.current(version)) return;
      closed = true;
      // Invalidate bound commands before cancellation can notify observers.
      this.store.closePosition(id);
      this.viewQueries.forget(id);
    };
    if (instance.kind === 'dashboard') {
      try {
        const runtime = this.dashboard(id);
        return {
          kind: 'dashboard',
          identity,
          runtime,
          getSnapshot: runtime.getSnapshot,
          subscribe: runtime.subscribe,
          dispose,
        };
      } catch (error) {
        dispose();
        throw error;
      }
    }
    const shared = { identity, subscribe: this.subscribe, dispose };
    return instance.kind === 'record'
      ? {
          ...shared,
          kind: 'record' as const,
          getSnapshot: () => this.store.recordSession(id),
          commands: this.record(id),
        }
      : {
          ...shared,
          kind: 'analysis' as const,
          getSnapshot: () => this.store.analysisSession(id),
          commands: this.analysis(id),
        };
  }
  /** Changes only a runtime position's scope; execution remains an explicit command. */
  setPositionScope(id: string, expression: FilterExpression): void {
    if (!this.store.isPosition(id))
      throw new Error('只允许设置运行位置的作用域');
    const session = this.store.session(id);
    const definition = this.store.definition(id);
    const scopeFilter = validateDashboardExpression(expression, definition);
    if (session.kind === 'dashboard') throw new Error('仪表盘不能嵌套');
    if (sameJsonState(session.scopeFilter, scopeFilter)) return;
    const own = compileFilterConfiguration(
      session.kind === 'record'
        ? session.filterBaseline
        : session.instance.config.filters,
      definition.fields,
      definition.allowedOperators,
      this.filterCompilers,
      definition.timeZone,
    );
    if (own.errors.length || !own.expression)
      throw new Error('引用筛选配置无效');
    validateDashboardExpression(
      withQueryScope(own.expression, scopeFilter),
      definition,
    );
    this.viewQueries.cancel(id);
    this.summaries.invalidate(id);
    if (session.kind === 'record') {
      const appliedFilter = withQueryScope(own.expression, scopeFilter);
      this.store.patch(id, {
        kind: 'record',
        scopeFilter,
        appliedFilter,
        page: 1,
        cursor: null,
        nextCursor: null,
        selectedRowKeys: [],
        queryAttempt: null,
      });
    } else {
      this.store.patch(id, {
        kind: 'analysis',
        scopeFilter,
        pendingQuery: null,
        queryAttempt: null,
      });
    }
  }

  load(): Promise<void> {
    return this.observe('load', false, () => this.loader.load());
  }

  selectInstance(id: string): Promise<void> {
    return this.observe('select', false, () => this.loader.selectInstance(id));
  }

  reloadInstance(id?: string): Promise<void> {
    return this.observe('reload', false, () => this.reload.reloadInstance(id));
  }

  useRemoteInstance(review: ViewInstanceConflict, id?: string): Promise<void> {
    return this.reload.useRemoteInstance(review, id);
  }

  overwriteInstance(review: ViewInstanceConflict, id?: string): Promise<void> {
    return this.observe('overwrite', true, () => {
      this.assertDashboardSavable(id);
      return this.persistence.overwriteInstance(review, id);
    });
  }

  canReloadInstance(id?: string): boolean {
    return this.reload.canReloadInstance(id);
  }

  /** Creates local editor state only; save() performs the first authoritative create. */
  createDashboard(options: { title: string; scope: SaveAsScope }): string {
    const definition = this.store.definition();
    if (!definition.dashboard) throw new Error('定义未声明仪表盘能力');
    if (typeof options.title !== 'string' || !options.title.trim())
      throw new Error('实例名称不能为空');
    if (!(
      options.scope?.type === 'personal' ||
      (options.scope?.type === 'public' && options.scope.source === 'shared')
    ))
      throw new Error('新建仅支持个人或共享范围');
    if (!this.host.instance?.create) throw new Error('宿主未提供创建服务');
    const grants = this.host.permission?.getDefinition?.();
    if (
      (options.scope.type === 'personal'
        ? grants?.createPersonal
        : grants?.createShared) !== true
    )
      throw new Error('宿主未允许此创建操作');
    const id = `draft:${crypto.randomUUID()}`;
    const instance = {
      id,
      definitionId: definition.id,
      title: options.title,
      kind: 'dashboard' as const,
      scope: copy(options.scope),
      revision: 'local-draft',
      config: { schemaVersion: 1 as const, panels: [], filters: [] },
    };
    const session = {
      ...createDashboardSession(instance),
      persisted: false,
      dirty: true,
    };
    const previous = this.store.getSnapshot().selectedInstanceId;
    this.scope.advanceSelection();
    if (previous) this.viewQueries.cancel(previous);
    this.store.publish({
      sessions: { ...this.store.getSnapshot().sessions, [id]: session },
      selectedInstanceId: id,
    });
    return id;
  }

  setTitle(title: string, id?: string): void {
    if (id && this.store.isPosition(id))
      throw new Error('运行位置不能通过实例管理接口改名');
    const session = this.store.session(id);
    if (isSystemSession(session)) throw new Error('系统视图不能编辑名称');
    if (typeof title !== 'string' || !title.trim())
      throw new Error('实例名称不能为空');
    this.store.patch(
      session.instance.id,
      session.kind === 'record'
        ? { kind: 'record', instance: { ...session.instance, title } }
        : session.kind === 'analysis'
          ? { kind: 'analysis', instance: { ...session.instance, title } }
          : { kind: 'dashboard', instance: { ...session.instance, title } },
    );
  }

  async restore(id?: string): Promise<void> {
    const session = this.store.session(id);
    this.work.assertRestorable(session);
    if (session.conflict)
      return Promise.reject(new Error('视图存在冲突，请明确选择使用最新版本'));
    if (session.kind === 'dashboard') {
      this.store.patch(session.positionId, {
        kind: 'dashboard',
        instance: session.baseline,
        writeError: null,
        editorValidity: {},
        editorEpoch: session.editorEpoch + 1,
      });
      return;
    }
    if (session.kind === 'analysis') {
      this.analysisCommands.restore(session.positionId);
      return Promise.resolve();
    }
    return this.edits.restore(id);
  }

  getPermissions(id?: string): ViewInstancePermissions {
    return this.management.getPermissions(id);
  }

  renameInstance(title: string, id?: string): Promise<void> {
    return this.observe('rename', true, () =>
      this.management.renameInstance(title, id),
    );
  }

  canSetDefaultInstance(): boolean {
    return this.management.canSetDefaultInstance();
  }

  setDefaultInstance(instanceId: string | null): Promise<void> {
    return this.observe('default', true, () =>
      this.management.setDefaultInstance(instanceId),
    );
  }

  canReorderInstances(): boolean {
    return this.management.canReorderInstances();
  }

  reorderInstances(instanceIds: readonly string[]): Promise<void> {
    return this.observe('order', true, () =>
      this.management.reorderInstances(instanceIds),
    );
  }

  deleteInstance(id?: string): Promise<void> {
    return this.observe('delete', true, () =>
      this.management.deleteInstance(id),
    );
  }

  save(id?: string): Promise<void> {
    return this.observe('save', true, () => {
      this.assertDashboardSavable(id);
      return this.persistence.save(id);
    });
  }

  saveAs(
    options: { title: string; scope: SaveAsScope },
    id?: string,
  ): Promise<string | undefined> {
    return this.observe('create', true, () => {
      this.assertDashboardSavable(id);
      return this.persistence.saveAs(options, id);
    });
  }

  private assertDashboardSavable(id?: string): void {
    const selected = id ?? this.store.getSnapshot().selectedInstanceId;
    const session = selected ? this.store.find(selected) : undefined;
    if (selected && this.store.isPosition(selected))
      throw new Error('运行位置不能通过实例管理接口写入，请编辑原视图');
    if (session?.kind === 'dashboard')
      this.dashboard(session.positionId).assertSavable();
  }

  dispose(): void {
    if (this.scope.disposed) return;
    this.unsubscribePermissions?.();
    for (const runtime of this.dashboards.values()) runtime.dispose();
    this.dashboards.clear();
    this.scope.dispose();
    this.capabilities = undefined;
    this.loader.dispose();
    this.viewQueries.reset();
    this.work.dispose();
    this.store.dispose();
  }
}
