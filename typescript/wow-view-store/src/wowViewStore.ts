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
import {
  type CommandOptions,
  type CommandResult,
  type WowClientOptions,
} from '@ahoo-wang/wow-client';
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
import { SHARED_OWNER_ID, SYSTEM_OWNER_ID, type Place } from './paths.js';
import { Remembered } from './remembered.js';
import {
  isStored,
  preferencesAt,
  preferencesInput,
  systemInstance,
  systemSummary,
  toPreferences,
  type SystemViewBody,
} from './views.js';
import {
  ViewStoreContract,
  viewStoreWait,
} from './generated/view-store/contract.js';
import { ViewCommandClient } from './generated/view-store/view/commandClient.js';
import {
  ViewSnapshotQueryClient,
  ViewStateClient,
} from './generated/view-store/view/queryClient.js';
import { ViewPreferencesCommandClient } from './generated/view-store/view_preferences/commandClient.js';
import { ViewPreferencesSnapshotQueryClient } from './generated/view-store/view_preferences/queryClient.js';
import type { ViewState } from './generated/view-store/types.js';
import { boardTitles, Failure, guard, request } from './errors.js';
import { SystemViewsClient } from './systemViews.js';
import {
  scopeAt,
  SUMMARY_FIELDS,
  toInstance,
  toSummary,
  type ViewRecord,
} from './wire.js';

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
}

/**
 * The views one page of `list` reads of an audience: the service's default
 * cursor page limit over HTTP.
 */
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
 * What a share or a claim waits for: the saga that moves the view to its
 * new owner, then that move's snapshot. The view's own commit changes its
 * audience; the owner, which reads and writes go by, changes one version
 * later (`ViewAudienceSaga`).
 */
const AUDIENCE_WAIT = [
  viewStoreWait.handled('ViewAudienceSaga.onAudienceChanged'),
  viewStoreWait.snapshot(),
];

/** One instance write, as sent from the place the view is at. */
interface InstanceWrite {
  /** Sends it with the options the store fills. */
  send(options: CommandOptions): Promise<CommandResult>;
  /** The place whose `Wow-Scope` it is sent with. */
  sentFrom: Place;
  /** Where the view is once it lands; `null` for a delete. */
  landsAt: Place | null;
  /** A share or a claim: it moves the view, waiting for the move. */
  moves?: boolean;
}

