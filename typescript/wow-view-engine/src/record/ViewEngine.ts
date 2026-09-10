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

import type { RecordPresentation, RecordCardConfig } from './recordModel.js';
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
} from './recordModel.js';
import type { ViewHost } from './ViewHost.js';
import { freeze } from '../lib/snapshot.js';
import { EngineScope } from './engine/EngineScope.js';
import { SessionStore } from './engine/SessionStore.js';
import { InstanceWork } from './engine/InstanceWork.js';
import { RecordSummaries } from './engine/RecordSummaries.js';
import { RecordQueries } from './engine/RecordQueries.js';
import { RecordEdits } from './engine/RecordEdits.js';
import { ViewLoader } from './engine/ViewLoader.js';
import { ViewReload } from './engine/ViewReload.js';
import { ViewPersistence } from './engine/ViewPersistence.js';
import { ViewManagement } from './engine/ViewManagement.js';

/** Fixed-scope public facade. Internal services own state, reads and durable writes. */
export class ViewEngine {
  private host: ViewHost;
  private unsubscribePermissions?: () => void;
  private capabilities?: { state: ViewEngineState; value: ViewCapabilities };
  private readonly scope = new EngineScope();
  private readonly store: SessionStore;
  readonly filterCompilers: FilterCompilerRegistry;
  private readonly work = new InstanceWork();
  private readonly summaries: RecordSummaries;
  private readonly queries: RecordQueries;
  private readonly edits: RecordEdits;
  private readonly loader: ViewLoader;
  private readonly reload: ViewReload;
  private readonly persistence: ViewPersistence;
  private readonly management: ViewManagement;

  constructor(options: ViewEngineOptions) {
    this.host = options.host;
    // Commands resolve the current host at invocation; UI consumes immutable snapshots.
    const host = new Proxy({} as ViewHost, {
      get: (_target, property) => {
        const current = this.host;
        const value = Reflect.get(current, property, current);
        return typeof value === 'function' ? value.bind(current) : value;
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
    this.store = new SessionStore(this.scope, this.filterCompilers);
    this.summaries = new RecordSummaries(this.store, this.scope, host);
    this.queries = new RecordQueries(
      this.store,
      this.scope,
      host,
      this.summaries,
    );
    this.edits = new RecordEdits(this.store, this.queries, this.summaries);
    this.loader = new ViewLoader(
      this.store,
      this.scope,
      host,
      this.work,
      this.queries,
      this.summaries,
      options,
    );
    this.reload = new ViewReload(
      this.store,
      this.scope,
      host,
      this.work,
      this.queries,
    );
    this.persistence = new ViewPersistence(
      this.store,
      this.scope,
      host,
      this.work,
      this.queries,
    );
    this.management = new ViewManagement(
      this.store,
      this.scope,
      host,
      this.work,
      this.queries,
      this.summaries,
      options.definitionId,
    );
    this.observePermissions();
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

  refreshSummary(id?: string): Promise<void> {
    return this.summaries.refresh(id);
  }

  load(): Promise<void> {
    return this.loader.load();
  }

  selectInstance(id: string): Promise<void> {
    return this.loader.selectInstance(id);
  }

  reloadInstance(id?: string): Promise<void> {
    return this.reload.reloadInstance(id);
  }

  canReloadInstance(id?: string): boolean {
    return this.reload.canReloadInstance(id);
  }

  applyFilter(id?: string): Promise<void> {
    return this.edits.applyFilter(id);
  }

  setFilterDraft(
    draft: DeepReadonly<FilterConfiguration>,
    id?: string,
    valid?: boolean,
  ): void {
    return this.edits.setFilterDraft(draft, id, valid);
  }

  setFilterValidity(valid: boolean, id?: string): void {
    return this.edits.setFilterValidity(valid, id);
  }

  setFilterMode(mode: FilterMode, id?: string): void {
    return this.edits.setFilterMode(mode, id);
  }

  setSort(sort: DeepReadonly<FieldSort[]>, id?: string): Promise<void> {
    return this.edits.setSort(sort, id);
  }

  setLayout(layout: RecordPresentation['layout'], id?: string): void {
    this.edits.setLayout(layout, id);
  }

  setCardConfig(card: DeepReadonly<RecordCardConfig>, id?: string): void {
    this.edits.setCardConfig(card, id);
  }

  setColumns(columns: DeepReadonly<RecordColumn[]>, id?: string): void {
    return this.edits.setColumns(columns, id);
  }

  setPage(index: number, id?: string): Promise<void> {
    return this.edits.setPage(index, id);
  }

  setPageSize(size: number, id?: string): Promise<void> {
    return this.edits.setPageSize(size, id);
  }

  nextPage(id?: string): Promise<void> {
    return this.edits.nextPage(id);
  }

  setTitle(title: string, id?: string): void {
    return this.edits.setTitle(title, id);
  }

  setSelection(keys: RecordKey[], id?: string): void {
    return this.edits.setSelection(keys, id);
  }

  refresh(id?: string, options?: { background?: boolean }): Promise<void> {
    return this.queries.refresh(id, options);
  }

  /** Retry the current query without changing its page or cursor. */
  retryQuery(id?: string): Promise<void> {
    return this.queries.retry(id);
  }

  restore(id?: string): Promise<void> {
    return this.edits.restore(id);
  }

  getPermissions(id?: string): ViewInstancePermissions {
    return this.management.getPermissions(id);
  }

  renameInstance(title: string, id?: string): Promise<void> {
    return this.management.renameInstance(title, id);
  }

  canReorderInstances(): boolean {
    return this.management.canReorderInstances();
  }

  reorderInstances(instanceIds: readonly string[]): Promise<void> {
    return this.management.reorderInstances(instanceIds);
  }

  deleteInstance(id?: string): Promise<void> {
    return this.management.deleteInstance(id);
  }

  save(id?: string): Promise<void> {
    return this.persistence.save(id);
  }

  saveAs(
    options: { title: string; scope: SaveAsScope },
    id?: string,
  ): Promise<void> {
    return this.persistence.saveAs(options, id);
  }

  dispose(): void {
    if (this.scope.disposed) return;
    this.unsubscribePermissions?.();
    this.scope.dispose();
    this.capabilities = undefined;
    this.loader.dispose();
    this.queries.reset();
    this.work.dispose();
    this.store.dispose();
  }
}
