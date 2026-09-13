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

import { copy, sameJsonState } from '../../src/lib/snapshot.js';

import type {
  ViewInstance,
  ViewInstancePermissions,
} from '@ahoo-wang/fetcher-view-engine';
import type { ViewPermissionService } from '@ahoo-wang/fetcher-view-engine';
import {
  ViewServiceError,
  type ViewPermissionSnapshot,
} from '@ahoo-wang/fetcher-view-engine';
import type { HttpViewTransport } from './HttpViewTransport.js';
/** Permission resource and its shared, versioned UI projection. Access via transport.permission. */
export class HttpViewPermissionService implements ViewPermissionService {
  constructor(private readonly transport: HttpViewTransport) {}
  readonly getInstance = (instance: ViewInstance): ViewInstancePermissions => {
    return Object.prototype.hasOwnProperty.call(
      this.permissionSnapshot.instances,
      instance.id,
    )
      ? this.permissionSnapshot.instances[instance.id]
      : {
          save: false,
          rename: false,
          delete: false,
          saveAsPersonal: false,
          saveAsShared: false,
        };
  };
  readonly getDefinition = () => {
    return {
      reorder: this.permissionSnapshot.reorder,
      createPersonal:
        this.transport.supportedFormats.dashboard === 1 &&
        this.permissionSnapshot.createPersonal === true,
      createShared:
        this.transport.supportedFormats.dashboard === 1 &&
        this.permissionSnapshot.createShared === true,
    };
  };
  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  readonly refresh = async (signal?: AbortSignal): Promise<void> => {
    await this.transport.request('/permissions', 'GET', undefined, signal);
  };
  readonly load = async (
    id: string,
    signal?: AbortSignal,
  ): Promise<ViewPermissionSnapshot> => {
    this.transport.assertDefinition(id);
    const result = await this.transport.request(
      '/permissions',
      'GET',
      undefined,
      signal,
    );
    // The transport validates and accepts the envelope's current authority snapshot.
    // Never return a raw payload that escaped those checks or predates a revocation.
    if (
      this.permissionSnapshot.revision < 0 ||
      !sameJsonState(result, this.permissionSnapshot)
    )
      throw new ViewServiceError(
        'UNAVAILABLE',
        '权限响应已过期或与当前授权不一致，请重试',
      );
    return copy({ ...this.permissionSnapshot, ...this.getDefinition() });
  };
  /** Clear cached grants after an HTTP session rejection; keep the authority version. */
  clear(): void {
    this.permissionSnapshot = copy({
      revision: this.permissionSnapshot.revision,
      instances: {},
      reorder: false,
    });
    this.listeners.forEach(listener => listener());
  }
  private permissionSnapshot: ViewPermissionSnapshot = {
    revision: -1,
    instances: {},
    reorder: false,
  };
  private readonly listeners = new Set<() => void>();
  acceptSnapshot(value: unknown): void {
    const next = value as ViewPermissionSnapshot;
    if (
      !next ||
      !Number.isSafeInteger(next.revision) ||
      next.revision < 0 ||
      typeof next.reorder !== 'boolean' ||
      (next.createPersonal !== undefined &&
        typeof next.createPersonal !== 'boolean') ||
      (next.createShared !== undefined &&
        typeof next.createShared !== 'boolean') ||
      !next.instances ||
      typeof next.instances !== 'object' ||
      Array.isArray(next.instances)
    )
      throw new ViewServiceError('UNAVAILABLE', '服务权限响应无效');
    for (const permission of Object.values(next.instances))
      if (
        !permission ||
        !['save', 'rename', 'delete', 'saveAsPersonal', 'saveAsShared'].every(
          key =>
            typeof permission[key as keyof ViewInstancePermissions] ===
            'boolean',
        )
      )
        throw new ViewServiceError('UNAVAILABLE', '服务实例权限无效');
    if (
      next.revision < this.permissionSnapshot.revision ||
      sameJsonState(next, this.permissionSnapshot)
    )
      return;
    this.permissionSnapshot = copy(next);
    this.listeners.forEach(listener => listener());
  }
}
