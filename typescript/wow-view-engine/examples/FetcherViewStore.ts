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
  ResultExtractors,
  type Fetcher,
  type FetchRequestInit,
  type RequestOptions,
} from '@ahoo-wang/fetcher';
import {
  ViewStoreError,
  type ViewConfig,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPreferences,
  type ViewStore,
  type WriteContext,
} from '../src/index.js';

/**
 * A `ViewStore` over HTTP, written with `@ahoo-wang/fetcher`.
 *
 * It is the port's second consumer after `MemoryViewStore`, and it exists to
 * show that the contract needs nothing of a backend beyond two headers. Both
 * carry a consistency rule the engine relies on:
 *
 * - `If-Match` carries the revision a write expects, so a server can refuse a
 *   write that would overwrite someone else's, and the engine turns that
 *   refusal into the conflict dialogue.
 * - `Idempotency-Key` carries the `requestId`, which a retry reuses. It is
 *   what lets a server recognise the replay of a write whose answer was lost
 *   and return the original outcome rather than creating a second view.
 *
 * The routes below are one reasonable shape, not a specification: a backend
 * that spells them differently only changes this file.
 */
export class FetcherViewStore implements ViewStore {
  private readonly fetcher: Fetcher;
  private readonly basePath: string;

  constructor(options: { fetcher: Fetcher; basePath?: string }) {
    this.fetcher = options.fetcher;
    this.basePath = trimSlash(options.basePath ?? '/view-engine');
  }

  async list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]> {
    return this.send<ViewInstanceSummary[]>('GET', this.views(definitionId), {
      signal,
    });
  }

  async get(id: string, signal?: AbortSignal): Promise<ViewInstance> {
    return this.send<ViewInstance>('GET', this.view(id), { signal });
  }

  async create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.send<ViewInstance>('POST', `${this.basePath}/views`, {
      body: input,
      headers: idempotency(context.requestId),
      signal: context.signal,
    });
  }

  async save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.send<ViewInstance>('PUT', `${this.view(id)}/config`, {
      body: { config },
      headers: { ...idempotency(context.requestId), 'If-Match': revision },
      signal: context.signal,
    });
  }

  async rename(
    id: string,
    title: string,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.send<ViewInstance>('PUT', `${this.view(id)}/title`, {
      body: { title },
      headers: { ...idempotency(context.requestId), 'If-Match': revision },
      signal: context.signal,
    });
  }

  async delete(
    id: string,
    revision: string,
    context: WriteContext,
  ): Promise<void> {
    await this.send<void>('DELETE', this.view(id), {
      headers: { ...idempotency(context.requestId), 'If-Match': revision },
      signal: context.signal,
      // A delete answers with no content, so there is no body to parse.
      empty: true,
    });
  }

  async getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences> {
    return this.send<ViewPreferences>('GET', this.preferences(definitionId), {
      signal,
    });
  }

  async setPreferences(
    definitionId: string,
    preferences: ViewPreferences,
    context: WriteContext,
  ): Promise<ViewPreferences> {
    return this.send<ViewPreferences>('PUT', this.preferences(definitionId), {
      body: preferences,
      headers: {
        ...idempotency(context.requestId),
        'If-Match': preferences.revision,
      },
      signal: context.signal,
    });
  }

  private views(definitionId: string): string {
    return `${this.basePath}/definitions/${encodeURIComponent(definitionId)}/views`;
  }

  private view(id: string): string {
    return `${this.basePath}/views/${encodeURIComponent(id)}`;
  }

  private preferences(definitionId: string): string {
    return `${this.basePath}/definitions/${encodeURIComponent(definitionId)}/preferences`;
  }

  /**
   * The one place a request leaves this store, and so the one place a
   * transport failure becomes a `ViewStoreError`. Every method above returns
   * either its value or that error, which is the whole of the port's failure
   * contract.
   */
  private async send<R>(
    method: string,
    url: string,
    request: FetchRequestInit & { empty?: boolean },
  ): Promise<R> {
    const { empty, ...init } = request;
    const options: RequestOptions = {
      resultExtractor: empty
        ? ResultExtractors.Response
        : ResultExtractors.Json,
    };
    try {
      return (await this.fetcher.request<R>(
        { ...init, method, url },
        options,
      )) as R;
    } catch (error) {
      throw await toStoreError(error);
    }
  }
}

function idempotency(requestId: string): Record<string, string> {
  return { 'Idempotency-Key': requestId };
}

function trimSlash(path: string): string {
  return path.endsWith('/') ? path.slice(0, -1) : path;
}

/**
 * HTTP onto the port's five codes.
 *
 * The distinction that matters is the last one: a request that was refused is
 * settled, while one that never arrived or whose answer was lost is not, and
 * the engine keeps the second kind open for a retry rather than reporting a
 * failure the user cannot act on.
 */
async function toStoreError(error: unknown): Promise<ViewStoreError> {
  const response = responseOf(error);
  if (!response) return new ViewStoreError('UNAVAILABLE', messageOf(error));

  switch (response.status) {
    case 404:
      return new ViewStoreError('NOT_FOUND', response.statusText);
    case 401:
    case 403:
      return new ViewStoreError('FORBIDDEN', response.statusText);
    case 409:
    case 412:
      // A server that can say what it holds saves the engine a round trip;
      // one that cannot leaves `remote` undefined and the engine fetches it.
      return new ViewStoreError(
        'CONFLICT',
        response.statusText,
        await remoteOf(response),
      );
    case 400:
    case 422:
      return new ViewStoreError('INVALID', response.statusText);
    default:
      // 5xx and anything unrecognised: reached, outcome unknown.
      return new ViewStoreError('UNAVAILABLE', response.statusText);
  }
}

function responseOf(error: unknown): Response | undefined {
  const exchange = (error as { exchange?: { response?: Response } }).exchange;
  return exchange?.response;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

async function remoteOf(
  response: Response,
): Promise<ViewInstance | ViewPreferences | undefined> {
  try {
    const body = (await response.clone().json()) as
      ViewInstance | ViewPreferences;
    return typeof body?.revision === 'string' ? body : undefined;
  } catch {
    // A conflict without a readable body is still a conflict.
    return undefined;
  }
}
