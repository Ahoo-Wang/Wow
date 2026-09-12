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

import type {
  RecordPresentation,
  RecordCardConfig,
} from '../contracts/viewModel.js';
import type { FieldSort } from '@ahoo-wang/fetcher-wow';
import type {
  FilterCompilerRegistry,
  FilterConfiguration,
  FilterMode,
} from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
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
  QueryBudget,
  beginDiagnostic,
  RuntimeLimitError,
} from '../lib/runtimeLimits.js';
import { AnalysisCommands } from '../analysis/AnalysisCommands.js';
import type { RecordViewConfig } from '../contracts/viewModel.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import { copy } from '../lib/snapshot.js';
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

  constructor(options: ViewEngineOptions) {
    this.host = options.host;
    this.onDiagnostic = options.onDiagnostic;
    const limits = validateRuntimeLimits(options.limits);
    const budget = new QueryBudget(limits.maxConcurrentQueries);
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
    const value: ViewCapabilities = freeze({
      reorder: this.canReorderInstances(),
      setDefault: this.canSetDefaultInstance(),
      instances: Object.fromEntries(
        [
          ...new Set([
            ...state.instanceIds,
            ...Object.keys(state.pendingCreates),
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
        this.store.patch(id, { filterValid: valid });
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
    return this.observe('overwrite', true, () =>
      this.persistence.overwriteInstance(review, id),
    );
  }

  canReloadInstance(id?: string): boolean {
    return this.reload.canReloadInstance(id);
  }

  setTitle(title: string, id?: string): void {
    const session = this.store.session(id);
    if (isSystemSession(session)) throw new Error('系统视图不能编辑名称');
    if (typeof title !== 'string' || !title.trim())
      throw new Error('实例名称不能为空');
    this.store.patch(
      session.instance.id,
      session.kind === 'record'
        ? { kind: 'record', instance: { ...session.instance, title } }
        : { kind: 'analysis', instance: { ...session.instance, title } },
    );
  }

  async restore(id?: string): Promise<void> {
    this.work.assertWritable(this.store.session(id));
    if (this.store.session(id).conflict)
      return Promise.reject(new Error('视图存在冲突，请明确选择使用最新版本'));
    if (this.store.session(id).kind === 'analysis') {
      this.analysisCommands.restore(this.store.session(id).instance.id);
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
    return this.observe('save', true, () => this.persistence.save(id));
  }

  saveAs(
    options: { title: string; scope: SaveAsScope },
    id?: string,
  ): Promise<string | undefined> {
    return this.observe('create', true, () =>
      this.persistence.saveAs(options, id),
    );
  }

  dispose(): void {
    if (this.scope.disposed) return;
    this.unsubscribePermissions?.();
    this.scope.dispose();
    this.capabilities = undefined;
    this.loader.dispose();
    this.viewQueries.reset();
    this.work.dispose();
    this.store.dispose();
  }
}
