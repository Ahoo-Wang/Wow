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

import type { ViewCreateInput } from '../contracts/viewModel.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import {
  createViewInput,
  validateLocalViewState,
  type StoredInstance,
  type ServiceState,
} from './localViewState.js';
import { copy, message, sameJsonState } from '../lib/snapshot.js';

import {
  validateViewDefinition,
  validateViewInstance,
} from './recordValidation.js';
import { readInstanceList } from './validation/instanceValidation.js';
import {
  ViewServiceError,
  type ViewDeleteResult,
  encodeViewResourceId,
  type ViewCreateContext,
  type ViewPermissionSnapshot,
} from './viewServiceContract.js';
import type {
  ViewDefinition,
  ViewInstance,
  ViewInstanceList,
  ViewInstancePermissions,
} from '../contracts/viewModel.js';

export interface StatefulViewHostOptions {
  /** Trusted service/tenant namespace, independent of the current user. */
  serviceKey: string;
  /** Trusted current user identity; never take this from a write payload. */
  scopeKey: string;
  definition: ViewDefinition;
  instances: ViewInstanceList;
  resolveSource: ViewHost['resolveSource'];
  instancePermissions?: NonNullable<ViewHost['permission']>['getInstance'];
  canReorder?: () => boolean;
  permissionsRevision?: () => number;
}
export interface ViewStateChange<T> {
  result: T;
  value?: string | null;
}
export type ViewStateTransaction = <T>(
  key: string,
  change: (raw: string | null | undefined) => ViewStateChange<T>,
  signal?: AbortSignal,
) => Promise<T>;

