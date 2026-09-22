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
  emptyPreferences,
  isViewStoreError,
  ViewStoreError,
  type ConflictingState,
  type InstancePermissions,
  type ViewConfig,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPermissions,
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
 * `permissions` is the one method the port declares synchronous, because the
 * engine asks it before every command and a command cannot wait for a round
 * trip. An HTTP store therefore answers it from what `loadPermissions` has
 * already fetched — the application awaits that once, next to whatever else
 * it loads before mounting a workbench.
 *
 * The routes below are one reasonable shape, not a specification: a backend
 * that spells them differently only changes this file.
 */
export class FetcherViewStore implements ViewStore {
  private readonly fetcher: Fetcher;
  private readonly basePath: string;
  /** One definition's answer, by definition id; see `loadPermissions`. */
  private readonly allowed = new Map<string, ViewPermissions>();

  constructor(options: { fetcher: Fetcher; basePath?: string }) {
    this.fetcher = options.fetcher;
    this.basePath = trimSlash(options.basePath ?? '/view-engine');
  }

  /**
   * The server answers with summaries rather than instances. Each one carries
   * the `kind` of the config it names — projected from that config, never
   * stored a second time — so a sidebar can tell a record view from an
   * analysis one without reading either config.
   */
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

  /**
   * A definition nobody has ordered or defaulted yet has no preference
   * record, and a server that answers 404 for it is saying exactly that.
   *
   * It comes back as `emptyPreferences()` — revision `'0'` — which is what
   * `MemoryViewStore` answers for the same definition. The revision is the
   * point: it is what the first `setPreferences` sends as `If-Match`, so a
   * host written against one store writes the same first request against the
   * other. Left to throw, an untouched definition would take the sidebar's
   * order and default down with it on every first visit.
   */
  async getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences> {
    try {
      return await this.send<ViewPreferences>(
        'GET',
        this.preferences(definitionId),
        { signal },
      );
    } catch (error) {
      if (isViewStoreError(error) && error.code === 'NOT_FOUND')
        return emptyPreferences();
      throw error;
    }
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

  /**
   * Fetches what the current user may do with one definition's views and
   * keeps it, so `permissions` can answer without a round trip. Call it once
   * before the workbench is mounted; calling it again replaces the answer.
   *
   * A definition whose permissions were never loaded is not "nothing is
   * allowed": the port's own default for a store that declares no
   * `permissions` at all is that everything is, and this holds to it. The
   * server is the trusted boundary either way — `permissions` decides which
   * buttons are enabled, never what a write is allowed to do.
   */
  async loadPermissions(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPermissions> {
    const body = await this.send<PermissionsBody>(
      'GET',
      `${this.basePath}/definitions/${encodeURIComponent(definitionId)}/permissions`,
      { signal },
    );
    const allowed = toPermissions(body);
    this.allowed.set(definitionId, allowed);
    return allowed;
  }

  permissions(definitionId: string): ViewPermissions {
    return this.allowed.get(definitionId) ?? ALLOW_ALL;
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

/**
 * What the permissions endpoint answers with. Every member is optional
 * because the mapping below reads a missing one as "the server said nothing
 * about this", and what a server says nothing about is allowed — the same
 * rule the port applies to a store with no `permissions` method at all.
 * Refusing on silence would disable the whole sidebar the first time a
 * backend forgot a field.
 */
interface PermissionsBody {
  createPersonal?: boolean;
  createShared?: boolean;
  reorder?: boolean;
  setDefault?: boolean;
  /** The instances this user may not act on freely, by id. */
  instances?: Record<string, Partial<InstancePermissions> | undefined>;
  /** What an instance the answer does not name may take, including one created since. */
  instanceDefault?: Partial<InstancePermissions>;
}

const ALLOWED_INSTANCE: InstancePermissions = {
  save: true,
  rename: true,
  delete: true,
};

/** Everything, which is what an unloaded definition and a silent server get. */
const ALLOW_ALL: ViewPermissions = toPermissions({});

function toPermissions(body: PermissionsBody): ViewPermissions {
  return {
    createPersonal: body.createPersonal ?? true,
    createShared: body.createShared ?? true,
    reorder: body.reorder ?? true,
    setDefault: body.setDefault ?? true,
    instance: id => ({
      ...ALLOWED_INSTANCE,
      ...body.instanceDefault,
      ...body.instances?.[id],
    }),
  };
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
      // one that cannot leaves both members unset and the engine fetches it.
      return new ViewStoreError(
        'CONFLICT',
        response.statusText,
        await heldBy(response),
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

/**
 * The state a conflict body carries, told apart by what is in it: an
 * instance names a config, a preference record names an order. The port
 * keeps them in two members rather than one, so this is where the two are
 * distinguished — reading the body once, here, instead of casting one into
 * the other wherever it is used.
 */
async function heldBy(response: Response): Promise<ConflictingState> {
  try {
    const body = (await response.clone().json()) as Partial<
      ViewInstance & ViewPreferences
    >;
    if (typeof body?.revision !== 'string') return {};
    if (body.config !== undefined && typeof body.id === 'string')
      return { instance: body as ViewInstance };
    if (Array.isArray(body.order))
      return { preferences: body as ViewPreferences };
    return {};
  } catch {
    // A conflict without a readable body is still a conflict.
    return {};
  }
}
