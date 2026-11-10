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

import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  asc,
  cursorQuery,
  filter,
  singleQuery,
} from '@ahoo-wang/wow-client/dsl';
import type { CommandOptions, WowClientOptions } from '@ahoo-wang/wow-client';
import {
  emptyPreferences,
  isSystemInstanceId,
  ViewStoreError,
  type ViewAudience,
  type ViewConfig,
  type ViewInstance,
  type ViewInstanceSummary,
  type ViewListOptions,
  type ViewListPage,
  type ReadOptions,
  type ViewPermissions,
  type ViewPreferences,
  type ViewStore,
  type WriteOptions,
} from '@ahoo-wang/wow-view-engine';
import type { Place } from './paths.js';
import { Remembered } from './remembered.js';
import {
  isStored,
  preferencesAt,
  preferencesInput,
  systemSummary,
  toPreferences,
  viewInstance,
} from './views.js';
import { ViewCommandClient } from './generated/view-store/view/commandClient.js';
import {
  ViewSnapshotQueryClient,
  ViewStateClient,
} from './generated/view-store/view/queryClient.js';
import { ViewPreferencesCommandClient } from './generated/view-store/view_preferences/commandClient.js';
import { ViewPreferencesSnapshotQueryClient } from './generated/view-store/view_preferences/queryClient.js';
import { ViewStoreRoutes } from './generated/view-store/routes.js';
import type {
  SystemView,
  ViewEntry,
  ViewState,
} from './generated/view-store/types.js';
import { Failure, guard, request } from './errors.js';
import { versionOf, ViewWrites, type InstanceWrite } from './viewWrites.js';
import { scopeAt, SUMMARY_FIELDS, toSummary, type ViewRecord } from './wire.js';

/** How a V10 {@link WowViewStore} reaches the view store service. */
export interface WowViewStoreOptions {
  /**
   * The fetcher every request goes through, on the base URL that serves
   * `/view-store/…` (the V10 view store service, or a gateway in front of
   * it).
   *
   * Its interceptors carry who is asking; the store never does. The V10
   * service takes the tenant and the owner from the principal, so no path
   * names them: the interceptors send the authorization (CoSec's token, or
   * the headers the service's own caller resolver reads) and `CoSec-App-Id`,
   * the application every view, preference and wording is kept in. The
   * store adds `Wow-Scope` itself where it acts as a reserved owner: the
   * shared views' `(shared)`, and `(system)` of `(platform)` for stored
   * system views.
   */
  fetcher: Fetcher;
  /**
   * Which buttons are enabled for one definition's views. The service
   * authorizes each write; a host answers this by the roles it holds there —
   * `changeAudience` and the shared views' writes by the role that writes
   * the owner `(shared)` (`view-store.shared-writer` on the service as
   * shipped), `editSystem` by the one that writes `(system)`. Left out,
   * everything is allowed, as the port reads a store without `permissions`.
   */
  permissions?: (definitionId: string) => ViewPermissions;
  /**
   * The most views one page of `list` answers; 100, the service's default
   * cursor page limit over HTTP, when left out. A test sets it low to read
   * a list across pages, and across audiences, with few views.
   */
  pageSize?: number;
}

/** The views one page of `list` answers when the options say none. */
const LIST_PAGE = 100;

/** Where a list page starts: the audience being read, and its own cursor. */
interface ListAt {
  audience: 'system' | 'shared' | 'personal';
  cursor: string | null;
}