/** Shared view-domain behavior for the actual memory and IndexedDB stores. Not a public storage adapter. */
export abstract class StatefulViewHost implements ViewHost {
  readonly definition = {
    load: async (id: string, signal?: AbortSignal): Promise<ViewDefinition> => {
      signal?.throwIfAborted();
      this.assertDefinition(id);
      return structuredClone(this.storedDefinition);
    },
  };
  readonly instance = {
    list: async (
      id: string,
      signal?: AbortSignal,
    ): Promise<ViewInstanceList> => {
      this.assertDefinition(id);
      return this.transaction(
        state => {
          const visible = this.ordered(state, this.options.scopeKey);
          return {
            instances: visible.map(item => this.dto(item)),
            defaultInstanceId: this.resolveDefault(
              state.users[this.options.scopeKey].defaultInstanceId,
              visible,
            ),
          };
        },
        false,
        signal,
      );
    },
    load: async (id: string, signal?: AbortSignal): Promise<ViewInstance> => {
      return this.transaction(
        state => this.dto(this.find(state, id)),
        false,
        signal,
      );
    },
    save: async (instance: ViewInstance): Promise<ViewInstance> => {
      return this.transaction(state => {
        this.validate(instance);
        const previous = this.writable(
          state,
          instance.id,
          instance.revision,
          'save',
        );
        if (previous.kind !== instance.kind)
          throw new ViewServiceError(
            'INVALID_ARGUMENT',
            '保存不能改变视图类型',
          );
        if (!sameJsonState(previous.scope, instance.scope))
          throw new ViewServiceError(
            'INVALID_ARGUMENT',
            '保存不能改变视图可见范围，请另存为',
          );
        const saved = {
          ...copy(this.dto(instance)),
          ownerKey: previous.ownerKey,
          revision: crypto.randomUUID(),
        };
        state.instances[state.instances.indexOf(previous)] = saved;
        return this.dto(saved);
      }, true);
    },
    create: async (
      input: ViewCreateInput,
      context: ViewCreateContext,
    ): Promise<ViewInstance> => {
      if (typeof context?.requestId !== 'string' || !context.requestId.trim())
        throw new ViewServiceError(
          'INVALID_ARGUMENT',
          '创建必须提供 requestId',
        );
      if (!input || typeof input !== 'object' || Array.isArray(input))
        throw new ViewServiceError(
          'INVALID_ARGUMENT',
          '创建正文必须为视图对象',
        );
      return this.transaction(
        state => {
          const body = createViewInput(input);
          const candidate = {
            ...copy(body),
            id: crypto.randomUUID(),
            revision: crypto.randomUUID(),
          };
          this.validate(candidate);
          if (
            candidate.scope.type === 'public' &&
            candidate.scope.source === 'system'
          )
            throw new ViewServiceError(
              'FORBIDDEN',
              '系统视图只能通过初始配置提供',
            );
          const allowed = this.permission!.getInstance(candidate);
          if (
            !(candidate.scope.type === 'personal'
              ? allowed.saveAsPersonal
              : allowed.saveAsShared)
          )
            throw new ViewServiceError('FORBIDDEN', '没有创建视图权限');
          const receiptKey = JSON.stringify([
            this.options.scopeKey,
            context.requestId,
          ]);
          if (Object.prototype.hasOwnProperty.call(state.creates, receiptKey)) {
            const receipt = state.creates[receiptKey];
            if (!sameJsonState(receipt.input, body))
              throw new ViewServiceError(
                'CONFLICT',
                'requestId 已用于不同的创建内容',
              );
            return receipt.result;
          }
          state.instances.push({
            ...candidate,
            ownerKey:
              candidate.scope.type === 'personal'
                ? this.options.scopeKey
                : null,
          });
          state.creates = {
            ...state.creates,
            [receiptKey]: { input: copy(body), result: candidate },
          };
          return candidate;
        },
        true,
        context.signal,
      );
    },
    rename: async (
      id: string,
      title: string,
      revision: string,
    ): Promise<ViewInstance> => {
      return this.transaction(state => {
        const previous = this.writable(state, id, revision, 'rename');
        const next = {
          ...previous,
          title: typeof title === 'string' ? title.trim() : title,
          revision: crypto.randomUUID(),
        };
        this.validate(next);
        state.instances[state.instances.indexOf(previous)] = next;
        return this.dto(next);
      }, true);
    },
    delete: async (id: string, revision: string): Promise<ViewDeleteResult> => {
      encodeViewResourceId(id);
      return this.transaction(state => {
        // Absence is scoped to this caller, including inaccessible personal views.
        if (this.visible(state).some(item => item.id === id)) {
          const previous = this.writable(state, id, revision, 'delete');
          state.instances.splice(state.instances.indexOf(previous), 1);
          for (const [scopeKey, user] of Object.entries(state.users)) {
            if (user.defaultInstanceId === id)
              user.defaultInstanceId = this.resolveDefault(
                id,
                this.ordered(state, scopeKey),
              );
          }
        }
        const visible = this.ordered(state, this.options.scopeKey);
        const defaultId = this.resolveDefault(
          state.users[this.options.scopeKey].defaultInstanceId,
          visible,
        );
        return {
          defaultInstance:
            defaultId === null
              ? null
              : this.dto(visible.find(item => item.id === defaultId)!),
        };
      }, true);
    },
  };
  readonly permission = {
    getInstance: (
      instance: ViewInstance,
    ): Required<ViewInstancePermissions> => {
      const system =
        instance.scope.type === 'public' && instance.scope.source === 'system';
      const policy = this.options.instancePermissions?.(
        structuredClone(instance),
      ) ?? {
        save: true,
        rename: true,
        delete: true,
        saveAsPersonal: true,
        saveAsShared: true,
      };
      return {
        save: !system && policy.save === true,
        rename: !system && policy.rename === true,
        delete: !system && policy.delete === true,
        saveAsPersonal: policy.saveAsPersonal === true,
        saveAsShared: policy.saveAsShared === true,
      };
    },
    getDefinition: () => {
      return { reorder: this.options.canReorder?.() ?? true };
    },
    subscribe: (listener: () => void): (() => void) => {
      this.permissionListeners.add(listener);
      return () => {
        this.permissionListeners.delete(listener);
      };
    },
    load: async (
      id: string,
      signal?: AbortSignal,
    ): Promise<ViewPermissionSnapshot> => {
      this.assertDefinition(id);
      return this.transaction(
        state => ({
          revision: this.options.permissionsRevision?.() ?? 0,
          instances: Object.fromEntries(
            this.visible(state).map(item => [
              item.id,
              this.permission!.getInstance(this.dto(item)),
            ]),
          ),
          reorder: this.permission!.getDefinition().reorder,
        }),
        false,
        signal,
      );
    },
    refresh: async (): Promise<void> => {
      await this.permission!.load(this.storedDefinition.id);
      this.permissionListeners.forEach(listener => listener());
    },
  };
  readonly preference = {
    saveDefault: async (
      id: string,
      instanceId: string | null,
    ): Promise<void> => {
      this.assertDefinition(id);
      await this.transaction(state => {
        if (instanceId !== null) this.find(state, instanceId);
        state.users[this.options.scopeKey].defaultInstanceId = instanceId;
      }, true);
    },
    saveOrder: async (id: string, instanceIds: string[]): Promise<void> => {
      this.assertDefinition(id);
      await this.transaction(state => {
        if (!this.permission!.getDefinition().reorder)
          throw new ViewServiceError('FORBIDDEN', '没有视图排序权限');
        const visible = this.visible(state);
        if (
          !Array.isArray(instanceIds) ||
          instanceIds.some(id => typeof id !== 'string') ||
          new Set(instanceIds).size !== instanceIds.length
        )
          throw new ViewServiceError(
            'INVALID_ARGUMENT',
            '排序必须是不重复的实例 ID 数组',
          );
        if (
          instanceIds.length !== visible.length ||
          instanceIds.some(id => !visible.some(item => item.id === id))
        )
          throw new ViewServiceError(
            'CONFLICT',
            '排序必须包含所有当前可见视图，请重新加载',
          );
        state.users[this.options.scopeKey].order = [...instanceIds];
      }, true);
    },
  };
  readonly storageKey: string;
  private readonly storedDefinition: ViewDefinition;
  private readonly initial: ViewInstanceList;
  private readonly options: StatefulViewHostOptions;
  private readonly permissionListeners = new Set<() => void>();

