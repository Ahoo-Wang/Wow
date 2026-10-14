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

import { type Fetcher, fetcher as defaultFetcher } from '@ahoo-wang/fetcher';
import { toWowProblemError } from './problem.js';

/**
 * The request headers of a Wow V10 service's public port (decisions D95,
 * D96, D98, D99).
 */
export const WowV10Headers = {
  /** The caller's id of the request: a retry with the same one is deduplicated. */
  REQUEST_ID: 'Wow-Request-Id',
  /** The wait plan (decision D99). */
  WAIT: 'Wow-Wait',
  /** How long to wait: `500ms`, `10s` or an ISO-8601 duration. */
  WAIT_TIMEOUT: 'Wow-Wait-Timeout',
  /** The scope to act in, for a caller allowed to act across scopes (decision D95). */
  SCOPE: 'Wow-Scope',
  /** The space, among the caller's own, to act in (decision D96). */
  SPACE: 'Wow-Space',
  /** The expected version of the aggregate, as an entity tag. */
  IF_MATCH: 'If-Match',
  /** The version, or descriptor version, a read already holds. */
  IF_NONE_MATCH: 'If-None-Match',
  /** The version of a state read, or of a query descriptor. */
  ETAG: 'ETag',
  /** The seconds after which a failed request may be retried. */
  RETRY_AFTER: 'Retry-After',
} as const;

/** How a V10 client reaches its service. */
export interface WowClientOptions {
  /**
   * The fetcher requests go through, with its base URL and interceptors
   * (authentication, say); the default fetcher when absent.
   */
  readonly fetcher?: Fetcher;
  /** A path put before every route, such as a gateway's prefix. */
  readonly basePath?: string;
}

/**
 * The scope a caller allowed to act across scopes acts in: the `Wow-Scope`
 * header (decision D95).
 */
export interface TargetScope {
  readonly tenantId?: string;
  readonly ownerId?: string;
  readonly spaceId?: string;
}

function quote(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/**
 * A target scope as the `Wow-Scope` header spells it, an RFC 8941
 * dictionary: `tenant="t1", owner="u1", space="s1"`.
 *
 * @param scope - The scope; at least one of its members is set
 */
export function formatScope(scope: TargetScope): string {
  const members = [
    scope.tenantId === undefined
      ? undefined
      : `tenant=${quote(scope.tenantId)}`,
    scope.ownerId === undefined ? undefined : `owner=${quote(scope.ownerId)}`,
    scope.spaceId === undefined ? undefined : `space=${quote(scope.spaceId)}`,
  ].filter(member => member !== undefined);
  if (members.length === 0) {
    throw new TypeError('Wow-Scope names a tenant, an owner or a space.');
  }
  return members.join(', ');
}

/** What every V10 request may carry beside its own parameters. */
export interface RequestOptions {
  /** The scope to act in (`Wow-Scope`), for a caller allowed to. */
  readonly scope?: TargetScope;
  /** The space to act in (`Wow-Space`), among the caller's own. */
  readonly space?: string;
  /** More headers: the request facts the service records, say. */
  readonly headers?: Readonly<Record<string, string>>;
  /** Cancels the request. */
  readonly signal?: AbortSignal;
}

/** One request of a V10 client. */
export interface Call {
  readonly method: 'GET' | 'POST' | 'DELETE';
  readonly path: string;
  readonly query?: Readonly<Record<string, string | number | undefined>>;
  readonly headers?: Readonly<Record<string, string | undefined>>;
  readonly body?: unknown;
  readonly options?: RequestOptions;
  /** Answers a 304 rather than rejecting with it. */
  readonly notModified?: boolean;
}

/** `{name}` segments of `path` filled from `params`, each encoded. */
export function expandPath(
  path: string,
  params: Readonly<Record<string, string>>,
): string {
  return path.replace(/\{([^}]+)\}/g, (segment, name: string) => {
    const value = params[name];
    if (value === undefined || value === '') {
      throw new TypeError(`The path ${path} needs ${name}.`);
    }
    return encodeURIComponent(value);
  });
}

function queryString(
  query: Readonly<Record<string, string | number | undefined>> | undefined,
): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query)) {
    if (value !== undefined) params.append(name, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

/**
 * Sends one request through the client's fetcher and answers its response;
 * a response that is not 2xx rejects with a {@link WowProblemError}.
 *
 * @param client - The client's options
 * @param call - The request
 */
export async function send(
  client: WowClientOptions,
  call: Call,
): Promise<Response> {
  const options = call.options ?? {};
  const headers: Record<string, string> = {};
  for (const [name, value] of Object.entries({
    ...call.headers,
    ...(options.scope === undefined
      ? {}
      : { [WowV10Headers.SCOPE]: formatScope(options.scope) }),
    ...(options.space === undefined
      ? {}
      : { [WowV10Headers.SPACE]: options.space }),
    ...options.headers,
  })) {
    if (value !== undefined) headers[name] = value;
  }
  const url = `${client.basePath ?? ''}${call.path}${queryString(call.query)}`;
  try {
    return await (client.fetcher ?? defaultFetcher).fetch(url, {
      method: call.method,
      headers,
      ...(call.body === undefined ? {} : { body: call.body as object }),
      ...(options.signal === undefined ? {} : { signal: options.signal }),
    });
  } catch (error) {
    const response = (error as { exchange?: { response?: Response } })?.exchange
      ?.response;
    if (call.notModified && response?.status === 304) return response;
    throw (await toWowProblemError(error)) ?? error;
  }
}

/** The JSON body of a 2xx response. */
export async function json<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

/** The entity tag of a response without its quotes or weak marker. */
export function entityTag(response: Response): string | undefined {
  const tag = response.headers.get(WowV10Headers.ETAG);
  return tag === null ? undefined : tag.replace(/^W\//, '').replace(/"/g, '');
}
