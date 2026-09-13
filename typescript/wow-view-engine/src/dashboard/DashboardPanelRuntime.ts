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

import type { FilterExpression } from '@ahoo-wang/fetcher-wow';
import type { ViewHost } from '../contracts/ViewHost.js';
import type {
  ViewDefinition,
  ViewInstance,
  ViewSource,
} from '../contracts/viewModel.js';
import { ViewServiceError } from '../contracts/viewServiceContract.js';
import { validateViewDefinition } from '../contracts/validation/definitionValidation.js';
import { validateViewInstance } from '../contracts/validation/instanceValidation.js';
import type { RequestRunner } from '../engine/RequestRunner.js';
import type { SessionStore } from '../engine/SessionStore.js';
import type { DataViewPosition, ViewEngine } from '../engine/ViewEngine.js';
import { copy, message, sameJsonState } from '../lib/snapshot.js';
import type { DeepReadonly } from '../lib/types.js';

type ReferenceInstance = Exclude<ViewInstance, { kind: 'dashboard' }>;

export interface DashboardPanelSnapshot {
  readonly panelId: string;
  readonly status: 'loading' | 'ready' | 'blocked' | 'error' | 'suspended';
  readonly loading: boolean;
  readonly blocked: boolean;
  readonly error: string | null;
  readonly instance?: DeepReadonly<ReferenceInstance>;
  readonly definition?: DeepReadonly<ViewDefinition>;
  readonly position?: DataViewPosition;
  readonly scopeVersion: number;
  readonly referenceVersion: number;
  readonly filterId?: string;
}

/** Owns one panel/reference identity. Configuration and scope planning belong to the dashboard. */
export class DashboardPanelRuntime {
  private retained?: {
    instance: DeepReadonly<ReferenceInstance>;
    bytes: number;
  };
  private definition?: { value: DeepReadonly<ViewDefinition>; bytes: number };
  private source?: (controller: AbortController) => Promise<ViewSource>;
  private currentPosition?: DataViewPosition;
  private scope?: FilterExpression;
  private scopeVersion = 0;
  private referenceVersion = 0;
  private generation = 0;
  private status: DashboardPanelSnapshot['status'] = 'suspended';
  private error: string | null = null;
  private filterId?: string;
  private loading?: Promise<void>;
  private disposed = false;

  constructor(
    readonly id: string,
    readonly instanceId: string,
    private readonly engine: ViewEngine,
    private readonly store: SessionStore,
    private readonly host: ViewHost,
    private readonly loads: RequestRunner,
    private readonly events: {
      isActive(): boolean;
      publish(): void;
      reserveMetadata(bytes: number): void;
    },
  ) {}

