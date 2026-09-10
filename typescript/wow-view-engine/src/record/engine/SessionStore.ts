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

import type { FilterCompilerRegistry } from '../../filter/filterModel.js';
import type { DeepReadonly } from '../../lib/types.js';
import type {
  RecordSession,
  ViewEngineState,
  ViewInstance,
} from '../recordModel.js';
import { validateViewInstance } from '../recordValidation.js';
import type { EngineScope } from './EngineScope.js';
import { copy, freeze } from '../../lib/snapshot.js';
import { deriveSession } from './sessionState.js';

/** Sole owner of published immutable session state and subscriptions. */
export class SessionStore {
  private state: ViewEngineState = freeze({
    status: 'idle',
    error: null,
    definition: null,
    instanceIds: [],
    selectedInstanceId: null,
    defaultInstanceId: null,
    sessions: Object.create(null),
    pendingCreates: Object.create(null),
  });
  private readonly listeners = new Set<() => void>();
  constructor(
    private readonly scope: EngineScope,
    readonly filterCompilers: FilterCompilerRegistry,
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
    this.state = freeze({ ...this.state, ...patch });
    this.listeners.forEach(listener => {
      try {
        listener();
      } catch (error) {
        console.error('视图状态订阅回调失败', error);
      }
    });
  }

  patch(id: string, patch: Partial<RecordSession>): void {
    const session = this.find(id) ?? this.findPendingCreate(id);
    const target = this.find(id) ? 'sessions' : 'pendingCreates';
    const definition = this.state.definition;
    if (!session || !definition || this.scope.disposed) return;
    this.publish({
      [target]: {
        ...this.state[target],
        [id]: deriveSession(
          { ...session, ...patch },
          definition,
          this.filterCompilers,
          session,
        ),
      },
    });
  }

  find(id: string): RecordSession | undefined {
    return Object.prototype.hasOwnProperty.call(this.state.sessions, id)
      ? this.state.sessions[id]
      : undefined;
  }

  findPendingCreate(id: string): RecordSession | undefined {
    return Object.prototype.hasOwnProperty.call(this.state.pendingCreates, id)
      ? this.state.pendingCreates[id]
      : undefined;
  }

  definition(): NonNullable<ViewEngineState['definition']> {
    this.scope.assertReady();
    if (!this.state.definition) throw new Error('视图定义尚未加载');
    return this.state.definition;
  }

  session(id = this.state.selectedInstanceId): RecordSession {
    this.scope.assertReady();
    if (id === null || !this.find(id))
      throw new Error('请先选择有效的视图实例');
    return this.find(id)!;
  }

  sessionForReload(id = this.state.selectedInstanceId): RecordSession {
    this.scope.assertReady();
    const session =
      id === null ? undefined : (this.find(id) ?? this.findPendingCreate(id));
    if (!session) throw new Error('请先选择有效的视图实例');
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
    instance: DeepReadonly<ViewInstance>,
    patch: Partial<RecordSession> = {},
  ): void {
    validateViewInstance(instance, this.definition(), session.instance.id);
    this.patch(session.instance.id, { ...patch, instance: copy(instance) });
  }

  dispose(): void {
    this.listeners.clear();
  }
}
