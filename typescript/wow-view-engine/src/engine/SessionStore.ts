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
  validateRuntimeLimits,
  RuntimeLimitError,
  type RuntimeLimits,
} from '../lib/runtimeLimits.js';
import type { AnalysisCompilerRegistry } from '../analysis/analysisModel.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type {
  RecordSession,
  AnalysisSession,
  ViewSession,
  ViewEngineState,
} from '../contracts/viewModel.js';
import { validateViewDefinition } from '../contracts/validation/definitionValidation.js';
import { validateViewInstance } from '../contracts/validation/instanceValidation.js';
import type { EngineScope } from './EngineScope.js';
import { clearAnalysisResult } from '../analysis/analysisSession.js';
import { clearRecordResult } from '../record/engine/recordSession.js';
import { copy, freeze } from '../lib/snapshot.js';
import type {
  ViewDefinition,
  ViewInstance,
  ViewSource,
} from '../contracts/viewModel.js';
import { createSession, deriveSession } from './sessionState.js';

import { compileFilterConfiguration } from '../filter/filterConfigurationCompiler.js';
import type { DashboardSession } from '../dashboard/dashboardModel.js';

type CommonSessionFields = Omit<
  ViewSession,
  'kind' | 'instance' | 'baseline' | 'result' | 'queryAttempt'
>;
type CommonSessionPatch = Partial<CommonSessionFields> & {
  [
    K in Exclude<
      keyof RecordSession | keyof AnalysisSession | keyof DashboardSession,
      keyof CommonSessionFields
    >
  ]?: never;
};
type RecordSessionPatch = { kind: 'record' } & Partial<
  Omit<RecordSession, 'kind'>
> & {
    [K in Exclude<keyof AnalysisSession, keyof RecordSession>]?: never;
  };
type AnalysisSessionPatch = { kind: 'analysis' } & Partial<
  Omit<AnalysisSession, 'kind'>
> & {
    [K in Exclude<keyof RecordSession, keyof AnalysisSession>]?: never;
  };
type DashboardSessionPatch = { kind: 'dashboard' } & Partial<
  Omit<DashboardSession, 'kind'>
>;
type SessionPatch =
  | CommonSessionPatch
  | RecordSessionPatch
  | AnalysisSessionPatch
  | DashboardSessionPatch;

/** Sole owner of published immutable session state and subscriptions. */
export class SessionStore {
  private state: ViewEngineState = freeze({
    status: 'idle',
    version: 0,
    error: null,
    definition: null,
    instanceIds: [],
    selectedInstanceId: null,
    openingInstanceId: null,
    defaultInstanceId: null,
    sessions: Object.create(null),
    pendingCreates: Object.create(null),
  });
  private readonly dashboardPositions = new Map<string, string>();
  registerDashboardPosition(positionId: string, dashboardId: string): void {
    if (
      !this.positions.has(positionId) ||
      this.find(dashboardId)?.kind !== 'dashboard'
    )
      throw new Error('仪表盘运行位置无效');
    this.dashboardPositions.set(positionId, dashboardId);
  }
  private admitDashboardResult(
    id: string,
    result: RecordSession['result'] | AnalysisSession['result'],
  ): void {
    const dashboard = this.dashboardPositions.get(id);
    if (!dashboard || !result) return;
    let rows = 0,
      bytes = 0;
    for (const [position, owner] of this.dashboardPositions) {
      if (owner !== dashboard) continue;
      const session = this.find(position);
      const retained =
        position === id
          ? result
          : session?.kind !== 'dashboard'
            ? session?.result
            : undefined;
      if (!retained) continue;
      rows += retained.rows.length;
      bytes += new TextEncoder().encode(JSON.stringify(retained)).byteLength;
    }
    if (
      rows > this.limits.maxDashboardResultRows ||
      bytes > this.limits.maxDashboardResultBytes
    )
      throw new RuntimeLimitError(
        'RESOURCE_LIMIT',
        '仪表盘结果超过保留预算，请减少面板或结果规模',
      );
  }
  private readonly dashboardMetadata = new Map<string, number>();
  reserveDashboardMetadata(id: string, bytes: number): void {
    let total = bytes;
    for (const [other, retained] of this.dashboardMetadata)
      if (other !== id) total += retained;
    if (total > this.limits.maxDashboardMetadataBytes)
      throw new RuntimeLimitError(
        'RESOURCE_LIMIT',
        '仪表盘恢复元数据超出预算，请调整宿主接入规模',
      );
    this.dashboardMetadata.set(id, bytes);
  }
  transferDashboardOwnership(from: string, to: string): void {
    for (const [position, dashboard] of this.dashboardPositions)
      if (dashboard === from) this.dashboardPositions.set(position, to);
    const bytes = this.dashboardMetadata.get(from);
    this.dashboardMetadata.delete(from);
    if (bytes !== undefined) this.dashboardMetadata.set(to, bytes);
  }
  releaseDashboardMetadata(id: string): void {
    this.dashboardMetadata.delete(id);
  }
  private readonly positions = new Map<string, ViewDefinition>();
  private readonly positionSources = new Map<
    string,
    | ViewSource
    | ((controller: AbortController) => ViewSource | Promise<ViewSource>)
  >();
  source(
    id: string,
  ):
    | ViewSource
    | ((controller: AbortController) => ViewSource | Promise<ViewSource>)
    | undefined {
    return this.positionSources.get(id);
  }
  private readonly resultAccess = new Map<string, number>();
  private accessVersion = 0;
  private nextGeneration = 0;
  private readonly generations = new Map<string, number>();
  generation(id: string): number {
    return this.generations.get(id) ?? 0;
  }
  private readonly listeners = new Set<() => void>();
  constructor(
    private readonly scope: EngineScope,
    readonly filterCompilers: FilterCompilerRegistry,
    readonly analysisCompilers: AnalysisCompilerRegistry = {},
    readonly limits: Readonly<RuntimeLimits> = validateRuntimeLimits(),
  ) {}