/** A list page's cursor: {@link ListAt} as base64url JSON, opaque to the engine. */
function listCursor(at: ListAt): string {
  return btoa(JSON.stringify(at))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

/** The page a cursor names; the first page without one. */
function readListCursor(cursor: string | null | undefined): ListAt {
  if (!cursor) return { audience: 'system', cursor: null };
  try {
    const at = JSON.parse(
      atob(cursor.replace(/-/g, '+').replace(/_/g, '/')),
    ) as ListAt;
    if (['system', 'shared', 'personal'].includes(at.audience)) return at;
  } catch {
    // Read as the malformed cursor below.
  }
  throw new ViewStoreError('INVALID', `Not a view list cursor: ${cursor}`);
}

/** How many system views' revisions the store remembers the version of. */
const REMEMBERED_SYSTEM_REVISIONS = 1024;

/**
 * The view engine's `ViewStore` over the **V10** view store service
 * (`platform/view-store` in the Wow repository): saved views and preferences
 * as two Wow V10 aggregates, reached through the clients generated from the
 * service's Manifest (`@ahoo-wang/wow-client`), and the system views and
 * the read by id through the service's own routes.
 *
 * **The owner is the audience.** A personal view is the caller's own; a
 * shared one belongs to the reserved owner `(shared)`, which the store acts
 * as with `Wow-Scope: owner="(shared)"`; a stored system view to `(system)`
 * of the tenant `(platform)`. The port names a view by id alone, so the
 * store remembers where it last saw each one; one read by id answers a
 * view wherever it is, the caller's own first, then a shared one, then a
 * system view (`GET /view-store/views/{id}`).
 * Sharing and claiming move a view to the other owner in place, id kept:
 * the store waits for the service's `ViewAudienceSaga` to move it.
 *
 * **Writes** carry the port's `requestId` as `Wow-Request-Id` and its
 * `revision` as `If-Match`, and wait for the snapshot. The service
 * deduplicates a request id, creates included, so a retry is answered with
 * the first attempt's outcome; the store answers the view read back at the
 * version that write left (`GET …/{id}/state?version=`). A stale revision
 * is `CONFLICT` carrying the view as it is now.
 *
 * **Errors** are read by the problem's code onto the port's codes, by the
 * HTTP status only for an answer without a code the store knows; a `404`
 * without a code is a route the service does not serve (`UNSUPPORTED`), and
 * a request that got no answer is `UNAVAILABLE`.
 */
export class WowViewStore implements ViewStore {
  /** The host's {@link WowViewStoreOptions.permissions}, when it gave any. */
  readonly permissions?: (definitionId: string) => ViewPermissions;
  private readonly commands: ViewCommandClient;
  private readonly snapshots: ViewSnapshotQueryClient;
  private readonly states: ViewStateClient;
  private readonly preferenceCommands: ViewPreferencesCommandClient;
  private readonly preferenceSnapshots: ViewPreferencesSnapshotQueryClient;
  private readonly routes: ViewStoreRoutes;
  /** Where each view was last seen, by id. */
  private readonly places = new Map<string, Place>();
  /**
   * The version of each stored system view at each revision the store has
   * seen it at (a hash of its content, while a write expects the version),
   * by `id` and revision; `null` for a configured one, which is read-only.
   * Keeping the old revisions too lets a retry send the version its first
   * attempt expected, which the service then answers as a duplicate.
   */
  private readonly systemVersions = new Remembered<number | null>(
    REMEMBERED_SYSTEM_REVISIONS,
  );

  /** See `WowViewStoreOptions.pageSize`. */
  private readonly pageSize: number;
  /** Every instance write, sent and read back; see `ViewWrites`. */
  private readonly writes: ViewWrites;

  constructor(options: WowViewStoreOptions) {
    this.pageSize = options.pageSize ?? LIST_PAGE;
    const client: WowClientOptions = { fetcher: options.fetcher };
    this.commands = new ViewCommandClient(client);
    this.snapshots = new ViewSnapshotQueryClient(client);
    this.states = new ViewStateClient(client);
    this.preferenceCommands = new ViewPreferencesCommandClient(client);
    this.preferenceSnapshots = new ViewPreferencesSnapshotQueryClient(client);
    this.routes = new ViewStoreRoutes(client);
    this.writes = new ViewWrites({
      states: this.states,
      places: this.places,
      read: (id, signal) => this.read(id, signal),
      systemVersion: (id, revision, signal) =>
        this.systemVersion(id, revision, signal),
      commandOptions: (place, context, more) =>
        this.commandOptions(place, context, more),
    });
    // Left undefined when the host declared none, which the port reads as
    // "everything is allowed".
    if (options.permissions) this.permissions = options.permissions;
  }

  /**
   * The definition's views a page at a time, in the port's order: the
   * service's system views first, whole, then the shared views, then the
   * caller's own, each read with the snapshot model's cursor, oldest first
   * (the service sorts by the time a view's first event was written, then
   * by its id). One call reads on into the next audience while its page has
   * room, so a definition with few views answers one page; the page's cursor
   * names the audience being read and that audience's own cursor.
   */
  list(
    definitionId: string,
    options: ViewListOptions = {},
  ): Promise<ViewListPage> {
    return guard(async () => {
      const { signal } = options;
      const items: ViewInstanceSummary[] = [];
      let at: ListAt | null = readListCursor(options.cursor);
      while (at !== null && items.length < this.pageSize) {
        const room = this.pageSize - items.length;
        const read = await this.listAudience(definitionId, at, room, signal);
        items.push(...read.items);
        at = read.next;
      }
      for (const summary of items) this.places.set(summary.id, summary.scope);
      return { items, nextCursor: at === null ? null : listCursor(at) };
    });
  }

  /** One read of one audience, and where the list goes on after it. */
  private async listAudience(
    definitionId: string,
    at: ListAt,
    size: number,
    signal: AbortSignal | undefined,
  ): Promise<{ items: ViewInstanceSummary[]; next: ListAt | null }> {
    if (at.audience === 'system') {
      const system = await request(() =>
        this.routes.systemViews({ definitionId }, { signal }),
      );
      for (const view of system) this.rememberSystem(view);
      return {
        items: system.map(systemSummary),
        next: { audience: 'shared', cursor: null },
      };
    }
    const page = await request(() =>
      this.snapshots.cursor(
        cursorQuery({
          filter: filter.eq('state.definitionId', definitionId),
          projection: { include: [...SUMMARY_FIELDS] },
          sort: [asc('firstEventTime')],
          size,
          cursor: at.cursor,
        }),
        at.audience === 'shared'
          ? { scope: scopeAt('shared'), signal }
          : { signal },
      ),
    );
    const items = page.list.map(record => toSummary(recordOf(record)));
    const next: ListAt | null =
      page.nextCursor !== null
        ? { audience: at.audience, cursor: page.nextCursor }
        : at.audience === 'shared'
          ? { audience: 'personal', cursor: null }
          : null;
    return { items, next };
  }

  /** View `id` wherever it is: the caller's, shared, or a system view. */
  get(id: string, options: ReadOptions = {}): Promise<ViewInstance> {
    return guard(async () => (await this.read(id, options.signal)).instance);
  }

  /**
   * Creates the view as the owner of its `scope`; the service generates the
   * id. A retry under the same request id is answered with the first
   * attempt's view, whichever store sends it: the service deduplicates it.
   */
  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    context: WriteOptions,
  ): Promise<ViewInstance> {
    return guard(async () => {
      const place: Place = input.scope;
      const { definitionId, title, config } = input;
      const write: InstanceWrite = {
        send: options =>
          this.commands.createView(
            { definitionId, title, config: bodyOf(config) },
            options,
          ),
        sentFrom: place,
        landsAt: place,
      };
      const result = await request(() =>
        write.send(this.commandOptions(place, context)),
      );
      return (await this.writes.landed(result.ref.id, write, result, context))!;
    });
  }

  /** Replaces the view's config, at the expected `revision`. */
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteOptions,
  ): Promise<ViewInstance> {
    return this.writes.write(id, revision, context, place => ({
      send: options =>
        this.commands.saveView(id, { config: bodyOf(config) }, options),
      sentFrom: place,
      landsAt: place,
    })) as Promise<ViewInstance>;
  }

  /** Renames the view (the service trims the title), at the expected `revision`. */
  rename(
    id: string,
    title: string,
    revision: string,
    context: WriteOptions,
  ): Promise<ViewInstance> {
    return this.writes.write(id, revision, context, place => ({
      send: options => this.commands.renameView(id, { title }, options),
      sentFrom: place,
      landsAt: place,
    })) as Promise<ViewInstance>;
  }

  /**
   * 设为共享 sends `share-view` as the caller, the view's owner; 设为个人
   * sends `claim-view` as `(shared)`, which the service admits only with
   * the role that writes the owner `(shared)`, and which makes the caller
   * the owner. Either waits for the move (`ViewAudienceSaga`). Each is sent
   * from the same owner whether or not the view has moved, so a retry is
   * answered as its first attempt's duplicate even after the move.
   *
   * Asked for the audience the view already has, the service answers the
   * command as one on a view out of that owner's reach, not found; the store
   * then answers the view as it is, revision unmoved, once the revision
   * matches.
   *
   * A shared view a shared dashboard shows stays shared: the service
   * refuses the claim and names the boards, and the refusal carries them by
   * **title** in `boards`, as they are stored.
   */
  changeAudience(
    id: string,
    audience: ViewAudience,
    revision: string,
    context: WriteOptions,
  ): Promise<ViewInstance> {
    return this.writes.write(id, revision, context, place => {
      if (place === 'system') readOnly('A system view never moves audience');
      return audience === 'shared'
        ? {
            send: options => this.commands.shareView(id, {}, options),
            sentFrom: 'personal',
            landsAt: 'shared',
            moves: true,
          }
        : {
            send: options => this.commands.claimView(id, {}, options),
            sentFrom: 'shared',
            landsAt: 'personal',
            moves: true,
          };
    }) as Promise<ViewInstance>;
  }

  /** Deletes the view, at the expected `revision`; a board showing it keeps a broken panel. */
  async delete(
    id: string,
    revision: string,
    context: WriteOptions,
  ): Promise<void> {
    await this.writes.write(id, revision, context, place => ({
      send: options => this.commands.deleteAggregate(id, options),
      sentFrom: place,
      landsAt: null,
    }));
  }

  /**
   * The caller's preferences of the definition, in the request's
   * application: the service derives their id from the owner, the
   * application and the definition. None written yet reads as the port's
   * empty preferences, at revision `'0'`.
   */
  getPreferences(
    definitionId: string,
    options: ReadOptions = {},
  ): Promise<ViewPreferences> {
    const { signal } = options;
    return guard(async () => {
      const record = await request(() =>
        this.preferenceSnapshots.single(
          singleQuery({
            filter: filter.eq('state.definitionId', definitionId),
          }),
          { signal },
        ),
      );
      // None written yet: the port's empty preferences.
      if (record === undefined) return emptyPreferences();
      return toPreferences({ ...record.state, version: record.version });
    });
  }

  /**
   * Writes the preferences at the expected `revision` (`'0'` for ones never
   * written, which `If-Match: "0"` says). A retry under the same request id
   * is answered as the first attempt's duplicate, at its version.
   */
  setPreferences(
    definitionId: string,
    preferences: ViewPreferences,
    context: WriteOptions,
  ): Promise<ViewPreferences> {
    return guard(async () => {
      const { requestId, signal } = context;
      try {
        const result = await request(() =>
          this.preferenceCommands.setViewPreferences(
            { definitionId, ...preferencesInput(preferences) },
            {
              requestId,
              expectedVersion: versionOf(preferences.revision),
              ...(signal === undefined ? {} : { signal }),
            },
          ),
        );
        return typeof result.version === 'number'
          ? preferencesAt(preferences, result.version)
          : await this.getPreferences(definitionId, { signal });
      } catch (thrown) {
        if (!(thrown instanceof Failure) || thrown.code !== 'CONFLICT')
          throw thrown;
        throw new ViewStoreError('CONFLICT', thrown.message, {
          ...thrown.held(),
          preferences: await this.getPreferences(definitionId, { signal }),
        });
      }
    });
  }

  /**
   * View `id` as it is now and where it is, in one read: the service answers
   * the caller's own view of that id, else a shared one, else a system view.
   */
  private async read(
    id: string,
    signal: AbortSignal | undefined,
  ): Promise<{ place: Place; instance: ViewInstance }> {
    // Ids in `system:` are the views a host declares in code; the service
    // never issues or serves one.
    if (isSystemInstanceId(id))
      throw new ViewStoreError('NOT_FOUND', `No such view: ${id}`);
    const entry = await request(() => this.routes.view(id, { signal }));
    const place: Place = entry.scope;
    this.places.set(id, place);
    if (place === 'system') this.rememberSystem(entry);
    return { place, instance: viewInstance(entry) };
  }

  /** Keeps the version of a stored system view at its revision; a configured one as read-only. */
  private rememberSystem(view: SystemView | ViewEntry): void {
    this.systemVersions.set(
      revisionKey(view.id, view.revision),
      isStored(view) ? view.version! : null,
    );
  }

  /**
   * The version a write of stored system view `id` at `revision` (its
   * content hash) expects: as seen at that revision, else as read now. A
   * configured system view is read-only (`FORBIDDEN`; a code one is never
   * found here); a revision other than the view's, or a view of that id
   * that is no longer a system view, is `CONFLICT`, carrying the view as it
   * is.
   */
  private async systemVersion(
    id: string,
    revision: string,
    signal: AbortSignal | undefined,
  ): Promise<number> {
    const key = revisionKey(id, revision);
    if (!this.systemVersions.has(key)) {
      const { instance } = await this.read(id, signal);
      if (instance.scope !== 'system')
        throw new ViewStoreError(
          'CONFLICT',
          `View ${id} is no longer a system view`,
          { instance },
        );
      if (instance.revision !== revision && instance.stored)
        throw new ViewStoreError(
          'CONFLICT',
          `System view ${id} has moved on from revision ${revision}`,
          { instance },
        );
    }
    const version = this.systemVersions.get(key);
    if (typeof version !== 'number')
      readOnly('Configured system views are read-only');
    return version;
  }

  /** The options of a command sent from `place`: its scope, request id and signal. */
  private commandOptions(
    place: Place,
    { requestId, signal }: WriteOptions,
    more: Partial<CommandOptions> = {},
  ): CommandOptions {
    const scope = scopeAt(place);
    return {
      ...(scope?.tenantId === undefined
        ? {}
        : { targetTenant: scope.tenantId }),
      ...(scope === undefined
        ? {}
        : { targetScope: { ownerId: scope.ownerId, spaceId: scope.spaceId } }),
      requestId,
      ...(signal === undefined ? {} : { signal }),
      ...more,
    };
  }
}

/** A snapshot record as the store reads it. */
function recordOf(record: {
  aggregateId: string;
  version: number;
  state: unknown;
}): ViewRecord {
  return {
    id: record.aggregateId,
    version: record.version,
    state: record.state as ViewState,
  };
}

/** A config as a command body holds it: free-form, the service stores it whole. */
function bodyOf(config: ViewConfig): Record<string, unknown> {
  return config as unknown as Record<string, unknown>;
}

function revisionKey(id: string, revision: string): string {
  return `${id}\u0000${revision}`;
}

/** Refuses a write to a system view the server or the code declares. */
function readOnly(message: string): never {
  throw new ViewStoreError('FORBIDDEN', message);
}
