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

import { ResultExtractors, type Fetcher } from '@ahoo-wang/fetcher';
import {
  asc,
  CommandHeaders,
  CommandStage,
  filter,
  listQuery,
  singleQuery,
  type BindingError,
} from '@ahoo-wang/wow-client';
import {
  isSystemInstanceId,
  isViewStoreError,
  ViewStoreError,
  type ViewAudience,
  type ViewConfig,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewPermissions,
  type ViewPreferences,
  type ViewStore,
  type WriteContext,
} from '@ahoo-wang/wow-view-engine';
import {
  failureOf,
  Failure,
  isDuplicateRequest,
  REFERENCED_BY_SHARED_DASHBOARD,
} from './errors.js';
import { PATHS, pathAt, type Place } from './paths.js';
import {
  isStored,
  preferencesAt,
  preferencesInput,
  revisionOf,
  samePreferences,
  SUMMARY_FIELDS,
  systemInstance,
  systemSummary,
  toInstance,
  toPreferences,
  toSummary,
  type CommandResultBody,
  type PreferencesBody,
  type SystemViewBody,
  type ViewSnapshotBody,
} from './wire.js';

/** How a {@link WowViewStore} reaches the view store. */
export interface WowViewStoreOptions {
  /**
   * The fetcher every request goes through, on the base URL that serves
   * `/view-store/…` (the CoSec gateway in front of the view store server, or
   * the service that embeds the starter).
   *
   * Its interceptors carry who is asking; the store never does. They must
   * fill the path variables `{tenantId}` and, on a personal path,
   * `{ownerId}` where the store leaves them out — fetcher-cosec's
   * `ResourceAttributionRequestInterceptor` fills them from the token's
   * `tenantId` and `sub` — and send `CoSec-App-Id` (fetcher-cosec's
   * `CoSecRequestInterceptor`), and the space and the authorization with it.
   * A host nobody signs in to adds an interceptor of its own that fills the
   * same defaults (the owner `(shared)`, {@link SHARED_OWNER_ID}).
   */
  fetcher: Fetcher;
  /**
   * Which buttons are enabled for one definition's views. The server does
   * not authorize, the CoSec gateway does; a host answers this by the roles
   * it holds there — `changeAudience` by the role that may write
   * `owner/(shared)`, which claiming a view needs. Left out, everything is
   * allowed, as the port reads a store without `permissions`.
   */
  permissions?: (definitionId: string) => ViewPermissions;
}

/**
 * The largest list the server answers (its query budget); a definition with
 * more views of one audience lists the first this many.
 */
const LIST_LIMIT = 1000;

/** How many preference writes the store remembers the outcome of. */
const REMEMBERED_WRITES = 256;

/** What a stored system view's writes expect: its version at a revision. */
interface StoredAt {
  /** The content hash the engine holds as the view's revision. */
  revision: string;
  version: number;
}

/** One instance write, as sent from the place the view is at. */
interface InstanceWrite {
  method: 'POST' | 'PUT' | 'DELETE';
  url: string;
  /** The path it is sent to, and so replayed on. */
  sentTo: Place;
  body?: unknown;
  /** Where the view is once it lands; `null` for a delete. */
  landsAt: Place | null;
}

/** The replay route found nothing this write can be answered with. */
const NOT_REPLAYED = Symbol('not replayed');

/**
 * The view engine's `ViewStore` over the Wow view store (`view-store/` in the
 * Wow repository): saved views and preferences as two Wow aggregates, served
 * under `/view-store/tenant/{tenantId}/owner/{ownerId}/…`.
 *
 * **The owner segment is the audience.** A personal view lives on the
 * caller's own path (`{ownerId}` filled by the fetcher's interceptors), a
 * shared one on `owner/(shared)`; the CoSec gateway decides who may use
 * which. The port names a view by id alone, so the store remembers where it
 * last saw each one (a list, a read, a write) and looks an unknown id up on
 * the personal path, the shared path and the server's system views, in that
 * order. Setting a view shared or personal moves it between the two paths,
 * id kept.
 *
 * **Writes** carry the port's `requestId` as `Command-Request-Id`, its
 * `revision` as `Command-Aggregate-Version`, and wait for the snapshot; the
 * answer is the view read back at the version the write left. A write the
 * server refuses as a stale version or a repeated request id is first looked
 * up by its request id (the replay route): a retry answers what the first
 * attempt wrote. Otherwise a stale version is `CONFLICT` carrying the view as
 * it is now, and a view that turns out to be gone is `NOT_FOUND`.
 *
 * **Errors** are read by Wow's error code onto the port's five codes, by the
 * HTTP status only for an answer without a code the store knows; a request
 * that got no answer is `UNAVAILABLE`, and a retry under the same
 * `requestId` is safe.
 *
 * **Creating is not idempotent on the server**, which generates the id. A
 * store remembers the request ids of its own creates (bounded), and a retry
 * of one first asks the replay route whether it landed; a retry sent by
 * another store — another tab, a reload — makes a second view.
 */