  getSnapshot = (): ViewEngineState => this.state;
  subscribe = (listener: () => void): (() => void) => {
    if (!this.scope.disposed) this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  publish(patch: Partial<ViewEngineState>): void {
    if (this.scope.disposed) return;
    if (patch.status === 'loading') {
      for (const id of this.positions.keys()) this.generations.delete(id);
      this.positions.clear();
      this.dashboardMetadata.clear();
      this.dashboardPositions.clear();
      this.positionSources.clear();
    }
    const definition =
      patch.definition === undefined ? this.state.definition : patch.definition;
    if (definition) {
      for (const target of ['sessions', 'pendingCreates'] as const) {
        const incoming = patch[target];
        if (!incoming) continue;
        const finalized = Object.fromEntries(
          Object.entries(incoming).map(([id, session]) => {
            const previous =
              definition === this.state.definition &&
              Object.prototype.hasOwnProperty.call(this.state[target], id)
                ? this.state[target][id]
                : undefined;
            const localDefinition = this.positions.get(id) ?? definition;
            let derived =
              session === previous
                ? session
                : deriveSession(
                    session,
                    localDefinition,
                    this.filterCompilers,
                    previous,
                    this.analysisCompilers,
                    this.limits.maxConfigBytes,
                    {
                      maxPanels: this.limits.maxDashboardPanels,
                      maxFilters: this.limits.maxDashboardFilters,
                    },
                  );
            if (this.positions.has(id) && derived.dirty)
              derived = { ...derived, dirty: false };
            if (derived === previous) return [id, derived];
            if (derived.kind === 'dashboard') {
              const errors = derived.instance.config.filters.flatMap(
                item =>
                  compileFilterConfiguration(
                    item.filters,
                    localDefinition.fields,
                    localDefinition.allowedOperators,
                    this.filterCompilers,
                    localDefinition.timeZone,
                  ).errors,
              );
              if (errors.length)
                return [
                  id,
                  {
                    ...derived,
                    validation: [...derived.validation, ...errors],
                  },
                ];
            }
            return [id, derived];
          }),
        );
        patch = { ...patch, [target]: finalized };
      }
    }
    if (patch.sessions)
      for (const id of Object.keys(patch.sessions))
        if (!this.find(id)) this.generations.set(id, ++this.nextGeneration);
    let next = { ...this.state, ...patch };
    const sessions = { ...next.sessions };
    for (const id of this.resultAccess.keys())
      if (!Object.prototype.hasOwnProperty.call(sessions, id))
        this.resultAccess.delete(id);
    for (const [id, session] of Object.entries(sessions)) {
      if (
        session.kind !== 'dashboard' &&
        session.result &&
        (session.result !==
          (this.find(id)?.kind !== 'dashboard'
            ? (this.find(id) as RecordSession | AnalysisSession | undefined)
                ?.result
            : null) ||
          (id === next.selectedInstanceId &&
            id !== this.state.selectedInstanceId))
      )
        this.resultAccess.set(id, ++this.accessVersion);
      if (session.kind === 'dashboard' || !session.result)
        this.resultAccess.delete(id);
    }
    const retained = Object.keys(sessions).filter(
      id =>
        !this.positions.has(id) &&
        sessions[id].kind !== 'dashboard' &&
        sessions[id].result !== null,
    );
    const candidates = retained
      .filter(id => id !== next.selectedInstanceId)
      .sort(
        (a, b) =>
          (this.resultAccess.get(a) ?? 0) - (this.resultAccess.get(b) ?? 0),
      );
    for (
      let count = retained.length;
      count > this.limits.maxRetainedResults && candidates.length;
      count--
    ) {
      const id = candidates.shift()!,
        session = sessions[id];
      this.resultAccess.delete(id);
      sessions[id] =
        session.kind === 'analysis'
          ? clearAnalysisResult(session)
          : session.kind === 'record'
            ? clearRecordResult(session)
            : session;
    }
    if (retained.length > this.limits.maxRetainedResults)
      next = { ...next, sessions };
    this.state = freeze({ ...next, version: this.state.version + 1 });
    this.listeners.forEach(listener => {
      try {
        listener();
      } catch (error) {
        console.error('视图状态订阅回调失败', error);
      }
    });
  }

  patch(id: string, patch: SessionPatch): void {
    if (patch.kind === 'record' || patch.kind === 'analysis')
      this.admitDashboardResult(id, patch.result ?? null);
    const session = this.find(id) ?? this.findPendingCreate(id);
    const target = this.find(id) ? 'sessions' : 'pendingCreates';
    const definition = this.positions.get(id) ?? this.state.definition;
    if (!session || !definition || this.scope.disposed) return;
    let next: ViewSession;
    if (patch.kind === undefined) {
      next =
        session.kind === 'record'
          ? { ...session, ...patch, kind: 'record' }
          : session.kind === 'analysis'
            ? { ...session, ...patch, kind: 'analysis' }
            : { ...session, ...patch, kind: 'dashboard' };
    } else if (session.kind === 'record' && patch.kind === 'record') {
      next = { ...session, ...patch };
    } else if (session.kind === 'analysis' && patch.kind === 'analysis') {
      next = { ...session, ...patch };
    } else if (session.kind === 'dashboard' && patch.kind === 'dashboard') {
      next = { ...session, ...patch };
    } else {
      throw new Error('实例类型不能改变');
    }
    this.publish({
      [target]: {
        ...this.state[target],
        [id]: next,
      },
    });
  }

  find(id: string): ViewSession | undefined {
    return Object.prototype.hasOwnProperty.call(this.state.sessions, id)
      ? this.state.sessions[id]
      : undefined;
  }

  findPendingCreate(id: string): ViewSession | undefined {
    return Object.prototype.hasOwnProperty.call(this.state.pendingCreates, id)
      ? this.state.pendingCreates[id]
      : undefined;
  }

  definition(id?: string): NonNullable<ViewEngineState['definition']> {
    const positionDefinition = id ? this.positions.get(id) : undefined;
    if (positionDefinition) {
      this.scope.assertReady();
      return positionDefinition;
    }
    this.scope.assertReady();
    if (!this.state.definition) throw new Error('视图定义尚未加载');
    return this.state.definition;
  }

  session(id = this.state.selectedInstanceId): ViewSession {
    this.scope.assertReady();
    if (id === null || !this.find(id))
      throw new Error('请先选择有效的视图实例');
    return this.find(id)!;
  }

  sessionForReload(id = this.state.selectedInstanceId): ViewSession {
    this.scope.assertReady();
    if (id && this.isPosition(id))
      throw new Error('运行位置不能通过实例管理接口重新加载');
    const session =
      id === null ? undefined : (this.find(id) ?? this.findPendingCreate(id));
    if (!session) throw new Error('请先选择有效的视图实例');
    return session;
  }

  recordSession(id = this.state.selectedInstanceId): RecordSession {
    const session = this.session(id);
    if (session.kind !== 'record') throw new Error('实例不是记录视图');
    return session;
  }
  analysisSession(id: string): AnalysisSession {
    const session = this.session(id);
    if (session.kind !== 'analysis') throw new Error('实例不是分析视图');
    return session;
  }

  clearPendingCreate(id: string): void {
    if (!Object.prototype.hasOwnProperty.call(this.state.pendingCreates, id))
      return;
    const pendingCreates = { ...this.state.pendingCreates };
    delete pendingCreates[id];
    this.publish({ pendingCreates });
  }

  updateInstance(
    session: RecordSession,
    instance: RecordSession['instance'],
    patch: Omit<RecordSessionPatch, 'kind' | 'instance'> = {},
  ): void {
    validateViewInstance(
      instance,
      this.definition(session.positionId),
      session.instance.id,
    );
    this.patch(session.positionId, {
      ...patch,
      kind: 'record',
      instance: copy(instance),
    });
  }

  openPosition(
    instance: ViewInstance,
    definition: ViewDefinition,
    options: {
      queryPolicy?: 'reject' | 'queue';
      source?:
        | ViewSource
        | ((controller: AbortController) => ViewSource | Promise<ViewSource>);
    } = {},
  ): string {
    this.scope.assertReady();
    validateViewDefinition(definition);
    validateViewInstance(instance, definition);
    const id = `position:${crypto.randomUUID()}`;
    const localDefinition = copy(definition);
    const session = {
      ...createSession(
        copy(instance),
        localDefinition,
        this.filterCompilers,
        this.analysisCompilers,
      ),
      positionId: id,
      queryPolicy: options.queryPolicy,
    };
    this.positions.set(id, localDefinition);
    if (options.source) this.positionSources.set(id, options.source);
    this.publish({ sessions: { ...this.state.sessions, [id]: session } });
    return id;
  }

  isPosition(id: string): boolean {
    return this.positions.has(id);
  }
  closePosition(id: string): void {
    if (!this.positions.delete(id)) return;
    this.positionSources.delete(id);
    this.dashboardPositions.delete(id);
    const sessions = { ...this.state.sessions };
    delete sessions[id];
    this.generations.delete(id);
    this.publish({ sessions });
  }
  dispose(): void {
    this.dashboardMetadata.clear();
    this.dashboardPositions.clear();
    this.positionSources.clear();
    this.generations.clear();
    this.resultAccess.clear();
    this.positions.clear();
    this.listeners.clear();
  }
}