  protected constructor(
    options: StatefulViewHostOptions,
    private readonly transact: ViewStateTransaction,
  ) {
    if (
      ![options.scopeKey, options.serviceKey].every(
        value => typeof value === 'string' && value.trim(),
      )
    )
      throw new ViewServiceError(
        'INVALID_ARGUMENT',
        'serviceKey 和 scopeKey 不能为空',
      );
    this.storedDefinition = copy(options.definition);
    validateViewDefinition(this.storedDefinition);
    this.initial = copy(options.instances);
    try {
      readInstanceList(this.initial, this.storedDefinition);
    } catch (error) {
      throw new ViewServiceError('INVALID_ARGUMENT', message(error));
    }
    this.options = { ...options };
    this.storageKey = `fve:views:${JSON.stringify([options.serviceKey, this.storedDefinition.id])}`;
  }

  resolveSource(id: string) {
    if (id !== this.storedDefinition.sourceId)
      throw new ViewServiceError('NOT_FOUND', '视图数据源不存在');
    return this.options.resolveSource(id);
  }

  /** Administrative fixture reset for this service/definition, across its users. Not a REST operation. */
  async reset(): Promise<void> {
    await this.transact(this.storageKey, () => ({
      value: null,
      result: undefined,
    }));
  }

  private assertDefinition(id: string): void {
    encodeViewResourceId(id);
    if (id !== this.storedDefinition.id)
      throw new ViewServiceError('NOT_FOUND', '视图定义不存在');
  }
  private dto({
    id,
    definitionId,
    kind,
    title,
    scope,
    revision,
    config,
  }: ViewInstance): ViewInstance {
    return {
      id,
      definitionId,
      kind,
      title,
      scope,
      revision,
      config,
    } as ViewInstance;
  }
  private visible(state: ServiceState, scopeKey = this.options.scopeKey) {
    return state.instances.filter(
      item => item.ownerKey === null || item.ownerKey === scopeKey,
    );
  }
  private ordered(state: ServiceState, scopeKey: string) {
    const visible = this.visible(state, scopeKey);
    const { order } = state.users[scopeKey];
    const ids = [
      ...order.filter(id => visible.some(item => item.id === id)),
      ...visible.map(item => item.id).filter(id => !order.includes(id)),
    ];
    return ids.map(id => visible.find(item => item.id === id)!);
  }
  private resolveDefault(id: string | null, visible: StoredInstance[]) {
    return id === null || visible.some(item => item.id === id)
      ? id
      : (visible[0]?.id ?? null);
  }
  private find(state: ServiceState, id: string): StoredInstance {
    encodeViewResourceId(id);
    const instance = this.visible(state).find(item => item.id === id);
    if (!instance) throw new ViewServiceError('NOT_FOUND', `视图 ${id} 不存在`);
    return instance;
  }
  private writable(
    state: ServiceState,
    id: string,
    revision: string | undefined,
    action: 'save' | 'rename' | 'delete',
  ): StoredInstance {
    const instance = this.find(state, id);
    if (!this.permission!.getInstance(this.dto(instance))[action])
      throw new ViewServiceError(
        'FORBIDDEN',
        '系统视图或未授权视图不能修改或删除',
      );
    if (!revision)
      throw new ViewServiceError(
        'PRECONDITION_REQUIRED',
        '写入需要当前 revision，请重新加载',
      );
    if (instance.revision !== revision)
      throw new ViewServiceError(
        'REVISION_CONFLICT',
        '视图已被更新，请重新加载',
      );
    return instance;
  }
  private validate(value: unknown): void {
    try {
      validateViewInstance(value, this.storedDefinition);
    } catch (error) {
      throw new ViewServiceError('INVALID_ARGUMENT', message(error));
    }
  }
  private transaction<T>(
    operation: (state: ServiceState) => T,
    write: boolean,
    signal?: AbortSignal,
  ): Promise<T> {
    return this.transact(
      this.storageKey,
      raw => {
        signal?.throwIfAborted();
        let state: ServiceState;
        try {
          state =
            raw == null
              ? {
                  instances: this.initial.instances
                    .filter(item => item.scope.type === 'public')
                    .map(item => ({
                      ...item,
                      ownerKey: null,
                      revision: crypto.randomUUID(),
                    })),
                  users: {},
                  creates: {},
                }
              : JSON.parse(raw);
          validateLocalViewState(state, this.storedDefinition);
        } catch (error) {
          throw new ViewServiceError('CORRUPT_STATE', message(error));
        }
        if (
          !Object.prototype.hasOwnProperty.call(
            state.users,
            this.options.scopeKey,
          )
        ) {
          state.instances.push(
            ...this.initial.instances
              .filter(item => item.scope.type === 'personal')
              .map(item => ({
                ...structuredClone(item),
                ownerKey: this.options.scopeKey,
                revision: crypto.randomUUID(),
              })),
          );
          state.users = {
            ...state.users,
            [this.options.scopeKey]: {
              order: this.initial.instances.map(item => item.id),
              defaultInstanceId: this.initial.defaultInstanceId,
            },
          };
          state.users[this.options.scopeKey].defaultInstanceId =
            this.resolveDefault(
              this.initial.defaultInstanceId,
              this.ordered(state, this.options.scopeKey),
            );
          write = true;
        }
        // Visibility must never contain a public/private ID collision.
        if (
          new Set(this.visible(state).map(item => item.id)).size !==
          this.visible(state).length
        )
          throw new ViewServiceError('CORRUPT_STATE', '可见实例 ID 重复');
        const result = operation(state);
        return {
          result: structuredClone(result),
          value: write || raw == null ? JSON.stringify(copy(state)) : undefined,
        };
      },
      signal,
    );
  }
}