export class WowViewStore implements ViewStore {
  private readonly fetcher: Fetcher;
  /**
   * Where each view was last seen, by id. Kept after a delete, for its retry.
   * A `system` view is read through the server's system views (configured
   * and stored) and written, when stored, on the system path.
   */
  private readonly places = new Map<string, Place>();
  /**
   * The preferences have no replay route, so the store keeps what each of
   * its own writes answered, and which ones it sent: a retry answers the
   * first outcome, and a retry whose first answer was lost is recognised.
   */
  private readonly preferenceOutcomes = new Remembered<ViewPreferences>();
  private readonly preferenceAttempts = new Remembered<true>();
  /** The request ids of the creates this store sent, for their retries. */
  private readonly createAttempts = new Remembered<true>();
  /**
   * The system views as last read, by id: a stored one's version at its
   * revision (a hash of its content, while a write expects the version), or
   * `null` for a configured one, which is read-only.
   */
  private readonly storedVersions = new Remembered<StoredAt | null>(
    REMEMBERED_SYSTEM_VIEWS,
  );
  /** The host's {@link WowViewStoreOptions.permissions}, when it gave any. */
  readonly permissions?: (definitionId: string) => ViewPermissions;

  constructor(options: WowViewStoreOptions) {
    this.fetcher = options.fetcher;
    // Left undefined when the host declared none, which the port reads as
    // "everything is allowed".
    if (options.permissions) this.permissions = options.permissions;
  }

