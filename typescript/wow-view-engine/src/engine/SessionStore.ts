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
import type { ViewDefinition, ViewInstance } from '../contracts/viewModel.js';
import { createSession, deriveSession } from './sessionState.js';

type CommonSessionFields = Omit<
  ViewSession,
  'kind' | 'instance' | 'baseline' | 'result' | 'queryAttempt'
>;
type CommonSessionPatch = Partial<CommonSessionFields> & {
  [
    K in Exclude<
      keyof RecordSession | keyof AnalysisSession,
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
type SessionPatch =
  CommonSessionPatch | RecordSessionPatch | AnalysisSessionPatch;

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
  private readonly positions = new Map<string, ViewDefinition>();
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
            return [
              id,
              session === previous
                ? session
                : deriveSession(
                    session,
                    this.positions.get(id) ?? definition,
                    this.filterCompilers,
                    previous,
                    this.analysisCompilers,
                    this.limits.maxConfigBytes,
                  ),
            ];
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
        session.result &&
        (session.result !== this.find(id)?.result ||
          (id === next.selectedInstanceId &&
            id !== this.state.selectedInstanceId))
      )
        this.resultAccess.set(id, ++this.accessVersion);
      if (!session.result) this.resultAccess.delete(id);
    }
    const retained = Object.keys(sessions).filter(
      id => !this.positions.has(id) && sessions[id].result !== null,
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
          : clearRecordResult(session);
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
    const session = this.find(id) ?? this.findPendingCreate(id);
    const target = this.find(id) ? 'sessions' : 'pendingCreates';
    const definition = this.positions.get(id) ?? this.state.definition;
    if (!session || !definition || this.scope.disposed) return;
    let next: ViewSession;
    if (patch.kind === undefined) {
      next =
        session.kind === 'record'
          ? { ...session, ...patch, kind: 'record' }
          : { ...session, ...patch, kind: 'analysis' };
    } else if (session.kind === 'record' && patch.kind === 'record') {
      next = { ...session, ...patch };
    } else if (session.kind === 'analysis' && patch.kind === 'analysis') {
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

  openPosition(instance: ViewInstance, definition: ViewDefinition): string {
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
    };
    this.positions.set(id, localDefinition);
    this.publish({ sessions: { ...this.state.sessions, [id]: session } });
    return id;
  }

  isPosition(id: string): boolean {
    return this.positions.has(id);
  }
  closePosition(id: string): void {
    if (!this.positions.delete(id)) return;
    const sessions = { ...this.state.sessions };
    delete sessions[id];
    this.generations.delete(id);
    this.publish({ sessions });
  }
  dispose(): void {
    this.generations.clear();
    this.resultAccess.clear();
    this.positions.clear();
    this.listeners.clear();
  }
}
