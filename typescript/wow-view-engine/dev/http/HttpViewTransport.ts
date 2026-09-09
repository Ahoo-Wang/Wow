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

import { encodeViewResourceId, VIEW_SERVICE_STATUS } from './protocol.js';
import { copy } from '../../src/lib/snapshot.js';
import { HttpViewPermissionService } from './HttpViewPermissionService.js';

import {
  ViewServiceError,
  type ViewServiceErrorCode,
} from '@ahoo-wang/fetcher-view-engine';

export interface HttpViewTransportOptions {
  baseUrl: string;
  definitionId: string;
  headers?: () => HeadersInit;
  fetch?: typeof fetch;
  timeoutMs?: number;
}
/** REST transport only; runtime components and record clients remain application-owned. */
export class HttpViewTransport {
  readonly permission: HttpViewPermissionService;

  private readonly options: HttpViewTransportOptions;
  private readonly root: string;

  private requestSequence = 0;
  private permissionFence = 0;

  constructor(options: HttpViewTransportOptions) {
    const root = new URL(options.baseUrl);
    if (
      !['http:', 'https:'].includes(root.protocol) ||
      root.username ||
      root.password ||
      root.search ||
      root.hash
    )
      throw new ViewServiceError(
        'INVALID_ARGUMENT',
        'baseUrl 必须是无凭据的 HTTP 服务地址',
      );
    if (
      typeof options.definitionId !== 'string' ||
      !options.definitionId.trim() ||
      (options.timeoutMs !== undefined &&
        (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0))
    )
      throw new ViewServiceError(
        'INVALID_ARGUMENT',
        'definitionId 或 timeoutMs 无效',
      );
    this.root = `${root.href.replace(/\/$/, '')}/definitions/${encodeViewResourceId(options.definitionId)}`;
    this.options = { ...options };
    this.permission = new HttpViewPermissionService(this);
  }

  assertDefinition(id: string): void {
    encodeViewResourceId(id);
    if (id !== this.options.definitionId)
      throw new ViewServiceError('NOT_FOUND', '视图定义不存在');
  }
  revision(revision?: string): HeadersInit {
    if (!revision)
      throw new ViewServiceError(
        'PRECONDITION_REQUIRED',
        '写入需要当前 revision，请重新加载',
      );
    return { 'If-Match': JSON.stringify(revision) };
  }

  async request<T>(
    path: string,
    method: string,
    body?: unknown,
    signal?: AbortSignal,
    extraHeaders?: HeadersInit,
  ): Promise<T> {
    signal?.throwIfAborted();
    const sequence = ++this.requestSequence;
    const writing = method !== 'GET';
    const timeout = AbortSignal.timeout(this.options.timeoutMs ?? 10000);
    const requestSignal = signal ? AbortSignal.any([signal, timeout]) : timeout;
    const headers = new Headers(this.options.headers?.());
    headers.set('Accept', 'application/json');
    new Headers(extraHeaders).forEach((value, key) => headers.set(key, value));
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    const payload = body === undefined ? undefined : JSON.stringify(copy(body));
    let response: Response;
    let envelope:
      | undefined
      | {
          data?: T;
          permissions?: unknown;
          error?: { code?: string; message?: string };
        };
    try {
      response = await (this.options.fetch ?? globalThis.fetch)(
        this.root + path,
        { method, headers, body: payload, signal: requestSignal },
      );
      if (response.status !== 401) envelope = await response.json();
    } catch {
      if (writing)
        throw new ViewServiceError(
          'UNKNOWN_OUTCOME',
          '写入结果未知，请使用同一请求重试或重新加载核对',
        );
      if (signal?.aborted) throw signal.reason;
      throw new ViewServiceError('UNAVAILABLE', '视图服务请求失败或超时');
    }
    if (response.status === 401) {
      this.permissionFence = Math.max(this.permissionFence, sequence);
      this.permission.clear();
      await response.body?.cancel().catch(() => {});
      throw new ViewServiceError(
        'UNAUTHENTICATED',
        '登录状态已失效，请重新登录',
      );
    }
    if (!envelope || typeof envelope !== 'object')
      throw new ViewServiceError(
        writing ? 'UNKNOWN_OUTCOME' : 'UNAVAILABLE',
        '视图服务响应无效',
      );
    if (envelope.permissions && sequence > this.permissionFence)
      this.permission.acceptSnapshot(envelope.permissions);
    if (!response.ok) {
      const code = envelope.error?.code;
      if (
        code &&
        Object.prototype.hasOwnProperty.call(VIEW_SERVICE_STATUS, code) &&
        VIEW_SERVICE_STATUS[code as ViewServiceErrorCode] === response.status
      )
        throw new ViewServiceError(
          code as ViewServiceErrorCode,
          envelope.error?.message ?? code,
        );
      throw new ViewServiceError(
        writing ? 'UNKNOWN_OUTCOME' : 'UNAVAILABLE',
        `视图服务返回 ${response.status}`,
      );
    }
    if (
      !envelope.permissions ||
      !Object.prototype.hasOwnProperty.call(envelope, 'data')
    )
      throw new ViewServiceError(
        writing ? 'UNKNOWN_OUTCOME' : 'UNAVAILABLE',
        '视图服务响应缺少 data 或 permissions',
      );
    return envelope.data as T;
  }
}