  get position(): DataViewPosition | undefined {
    return this.currentPosition;
  }
  get metadataBytes(): number {
    return (this.retained?.bytes ?? 0) + (this.definition?.bytes ?? 0);
  }
  get needsPreparation(): boolean {
    return !this.position && !this.source && this.status !== 'blocked';
  }
  getSnapshot(): DashboardPanelSnapshot {
    const session = this.position
      ? this.store.find(this.position.identity.id)
      : undefined;
    const data = session?.kind !== 'dashboard' ? session : undefined;
    return Object.freeze({
      panelId: this.id,
      status: data?.queryStatus === 'error' ? 'error' : this.status,
      loading:
        this.status === 'loading' ||
        data?.queryStatus === 'loading' ||
        data?.queryStatus === 'waiting',
      blocked: this.status === 'blocked',
      error: this.error,
      instance: this.definition ? this.retained?.instance : undefined,
      definition: this.definition?.value,
      position: this.position,
      scopeVersion: this.scopeVersion,
      referenceVersion: this.referenceVersion,
      filterId: this.filterId,
    });
  }
  private current(generation = this.generation): boolean {
    return (
      !this.disposed && this.events.isActive() && generation === this.generation
    );
  }
  private close(): number {
    const generation = ++this.generation;
    const position = this.position;
    this.loading = undefined;
    this.currentPosition = undefined;
    this.source = undefined;
    // Abort and position observers may start a new load synchronously.
    for (const stage of ['instance', 'definition'])
      this.loads.cancel(`panel:${this.id}:${stage}`);
    position?.dispose();
    return generation;
  }
  block(error: unknown, filterId?: string): void {
    if (this.disposed) return;
    this.scope = undefined;
    this.scopeVersion++;
    this.status = 'blocked';
    this.error = message(error);
    this.filterId = filterId;
    if (error instanceof ViewServiceError && error.code === 'FORBIDDEN') {
      this.retained = this.definition = undefined;
      this.events.reserveMetadata(0);
    }
    this.close();
    this.events.publish();
  }
  async load(): Promise<void> {
    if (!this.current() || this.source) return;
    if (this.loading) return this.loading;
    const generation = this.generation;
    this.status = 'loading';
    this.error = null;
    const request = <T>(
      stage: string,
      run: (signal: AbortSignal) => Promise<T> | T,
    ) =>
      this.loads.submit({
        key: `panel:${this.id}:${stage}`,
        policy: 'queue',
        timeoutMs: this.store.limits.loadTimeoutMs,
        run: async signal => {
          if (!this.current(generation)) throw new Error('引用加载已取消');
          return run(signal);
        },
      }).completion;
    const operation = Promise.resolve().then(async () => {
      if (!this.current(generation)) return;
      if (!this.host.instance?.load || !this.host.definition?.load)
        throw new Error('宿主未提供引用加载服务');
      const loaded = await request('instance', signal =>
        this.host.instance!.load!(this.instanceId, signal),
      );
      if (!this.current(generation)) return;
      if (loaded.id !== this.instanceId || loaded.kind === 'dashboard')
        throw new Error('引用身份无效或仪表盘嵌套');
      const definition = await request('definition', signal =>
        this.host.definition!.load!(loaded.definitionId, signal),
      );
      if (!this.current(generation)) return;
      if (definition.id !== loaded.definitionId)
        throw new Error('引用定义身份无效');
      validateViewDefinition(definition);
      validateViewInstance(loaded, definition, this.instanceId);
      const instance = this.retained?.instance ?? loaded;
      validateViewInstance(instance, definition, this.instanceId);
      if (!definition.sourceId) throw new Error('引用缺少查询源');
      const bytes = (value: unknown) =>
        new TextEncoder().encode(JSON.stringify(value)).byteLength;
      const retainedBytes = bytes(instance),
        definitionBytes = bytes(definition);
      this.events.reserveMetadata(retainedBytes + definitionBytes);
      if (!this.retained) this.referenceVersion++;
      this.retained = { instance: copy(instance), bytes: retainedBytes };
      this.definition = { value: copy(definition), bytes: definitionBytes };
      this.source = async controller => {
        const deny = (error: unknown, aborted: boolean) => {
          if (
            !aborted &&
            this.current(generation) &&
            error instanceof ViewServiceError &&
            error.code === 'FORBIDDEN'
          )
            this.block(error);
          throw error;
        };
        let source: ViewSource;
        try {
          source = await this.host.resolveSource(definition.sourceId!);
        } catch (error) {
          return deny(error, controller.signal.aborted);
        }
        return new Proxy(source, {
          get: (target, key) => {
            const value: unknown = Reflect.get(target, key, target);
            if (typeof value !== 'function') return value;
            return (...args: unknown[]) =>
              Promise.resolve()
                .then(() =>
                  (value as (...args: unknown[]) => unknown).apply(
                    target,
                    args,
                  ),
                )
                .catch((error: unknown) =>
                  deny(
                    error,
                    args[2] instanceof AbortController &&
                      args[2].signal.aborted,
                  ),
                );
          },
        });
      };
      this.status = 'ready';
    });
    const loading = operation
      .catch((error: unknown) => {
        if (this.current(generation)) this.block(error);
      })
      .finally(() => {
        if (this.generation === generation) this.loading = undefined;
        if (!this.disposed) this.events.publish();
      });
    this.loading = loading;
    this.events.publish();
    return loading;
  }
  applyScope(expression: FilterExpression, dashboardId: string): boolean {
    if (
      !this.current() ||
      (this.position && sameJsonState(this.scope, expression))
    )
      return false;
    const generation = this.generation;
    try {
      if (!this.currentPosition) {
        if (!this.retained || !this.definition || !this.source) return false;
        const opened = this.engine.openPosition(
          copy(this.retained.instance) as ReferenceInstance,
          copy(this.definition.value),
          { queryPolicy: 'queue', source: this.source },
        );
        if (!this.current(generation)) {
          opened.dispose();
          return false;
        }
        this.currentPosition = opened;
      }
      this.store.registerDashboardPosition(
        this.currentPosition.identity.id,
        dashboardId,
      );
      this.engine.setPositionScope(
        this.currentPosition.identity.id,
        expression,
      );
      if (!this.current(generation)) return false;
      this.scope = expression;
      this.scopeVersion++;
      this.status = 'ready';
      this.error = null;
      this.filterId = undefined;
      return true;
    } catch (error) {
      if (this.current(generation)) this.block(error);
      return false;
    }
  }
  async refresh(): Promise<void> {
    const position = this.position;
    if (!position || !this.current()) return;
    this.error = null;
    this.events.publish();
    if (!this.current() || this.position !== position) return;
    try {
      if (position.kind === 'record') await position.commands.refresh();
      else await position.commands.run();
    } catch (error) {
      // Published query errors belong to the result; unexpected failures reach the caller.
      if (
        this.position === position &&
        this.current() &&
        position.getSnapshot().queryStatus !== 'error'
      )
        throw error;
    }
  }
  async reloadReference(ready: () => Promise<void>): Promise<void> {
    if (this.disposed) return;
    this.retained = this.definition = undefined;
    this.scope = undefined;
    this.status = 'suspended';
    const generation = this.close();
    if (this.disposed || this.generation !== generation) return;
    this.events.reserveMetadata(0);
    this.events.publish();
    // Reset notifications can synchronously replace or reload this owner.
    if (!this.current(generation)) return;
    await this.load();
    if (this.current(generation) && this.source) await ready();
  }
  suspend(): void {
    if (this.disposed) return;
    this.status = 'suspended';
    this.definition = undefined;
    this.close();
  }
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.retained = this.definition = undefined;
    this.close();
  }
}