  /**
   * The caller's personal views, the shared views and the server's system
   * views of the definition: three requests, sent together, answered in the
   * port's order — system, shared, personal, each oldest first (the server
   * sorts each audience by the time its first event was written).
   *
   * A server with no view store at all (one released before it) answers
   * every route `404`, where a list on one that has it never does: that is
   * `UNSUPPORTED`, not a missing view.
   */
  list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]> {
    return guard(async () => {
      const query = listQuery({
        filter: filter.eq('state.definitionId', definitionId),
        projection: { include: SUMMARY_FIELDS },
        sort: LIST_ORDER,
        limit: LIST_LIMIT,
      });
      const [personal, shared, system] = await Promise.all([
        this.json<ViewSnapshotBody[]>('POST', PATHS.list, 'personal', {
          body: query,
          signal,
        }),
        this.json<ViewSnapshotBody[]>('POST', PATHS.list, 'shared', {
          body: query,
          signal,
        }),
        this.json<SystemViewBody[]>('GET', PATHS.systemViews, 'shared', {
          query: { definitionId },
          signal,
        }),
      ]).catch((thrown: unknown) => {
        throw thrown instanceof Failure && thrown.code === 'NOT_FOUND'
          ? unsupported(thrown)
          : thrown;
      });
      for (const view of system) this.rememberSystem(view);
      const seen = new Map<string, ViewInstanceSummary>();
      for (const summary of [
        ...personal.map(toSummary),
        ...shared.map(toSummary),
        ...system.map(systemSummary),
      ]) {
        if (seen.has(summary.id)) continue;
        seen.set(summary.id, summary);
        this.places.set(summary.id, summary.scope);
      }
      // Sorted stably: within an audience, the server's order stands.
      return [...seen.values()].sort(
        (a, b) => AUDIENCE_RANK[a.scope] - AUDIENCE_RANK[b.scope],
      );
    });
  }

  /** View `id` wherever it is: the caller's, shared, or the server's system view. */
  get(id: string, signal?: AbortSignal): Promise<ViewInstance> {
    return guard(async () => (await this.find(id, signal)).instance);
  }

  /**
   * Posts the view to the path of its `scope`; the server generates the id.
   * A retry of a create this store sent asks the replay route on that path
   * first, and answers the view the first attempt made when it landed; the
   * server itself does not deduplicate, so a retry from another store makes
   * a second view.
   */
  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return guard(async () => {
      // A system view is created on the system path, global (the gateway
      // decides who may); configured and code system views stay read-only.
      const place: Place = input.scope;
      const { requestId } = context;
      if (this.createAttempts.has(requestId)) {
        const made = await this.probe(place, context, true);
        const snapshot =
          made?.status === 200
            ? ((await made.json()) as ViewSnapshotBody | null)
            : null;
        if (snapshot?.aggregateId) {
          this.places.set(snapshot.aggregateId, place);
          // A system view's revision is its content hash, which the
          // snapshot does not carry: it is read from the system views.
          return place === 'system'
            ? this.readAt('system', snapshot.aggregateId, context.signal)
            : toInstance(snapshot);
        }
      }
      this.createAttempts.set(requestId, true);
      const { definitionId, title, config } = input;
      const write: InstanceWrite = {
        method: 'POST',
        url: PATHS.views,
        sentTo: place,
        body: { definitionId, title, config },
        landsAt: place,
      };
      const result = await this.json<CommandResultBody>(
        write.method,
        write.url,
        place,
        {
          body: write.body,
          headers: writeHeaders(context),
          signal: context.signal,
        },
      ).catch(async (thrown: unknown) => {
        throw await this.unsupportedOr(thrown, context.signal);
      });
      const landed = await this.landed(
        result.aggregateId,
        write,
        result,
        context,
      );
      return landed!;
    });
  }

  /** Replaces the view's config, at the expected `revision`. */
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.write(id, revision, context, place => ({
      method: 'PUT',
      url: PATHS.save,
      sentTo: place,
      body: { config },
      landsAt: place,
    })) as Promise<ViewInstance>;
  }

  /** Renames the view (the server trims the title), at the expected `revision`. */
  rename(
    id: string,
    title: string,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.write(id, revision, context, place => ({
      method: 'PUT',
      url: PATHS.rename,
      sentTo: place,
      body: { title },
      landsAt: place,
    })) as Promise<ViewInstance>;
  }

  /**
   * 设为共享 sends `share` to the path the view is at; 设为个人 sends `claim`
   * to the caller's own path, which the gateway admits only with the role
   * that may write `owner/(shared)`. Either answers a view that already has
   * the audience as it is, revision unmoved.
   *
   * A shared view a shared dashboard shows stays shared: the server refuses
   * the claim and names the boards, and the refusal carries them by
   * **title** in `boards`, as they are stored — a title written as a key
   * stays one, and is said where the engine shows the refusal.
   */
  changeAudience(
    id: string,
    audience: ViewAudience,
    revision: string,
    context: WriteContext,
  ): Promise<ViewInstance> {
    return this.write(id, revision, context, place =>
      place === 'system'
        ? readOnly('A system view never moves audience')
        : audience === 'shared'
          ? {
              method: 'PUT',
              url: PATHS.share,
              sentTo: place,
              body: {},
              landsAt: 'shared',
            }
          : {
              method: 'PUT',
              url: PATHS.claim,
              sentTo: 'personal',
              landsAt: 'personal',
            },
    ) as Promise<ViewInstance>;
  }

  /** Deletes the view, at the expected `revision`; a board showing it keeps a broken panel. */
  async delete(
    id: string,
    revision: string,
    context: WriteContext,
  ): Promise<void> {
    await this.write(id, revision, context, place => ({
      method: 'DELETE',
      url: PATHS.view,
      sentTo: place,
      body: {},
      landsAt: null,
    }));
  }

  /** The caller's own, or `(shared)`'s where the host fills that owner. */
  getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences> {
    return guard(async () =>
      toPreferences(
        await this.json<PreferencesBody>('GET', PATHS.preferences, 'personal', {
          path: { definitionId },
          signal,
        }).catch(async (thrown: unknown) => {
          throw await this.unsupportedOr(thrown, signal);
        }),
      ),
    );
  }

  /**
   * Writes the preferences at the expected `revision` (`'0'` for ones never
   * written). The server has no replay route for them, so a retry answers
   * what this store's first attempt answered, or — its answer lost — what is
   * stored when that is what it wrote.
   */
  setPreferences(
    definitionId: string,
    preferences: ViewPreferences,
    context: WriteContext,
  ): Promise<ViewPreferences> {
    return guard(async () => {
      const { requestId, signal } = context;
      const answered = this.preferenceOutcomes.get(requestId);
      if (answered) return structuredClone(answered);
      const retry = this.preferenceAttempts.has(requestId);
      this.preferenceAttempts.set(requestId, true);
      let answer: ViewPreferences;
      try {
        const result = await this.json<CommandResultBody>(
          'PUT',
          PATHS.preferences,
          'personal',
          {
            path: { definitionId },
            body: preferencesInput(preferences),
            headers: writeHeaders(context, preferences.revision),
            signal,
          },
        );
        answer =
          typeof result.aggregateVersion === 'number'
            ? preferencesAt(preferences, result.aggregateVersion)
            : await this.getPreferences(definitionId, signal);
      } catch (thrown) {
        if (
          !(thrown instanceof Failure) ||
          (thrown.code !== 'CONFLICT' && !isDuplicateRequest(thrown))
        )
          throw await this.unsupportedOr(thrown, signal);
        const current = await this.getPreferences(definitionId, signal);
        // A retry the server refuses because its first attempt landed: what
        // is stored says what it wrote, unless another writer moved it on.
        if (
          (retry || isDuplicateRequest(thrown)) &&
          samePreferences(current, preferences)
        )
          answer = current;
        else
          throw new ViewStoreError('CONFLICT', thrown.message, {
            ...thrown.held(),
            preferences: current,
          });
      }
      this.preferenceOutcomes.set(requestId, answer);
      return structuredClone(answer);
    });
  }

  /**
   * One instance write: to the place the view is at, then the answer read
   * back. A view remembered at a place it has since left (another tab shared
   * it) is looked up again and the write sent once more to where it is.
   */
  private write(
    id: string,
    revision: string,
    context: WriteContext,
    plan: (place: Place) => InstanceWrite,
  ): Promise<ViewInstance | undefined> {
    return guard(async () => {
      const { signal } = context;
      const remembered = this.places.get(id);
      let place = remembered ?? (await this.find(id, signal)).place;
      let relocated = remembered === undefined;
      for (;;) {
        const write = plan(place);
        let expected = revision;
        if (place === 'system') {
          const stored = await this.storedVersion(id, revision, write, context);
          if ('answer' in stored) return stored.answer;
          expected = stored.version;
        }
        let result: CommandResultBody;
        try {
          result = await this.json<CommandResultBody>(
            write.method,
            write.url,
            write.sentTo,
            {
              path: { id },
              body: write.body,
              headers: writeHeaders(context, expected),
              signal,
            },
          );
        } catch (thrown) {
          if (!(thrown instanceof Failure)) throw thrown;
          const replayed = await this.replayed(id, write, context, thrown);
          if (replayed !== NOT_REPLAYED) return replayed;
          if (
            !relocated &&
            (thrown.code === 'NOT_FOUND' || thrown.code === 'FORBIDDEN')
          ) {
            relocated = true;
            const found = (await this.find(id, signal, null)).place;
            // Only where the write would go elsewhere: a claim goes to the
            // caller's own path wherever the view is.
            if (found !== place && plan(found).sentTo !== write.sentTo) {
              place = found;
              continue;
            }
          }
          if (thrown.code === 'CONFLICT' || isDuplicateRequest(thrown))
            throw await this.conflict(id, thrown, signal);
          throw await this.refusal(thrown, signal);
        }
        // Landed: what goes wrong reading it back is never a reason to send
        // it again.
        return this.landed(id, write, result, context);
      }
    });
  }

  /** A landed write's answer: the view read back at the version it left. */
  private async landed(
    id: string,
    write: InstanceWrite,
    result: CommandResultBody,
    context: WriteContext,
  ): Promise<ViewInstance | undefined> {
    if (write.landsAt === null) return undefined;
    this.places.set(id, write.landsAt);
    const version = result.aggregateVersion;
    let read: ViewInstance | undefined;
    let failure: unknown;
    try {
      read = await this.readAt(write.landsAt, id, context.signal);
      if (typeof version !== 'number' || read.revision === revisionOf(version))
        return read;
      // A system view's revision is its content hash: the version it was
      // read at says whether it is this write's.
      if (write.landsAt === 'system') {
        if (this.storedVersions.get(id)?.version === version) return read;
      }
    } catch (thrown) {
      failure = thrown;
    }
    // Another writer moved it on between the write and the read: the replay
    // route answers it as this write left it. The write landed, so a probe
    // that fails is no reason to report it otherwise: what was read stands.
    const replayed = await this.replay(id, write, context, false);
    if (replayed !== NOT_REPLAYED && replayed !== undefined) return replayed;
    if (read) return read;
    throw failure;
  }

  /**
   * The answer of a write the server refused as a stale version, a repeated
   * request id or a missing view, when the refusal is that of a retry: the
   * replay route finds the first attempt by its request id.
   */
  private async replayed(
    id: string,
    write: InstanceWrite,
    context: WriteContext,
    failure: Failure,
  ): Promise<ViewInstance | undefined | typeof NOT_REPLAYED> {
    const retryable =
      failure.code === 'CONFLICT' ||
      failure.code === 'NOT_FOUND' ||
      isDuplicateRequest(failure);
    return retryable ? this.replay(id, write, context, true) : NOT_REPLAYED;
  }

  /**
   * What the write with the context's request id left of view `id`, from the
   * replay route: the view at that version, or `undefined` for a delete.
   *
   * The route finds a write only on the path of the owner who wrote it, and
   * a retry may be sent elsewhere than its first attempt — a share goes to
   * the personal path the view has left by the time it is retried — so the
   * path this attempt went to is asked first and the other one next.
   *
   * A probe that fails other than as "not found" is no answer on its path.
   * With `strict` — a refused write, whose own path is asked first — that
   * path's failure is passed on: whether the first attempt landed is
   * unknown, and a retry under the same request id is safe. The other path
   * (a caller without the shared role is refused there), and every probe of
   * a write that landed, count it as not replayed, and the caller keeps what
   * it knows: the refusal, or the view it read back.
   */
  private async replay(
    id: string,
    write: InstanceWrite,
    context: WriteContext,
    strict: boolean,
  ): Promise<ViewInstance | undefined | typeof NOT_REPLAYED> {
    const places: Place[] =
      write.sentTo === 'system'
        ? ['system']
        : write.sentTo === 'personal'
          ? ['personal', 'shared']
          : ['shared', 'personal'];
    for (const [index, place] of places.entries()) {
      const response = await this.probe(place, context, strict && index === 0);
      if (!response) continue;
      if (response.status === 204)
        return write.landsAt === null ? undefined : NOT_REPLAYED;
      const snapshot = (await response.json()) as ViewSnapshotBody;
      if (write.landsAt === null || snapshot?.aggregateId !== id)
        return NOT_REPLAYED;
      // The replay route answers a snapshot, whose revision would be its
      // version; a system view's is its content hash, which only the server
      // computes: the view is answered as it is now.
      if (write.landsAt === 'system')
        return this.readAt('system', id, context.signal);
      return toInstance(snapshot);
    }
    return NOT_REPLAYED;
  }

  /**
   * The replay route at `place` for the context's request id; `undefined`
   * when it knows none, or — not `strict` — when it could not be asked.
   */
  private async probe(
    place: Place,
    context: WriteContext,
    strict: boolean,
  ): Promise<Response | undefined> {
    try {
      return await this.send('GET', PATHS.replay, place, {
        path: { requestId: context.requestId },
        signal: context.signal,
      });
    } catch (thrown) {
      if (thrown instanceof Failure && (thrown.code === 'NOT_FOUND' || !strict))
        return undefined;
      throw thrown;
    }
  }

  /** A stale write's refusal, carrying the view as it is now. */
  private async conflict(
    id: string,
    failure: Failure,
    signal: AbortSignal | undefined,
  ): Promise<ViewStoreError> {
    const { instance } = await this.find(id, signal, null);
    return new ViewStoreError('CONFLICT', failure.message, {
      ...failure.held(),
      instance,
    });
  }

  /**
   * The port's refusal of a write. A claim the server refuses because shared
   * dashboards show the view carries those boards' titles (`boards`), which
   * the server gives beside each board's id (a board it gives no title for
   * is read for one); the engine says the refusal around them.
   */
  private async refusal(
    failure: Failure,
    signal: AbortSignal | undefined,
  ): Promise<ViewStoreError> {
    const boards = failure.bindingErrors.filter(
      error => error.code === REFERENCED_BY_SHARED_DASHBOARD,
    );
    if (failure.code !== 'INVALID' || boards.length === 0)
      return failure.toStoreError();
    const titles = await Promise.all(
      boards.map(board => this.boardTitle(board, signal)),
    );
    return new ViewStoreError('INVALID', failure.message, {
      ...failure.held(),
      boards: titles,
    });
  }

  private async boardTitle(
    board: BindingError,
    signal: AbortSignal | undefined,
  ): Promise<string> {
    if (typeof board.msg === 'string' && board.msg.trim() !== '')
      return board.msg;
    try {
      return (await this.readAt('shared', board.name, signal)).title;
    } catch {
      return board.name;
    }
  }

  /**
   * View `id` and where it is: first where it was last seen (`first`, `null`
   * to ignore that), then the personal path, the shared path and the
   * server's system views.
   */
  private async find(
    id: string,
    signal: AbortSignal | undefined,
    first: Place | null | undefined = this.places.get(id),
  ): Promise<{ place: Place; instance: ViewInstance }> {
    // Ids in `system:` are the views a host declares in code; the server
    // never issues or serves one.
    if (!isSystemInstanceId(id)) {
      const order: Place[] = ['personal', 'shared', 'system'];
      if (first)
        order.sort((a, b) => Number(b === first) - Number(a === first));
      for (const place of order) {
        try {
          const instance = await this.readAt(place, id, signal);
          this.places.set(id, place);
          return { place, instance };
        } catch (thrown) {
          if (thrown instanceof Failure && thrown.code === 'NOT_FOUND')
            continue;
          throw thrown;
        }
      }
      // Every place answered `404`: a view that is not there, or no view
      // store there at all (a server released before it) — the one
      // question the server's system views tell apart.
      if (!(await this.served(signal)))
        throw new ViewStoreError('UNSUPPORTED', NO_VIEW_STORE);
    }
    throw new ViewStoreError('NOT_FOUND', `No such view: ${id}`);
  }

  /**
   * Whether the server has a view store at all: its system views answer
   * on one that has (an empty list included), and `404` on one released
   * before it. Any other failure is no answer to that, and reads as served.
   */
  private async served(signal: AbortSignal | undefined): Promise<boolean> {
    try {
      await this.send('GET', PATHS.systemViews, 'shared', { signal });
      return true;
    } catch (thrown) {
      return !(thrown instanceof Failure && thrown.code === 'NOT_FOUND');
    }
  }

  /** A `404` from a server with no view store is `UNSUPPORTED`; else as it was. */
  private async unsupportedOr(
    thrown: unknown,
    signal: AbortSignal | undefined,
  ): Promise<unknown> {
    return thrown instanceof Failure &&
      thrown.code === 'NOT_FOUND' &&
      !(await this.served(signal))
      ? unsupported(thrown)
      : thrown;
  }

  /** Keeps what a stored system view's writes expect; forgets a configured one. */
  private rememberSystem(view: SystemViewBody): SystemViewBody {
    this.storedVersions.set(
      view.id,
      isStored(view)
        ? { revision: view.revision, version: view.version! }
        : null,
    );
    return view;
  }

  /**
   * The version a write of stored system view `id` at `revision` (its
   * content hash) expects: as last read, else read now. A configured system
   * view is read-only (`FORBIDDEN`; a code one is never found here). A
   * revision other than the view's is stale — unless this write is a retry
   * whose first attempt moved it on, which the replay route answers — and is
   * then `CONFLICT`, carrying the view as it is.
   */
  private async storedVersion(
    id: string,
    revision: string,
    write: InstanceWrite,
    context: WriteContext,
  ): Promise<{ version: string } | { answer: ViewInstance | undefined }> {
    let known = this.storedVersions.get(id);
    if (known === null) readOnly('Configured system views are read-only');
    if (known?.revision !== revision) {
      const instance = await this.readAt('system', id, context.signal);
      known = this.storedVersions.get(id);
      if (known && known.revision !== revision) {
        const replayed = await this.replay(id, write, context, false);
        if (replayed !== NOT_REPLAYED) return { answer: replayed };
        throw new ViewStoreError(
          'CONFLICT',
          `System view ${id} has moved on from revision ${revision}`,
          { instance },
        );
      }
    }
    if (!known) readOnly('Configured system views are read-only');
    return { version: String(known.version) };
  }

  private async readAt(
    place: Place,
    id: string,
    signal: AbortSignal | undefined,
  ): Promise<ViewInstance> {
    if (place === 'system')
      return systemInstance(
        this.rememberSystem(
          await this.json<SystemViewBody>('GET', PATHS.systemView, 'shared', {
            path: { id },
            signal,
          }),
        ),
      );
    return toInstance(
      await this.json<ViewSnapshotBody>('POST', PATHS.single, place, {
        body: singleQuery({ filter: filter.id(id) }),
        signal,
      }),
    );
  }

  private async json<R>(
    method: string,
    url: string,
    place: Place,
    request: Request,
  ): Promise<R> {
    const response = await this.send(method, url, place, request);
    return (await response.json()) as R;
  }

  /**
   * The one place a request leaves the store, and so the one place what it
   * threw becomes a {@link Failure}.
   */
  private async send(
    method: string,
    url: string,
    place: Place,
    { path, query, body, headers, signal }: Request,
  ): Promise<Response> {
    try {
      return await this.fetcher.request<Response>(
        {
          url,
          method,
          urlParams: { path: pathAt(place, path), query },
          body: body as Record<string, unknown> | undefined,
          headers,
          signal,
        },
        { resultExtractor: ResultExtractors.Response },
      );
    } catch (error) {
      throw await failureOf(error);
    }
  }
}

