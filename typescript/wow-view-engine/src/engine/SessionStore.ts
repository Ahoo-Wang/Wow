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

import { validateRuntimeLimits, type RuntimeLimits } from './runtimeLimits.js';
import type { AnalysisCompilerRegistry } from '../analysis/analysisModel.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type {
  RecordSession,
  AnalysisSession,
  ViewSession,
  ViewEngineState,
} from '../contracts/viewModel.js';
import { validateViewInstance } from '../record/recordValidation.js';
import type { EngineScope } from './EngineScope.js';
import { EMPTY_RECORD_SUMMARY } from '../record/recordSummary.js';
import { copy, freeze } from '../lib/snapshot.js';
import { deriveSession } from './sessionState.js';

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
  private readonly resultAccess = new Map<string, number>();
  private accessVersion = 0;
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
                    definition,
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
        if (!this.find(id)) this.generations.set(id, this.generation(id) + 1);
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
      id => sessions[id].result !== null,
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
          ? { ...session, result: null }
          : {
              ...session,
              result: null,
              rows: [],
              total: null,
              nextCursor: null,
              selectedRowKeys: [],
              pageSummary: EMPTY_RECORD_SUMMARY,
              allSummary: EMPTY_RECORD_SUMMARY,
            };
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
    const definition = this.state.definition;
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

  definition(): NonNullable<ViewEngineState['definition']> {
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
    validateViewInstance(instance, this.definition(), session.instance.id);
    this.patch(session.instance.id, {
      ...patch,
      kind: 'record',
      instance: copy(instance),
    });
  }

  dispose(): void {
    this.listeners.clear();
  }
}