/**
 * The view engine's `ViewStore` over the **V10** view store service
 * (`platform/view-store` in the Wow repository): saved views and preferences
 * as two Wow V10 aggregates, reached through the clients generated from the
 * service's Manifest (`@ahoo-wang/wow-client`), and the system views
 * through the service's own route.
 *
 * **The owner is the audience.** A personal view is the caller's own; a
 * shared one belongs to the reserved owner `(shared)`, which the store acts
 * as with `Wow-Scope: owner="(shared)"`; a stored system view to `(system)`
 * of the tenant `(platform)`. The port names a view by id alone, so the
 * store remembers where it last saw each one and looks an unknown id up as
 * the caller, as `(shared)` and among the system views, in that order.
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
  private readonly systemViews: SystemViewsClient;
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

  constructor(options: WowViewStoreOptions) {
    const client: WowClientOptions = { fetcher: options.fetcher };
    this.commands = new ViewCommandClient(client);
    this.snapshots = new ViewSnapshotQueryClient(client);
    this.states = new ViewStateClient(client);
    this.preferenceCommands = new ViewPreferencesCommandClient(client);
    this.preferenceSnapshots = new ViewPreferencesSnapshotQueryClient(client);
    this.systemViews = new SystemViewsClient(options.fetcher);
    // Left undefined when the host declared none, which the port reads as
    // "everything is allowed".
    if (options.permissions) this.permissions = options.permissions;
  }

  /**
   * The definition's views a page at a time, in the port's order: the
   * service's system views first, whole, then the shared views, then the
   * caller's own, each read with the snapshot model's cursor, oldest first
   * (the service sorts by the time a view's first event was written, then
   * by its id). The page's cursor names the audience being read and that
   * audience's own cursor; an audience read to its end moves on to the
   * next.
   */
  list(
    definitionId: string,
    options: ViewListOptions = {},
  ): Promise<ViewListPage> {
    return guard(async () => {
      const { signal } = options;
      const at = readListCursor(options.cursor);
      if (at.audience === 'system') {
        const system = await this.systemViews.list(definitionId, signal);
        for (const view of system) this.rememberSystem(view);
        const items = system.map(systemSummary);
        for (const summary of items) this.places.set(summary.id, summary.scope);
        return {
          items,
          nextCursor: listCursor({ audience: 'shared', cursor: null }),
        };
      }
      const page = await request(() =>
        this.snapshots.cursor(
          cursorQuery({
            filter: filter.eq('state.definitionId', definitionId),
            projection: { include: [...SUMMARY_FIELDS] },
            sort: [asc('firstEventTime')],
            size: LIST_PAGE,
            cursor: at.cursor,
          }),
          at.audience === 'shared'
            ? { scope: scopeAt('shared'), signal }
            : { signal },
        ),
      );
      const items = page.list.map(record => toSummary(recordOf(record)));
      for (const summary of items) this.places.set(summary.id, summary.scope);
      const nextCursor =
        page.nextCursor !== null
          ? listCursor({ audience: at.audience, cursor: page.nextCursor })
          : at.audience === 'shared'
            ? listCursor({ audience: 'personal', cursor: null })
            : null;
      return { items, nextCursor };
    });
  }

  /** View `id` wherever it is: the caller's, shared, or a system view. */
  get(id: string, options: ReadOptions = {}): Promise<ViewInstance> {
    return guard(async () => (await this.find(id, options.signal)).instance);
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
      return (await this.landed(result.ref.id, write, result, context))!;
    });
  }

  /** Replaces the view's config, at the expected `revision`. */
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    context: WriteOptions,
  ): Promise<ViewInstance> {
    return this.write(id, revision, context, place => ({
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
    return this.write(id, revision, context, place => ({
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
   * Asked for the audience the view already has, the service refuses the
   * command as outside that owner's scope; the store then answers the view
   * as it is, revision unmoved, once the revision matches.
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
    return this.write(id, revision, context, place => {
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
    await this.write(id, revision, context, place => ({
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
   * One instance write: to the place the view is at, then the answer read
   * back. A view remembered at a place it has since left (another tab shared
   * it) is looked up again and the write sent once more from where it is.
   * A move refused because the view already has the audience it asks for
   * changes nothing: the view is answered as it is, once its revision
   * matches.
   */
  private write(
    id: string,
    revision: string,
    context: WriteOptions,
    plan: (place: Place) => InstanceWrite,
  ): Promise<ViewInstance | undefined> {
    return guard(async () => {
      const { signal } = context;
      const remembered = this.places.get(id);
      let place = remembered ?? (await this.find(id, signal)).place;
      let relocated = remembered === undefined;
      for (;;) {
        const write = plan(place);
        const options = this.commandOptions(write.sentFrom, context, {
          expectedVersion:
            place === 'system'
              ? await this.systemVersion(id, revision, signal)
              : versionOf(revision),
          ...(write.moves ? { wait: AUDIENCE_WAIT } : {}),
        });
        let result: CommandResult;
        try {
          result = await request(() => write.send(options));
        } catch (thrown) {
          if (!(thrown instanceof Failure)) throw thrown;
          if (
            write.moves &&
            place === write.landsAt &&
            thrown.code === 'FORBIDDEN'
          )
            return this.unchanged(id, revision, place, signal);
          if (
            !relocated &&
            (thrown.code === 'NOT_FOUND' || thrown.code === 'FORBIDDEN')
          ) {
            relocated = true;
            const found = (await this.find(id, signal, null)).place;
            if (found !== place) {
              place = found;
              continue;
            }
          }
          if (thrown.code === 'CONFLICT')
            throw await this.conflict(id, thrown, signal);
          throw refusal(thrown);
        }
        // Landed: what goes wrong reading it back is never a reason to send
        // it again.
        return this.landed(id, write, result, context);
      }
    });
  }

  /**
   * A landed write's answer: the view read back at the version it left
   * (`GET …/{id}/state?version=`). A system view's revision is its content
   * hash, so it is read from its route as it is now.
   *
   * A move is two commits: the view's own (its audience), then the saga's
   * (its owner). Waited for, the result's version is the second; a
   * duplicate answers the first, so the move is read one version later,
   * where the owner it names must be the one it moved to. Anything else
   * (another writer between the two) answers the view as it is now.
   */
  private async landed(
    id: string,
    write: InstanceWrite,
    result: CommandResult,
    context: WriteOptions,
  ): Promise<ViewInstance | undefined> {
    const { landsAt } = write;
    if (landsAt === null) return undefined;
    this.places.set(id, landsAt);
    const { signal } = context;
    const read = typeof result.version === 'number' ? result.version : null;
    if (landsAt !== 'system' && read !== null)
      try {
        const version = write.moves && result.duplicate ? read + 1 : read;
        const view = await request(() =>
          this.states.get(id, {
            version,
            ...optionsAt(landsAt),
            ...(signal === undefined ? {} : { signal }),
          }),
        );
        if (!write.moves || ownedAt(view.scope.ownerId) === landsAt)
          return toInstance({ id, version: view.version, state: view.state });
      } catch {
        // Moved on since (another tab moved it): answered as it is.
      }
    return (await this.find(id, signal, landsAt)).instance;
  }

  /** A move to where the view already is: the view as it is, at `revision`. */
  private async unchanged(
    id: string,
    revision: string,
    place: Place,
    signal: AbortSignal | undefined,
  ): Promise<ViewInstance> {
    const { instance } = await this.find(id, signal, place);
    if (instance.revision !== revision)
      throw new ViewStoreError(
        'CONFLICT',
        `View ${id} has moved on from revision ${revision}`,
        { instance },
      );
    return instance;
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
   * View `id` and where it is: first where it was last seen (`first`, `null`
   * to ignore that), then as the caller, as `(shared)` and among the system
   * views.
   */
  private async find(
    id: string,
    signal: AbortSignal | undefined,
    first: Place | null | undefined = this.places.get(id),
  ): Promise<{ place: Place; instance: ViewInstance }> {
    // Ids in `system:` are the views a host declares in code; the service
    // never issues or serves one.
    if (!isSystemInstanceId(id)) {
      const order: Place[] = ['personal', 'shared', 'system'];
      if (first)
        order.sort((a, b) => Number(b === first) - Number(a === first));
      for (const place of order) {
        try {
          const instance = await this.readAt(place, id, signal);
          if (instance === undefined) continue;
          this.places.set(id, place);
          return { place, instance };
        } catch (thrown) {
          if (thrown instanceof Failure && thrown.code === 'NOT_FOUND')
            continue;
          throw thrown;
        }
      }
    }
    throw new ViewStoreError('NOT_FOUND', `No such view: ${id}`);
  }

  /**
   * The view as it is now at `place`: `undefined` when no stored view has
   * that id there, a `NOT_FOUND` failure when the system views lack it.
   */
  private async readAt(
    place: Place,
    id: string,
    signal: AbortSignal | undefined,
  ): Promise<ViewInstance | undefined> {
    if (place === 'system')
      return systemInstance(
        this.rememberSystem(await this.systemViews.one(id, signal)),
      );
    const record = await request(() =>
      this.snapshots.single(singleQuery({ filter: filter.id(id) }), {
        ...optionsAt(place),
        ...(signal === undefined ? {} : { signal }),
      }),
    );
    return record === undefined ? undefined : toInstance(recordOf(record));
  }

  /** Keeps the version of a stored system view at its revision; a configured one as read-only. */
  private rememberSystem(view: SystemViewBody): SystemViewBody {
    this.systemVersions.set(
      revisionKey(view.id, view.revision),
      isStored(view) ? view.version! : null,
    );
    return view;
  }

  /**
   * The version a write of stored system view `id` at `revision` (its
   * content hash) expects: as seen at that revision, else as read now. A
   * configured system view is read-only (`FORBIDDEN`; a code one is never
   * found here); a revision other than the view's is `CONFLICT`, carrying
   * the view as it is.
   */
  private async systemVersion(
    id: string,
    revision: string,
    signal: AbortSignal | undefined,
  ): Promise<number> {
    const key = revisionKey(id, revision);
    if (!this.systemVersions.has(key)) {
      const instance = await this.readAt('system', id, signal);
      if (instance === undefined)
        throw new ViewStoreError('NOT_FOUND', `No such view: ${id}`);
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

/** The port's list order: system views first, then shared, then personal. */
const AUDIENCE_RANK: Readonly<Record<ViewInstanceSummary['scope'], number>> = {
  system: 0,
  shared: 1,
  personal: 2,
};

/** The request options of `place`: its `Wow-Scope`, when it has one. */
function optionsAt(place: Place): { scope?: ReturnType<typeof scopeAt> } {
  const scope = scopeAt(place);
  return scope === undefined ? {} : { scope };
}

/** The place of a view its owner says: a reserved owner, or a user's own. */
function ownedAt(ownerId: string | null | undefined): Place {
  return ownerId === SHARED_OWNER_ID
    ? 'shared'
    : ownerId === SYSTEM_OWNER_ID
      ? 'system'
      : 'personal';
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

/** The aggregate version a revision of this store names. */
function versionOf(revision: string): number {
  const version = Number(revision);
  if (!Number.isInteger(version) || version < 0)
    throw new ViewStoreError(
      'CONFLICT',
      `Not a revision of this store: ${revision}`,
    );
  return version;
}

function revisionKey(id: string, revision: string): string {
  return `${id}\u0000${revision}`;
}

/** Refuses a write to a system view the server or the code declares. */
function readOnly(message: string): never {
  throw new ViewStoreError('FORBIDDEN', message);
}

/**
 * The port's refusal of a write. A claim the service refuses because shared
 * dashboards show the view carries those boards' titles (`boards`), read
 * from the problem's `boards` extension member; the engine says the refusal
 * around them.
 */
function refusal(failure: Failure): ViewStoreError {
  if (
    failure.problemCode !==
    ViewStoreContract.Errors.REFERENCED_BY_SHARED_DASHBOARD
  )
    return failure.toStoreError();
  return new ViewStoreError('INVALID', failure.message, {
    ...failure.held(),
    boards: boardTitles(failure.problem?.problem.extensions),
  });
}