/** One request's parts beside its method, route and place. */
interface Request {
  path?: Record<string, string>;
  query?: Record<string, string>;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

/** The headers of a write: its request id, its expected version, the wait. */
function writeHeaders(
  context: WriteContext,
  revision?: string,
): Record<string, string> {
  return {
    [CommandHeaders.REQUEST_ID]: context.requestId,
    [CommandHeaders.WAIT_STAGE]: CommandStage.SNAPSHOT,
    ...(revision === undefined
      ? {}
      : { [CommandHeaders.AGGREGATE_VERSION]: revision }),
  };
}

/** What a server without a view store is told as. */
const NO_VIEW_STORE = 'This server has no view store';

function unsupported(failure: Failure): ViewStoreError {
  return new ViewStoreError('UNSUPPORTED', NO_VIEW_STORE, failure.held());
}

/**
 * The port's list order, asked of the server per audience: oldest first, by
 * the time a view's first event was written, its id breaking a tie.
 */
const LIST_ORDER = [asc('firstEventTime'), asc('aggregateId')];

/** System views first, then shared, then personal: the port's list order. */
const AUDIENCE_RANK: Readonly<Record<ViewInstanceSummary['scope'], number>> = {
  system: 0,
  shared: 1,
  personal: 2,
};

/** Refuses a write to a system view the server or the code declares. */
function readOnly(message: string): never {
  throw new ViewStoreError('FORBIDDEN', message);
}

/**
 * Runs one of the port's methods, so that everything it rejects with is a
 * `ViewStoreError`: a failed request as its code says, anything else — a
 * body that did not parse — as `UNAVAILABLE`, the outcome unknown.
 */
async function guard<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (thrown) {
    if (thrown instanceof Failure) throw thrown.toStoreError();
    if (isViewStoreError(thrown)) throw thrown;
    // A body that did not parse came back from something — a login wall's
    // page, a proxy's: answered, so not "could not be reached".
    throw new ViewStoreError(
      'UNAVAILABLE',
      thrown instanceof Error ? thrown.message : String(thrown),
      {
        cause: thrown,
        ...(thrown instanceof SyntaxError ? { reachable: true as const } : {}),
      },
    );
  }
}

/**
 * How many system views the store remembers the version of; one it forgot
 * is read again before its write.
 */
const REMEMBERED_SYSTEM_VIEWS = 1024;

/** A bounded memory by key: the oldest entry goes first. */
class Remembered<T> {
  private readonly entries = new Map<string, T>();

  constructor(private readonly limit = REMEMBERED_WRITES) {}

  get(key: string): T | undefined {
    return this.entries.get(key);
  }

  has(key: string): boolean {
    return this.entries.has(key);
  }

  set(key: string, value: T): void {
    this.entries.delete(key);
    this.entries.set(key, value);
    if (this.entries.size > this.limit)
      this.entries.delete(this.entries.keys().next().value!);
  }
}
