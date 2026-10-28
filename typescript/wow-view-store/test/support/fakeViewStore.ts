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

/**
 * An in-memory stand-in for the **V10** view store service
 * (`platform/view-store/view-store-server`), as a `fetch`: the routes the
 * Manifest lists for `view`, `view_preferences` and `dataset_wording`, the
 * service's own system views route, and optionally a service's datasets.
 * The suites of `@ahoo-wang/wow-view-store` run the real store and the
 * real generated clients over it, and the Storybook's V10 stories install it
 * as the page's `fetch` (it imports nothing but the platform,
 * `@ahoo-wang/wow-client`'s wire and the store's reserved ids, so a browser
 * runs it as it is).
 *
 * It answers as the service does, as far as the store can tell: the callers
 * the host gives a development run ({@link DevelopmentCallerHeaders}, and
 * `Wow-Scope` naming `(shared)` or `(system)` of `(platform)`), the write
 * rules of `ViewStoreAuthorizer`, request ids deduplicated before the
 * expected version is checked, `If-Match`, the share and claim moved by the
 * saga one version later, the shared-dashboard refusal said in the
 * problem's detail, problem+json with the service's codes, and a 404
 * without a code for a route it does not serve. The real service is held to
 * the same behaviour by `typescript/integration-test/test/view-store/`.
 */

import {
  SHARED_OWNER_ID,
  SYSTEM_OWNER_ID,
  SYSTEM_TENANT_ID,
} from '../../src/paths.js';
import { DevelopmentCallerHeaders, WowHeaders } from '@ahoo-wang/wow-client';
import {
  commandResultOf,
  problemOf,
  problemResponse,
} from '@ahoo-wang/wow-client/testing';
import { FilterOperator, SortDirection } from '@ahoo-wang/wow-client/dsl';

/** A configured system view (`wow.view-store.system-views[n]`). */
export interface ConfiguredSystemView {
  id: string;
  definitionId: string;
  title: string;
  config: Record<string, unknown>;
  /** Blank: every tenant. */
  tenantId?: string;
  /** Blank: every application. */
  appId?: string;
}

/**
 * A dataset the fake serves on `/wow/dataset/{context}/{dataset}/query`: a
 * source answering in wow-client's shapes, as the engine's `ViewSource` does
 * (the view engine's `memorySource`, or a query client), which the fake
 * answers on the V10 wire.
 */
export interface FakeDataset {
  context: string;
  dataset: string;
  title?: string;
  source: {
    paged(query: object): Promise<{
      readonly total: number | null;
      readonly exact?: boolean;
      readonly list: readonly unknown[];
    }>;
    cursor(query: object): Promise<{
      readonly list: readonly unknown[];
      readonly nextCursor: string | null;
    }>;
    aggregate(query: object): Promise<readonly unknown[]>;
    describe?(): Promise<{
      readonly notModified: boolean;
      readonly descriptor?: { readonly version: string };
    }>;
  };
}

export interface FakeViewStoreOptions {
  systemViews?: readonly ConfiguredSystemView[];
  datasets?: readonly FakeDataset[];
  /** The service's clock, for `firstEventTime`; `Date.now` by default. */
  now?: () => number;
}

/** One request as the fake saw it. */
export interface SeenRequest {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: unknown;
}

export interface FakeViewStore {
  /** A `fetch` that answers as the service. */
  fetch: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
  /** Every request received, in order. */
  requests: SeenRequest[];
  /** Answers the next request whose path matches with `answer` instead, once. */
  failNext(path: RegExp, answer: () => Response): void;
}

type AggregateName = 'view' | 'view_preferences' | 'dataset_wording';
type State = Record<string, unknown>;

interface Version {
  version: number;
  ownerId: string | null;
  deleted: boolean;
  state: State;
}

interface Aggregate {
  name: AggregateName;
  tenantId: string;
  id: string;
  ownerId: string | null;
  version: number;
  deleted: boolean;
  firstEventTime: number;
  lastEventTime: number;
  state: State;
  history: Version[];
}

interface Caller {
  tenantId: string;
  ownerId: string | null;
  user: string | null;
  roles: string[];
  app: string | null;
  /** Acting as a reserved owner through `Wow-Scope`. */
  reserved: boolean;
}

const SHARED = SHARED_OWNER_ID;
const SYSTEM = SYSTEM_OWNER_ID;
const PLATFORM = SYSTEM_TENANT_ID;
const SHARED_WRITER = 'view-store.shared-writer';
const SYSTEM_WRITER = 'view-store.system-writer';
const SAGA = 'ViewAudienceSaga.onAudienceChanged';
const KINDS = ['record', 'analysis', 'dashboard'];
/** `ViewConfigs.MAX_TITLE_LENGTH` and `MAX_CONFIG_BYTES` of the service. */
const MAX_TITLE = 120;
const MAX_CONFIG_BYTES = 240 * 1024;

class Answer {
  constructor(readonly response: Response) {}
}

function json(
  value: unknown,
  status = 200,
  headers: HeadersInit = {},
): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

/** A problem as the service writes it; a code-less one is only HTTP's. */
export function problem(
  status: number,
  code: string | undefined,
  detail: string,
  extra: Record<string, unknown> = {},
): Response {
  return problemResponse(
    problemOf(status, code, {
      title: code ?? 'Not Found',
      detail,
      extensions: extra,
    }),
  );
}

function fail(
  status: number,
  code: string | undefined,
  detail: string,
  extra?: Record<string, unknown>,
): never {
  throw new Answer(problem(status, code, detail, extra));
}

/** The value at a dotted path of a record. */
function at(record: unknown, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (node, key) =>
        node !== null && typeof node === 'object'
          ? (node as Record<string, unknown>)[key]
          : undefined,
      record,
    );
}

function setAt(target: Record<string, unknown>, path: string, value: unknown) {
  const keys = path.split('.');
  let node = target;
  for (const key of keys.slice(0, -1))
    node = (node[key] ??= {}) as Record<string, unknown>;
  node[keys[keys.length - 1]] = value;
}

interface Filter {
  op: string;
  field?: string;
  value?: unknown;
  children?: Filter[];
}

function matches(record: Record<string, unknown>, filter: Filter): boolean {
  switch (filter.op) {
    case FilterOperator.MATCH_ALL:
      return true;
    case FilterOperator.AND:
      return (filter.children ?? []).every(child => matches(record, child));
    case FilterOperator.ID:
      return record.aggregateId === filter.value;
    case FilterOperator.EQ:
      return at(record, filter.field!) === filter.value;
    default:
      return fail(400, 'wow.query.invalid', `The fake serves no ${filter.op}.`);
  }
}

/** A short hash of text, as the service's content revision of a system view. */
/** A cursor query's position after `record`: opaque to the client. */
function cursorOf(record: Record<string, unknown>): string {
  return `after:${String(record.aggregateId)}`;
}

function hash(text: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < text.length; i++) {
    h1 = Math.imul(h1 ^ text.charCodeAt(i), 0x01000193);
    h2 = Math.imul(h2 ^ text.charCodeAt(i), 0x5bd1e995);
  }
  return [h1, h2].map(h => (h >>> 0).toString(16).padStart(8, '0')).join('');
}

export function fakeViewStore(
  options: FakeViewStoreOptions = {},
): FakeViewStore {
  const now = options.now ?? Date.now;
  const aggregates = new Map<string, Aggregate>();
  const answered = new Map<string, Record<string, unknown>>();
  const requests: SeenRequest[] = [];
  const failures: { path: RegExp; answer: () => Response }[] = [];
  let sequence = 0;
  let clock = 0;

  const keyOf = (name: AggregateName, tenantId: string, id: string) =>
    `${name}\u0000${tenantId}\u0000${id}`;
  const tick = () => Math.max(now(), clock + 1);

  function callerOf(headers: Headers): Caller {
    const user = headers.get(DevelopmentCallerHeaders.USER)?.trim() || null;
    // Without a user, the anonymous caller: the other identity headers are
    // ignored, the application kept.
    const tenant =
      (user && headers.get(DevelopmentCallerHeaders.TENANT)?.trim()) || '(0)';
    const owner =
      (user && headers.get(DevelopmentCallerHeaders.OWNER)?.trim()) || null;
    const roles = user
      ? (headers.get(DevelopmentCallerHeaders.ROLES) ?? '')
          .split(',')
          .map(role => role.trim())
          .filter(Boolean)
      : [];
    const app = headers.get(DevelopmentCallerHeaders.APP)?.trim() || null;
    const scope = headers.get(WowHeaders.SCOPE);
    if (scope !== null) {
      const members = Object.fromEntries(
        [...scope.matchAll(/(\w+)="((?:[^"\\]|\\.)*)"/g)].map(m => [
          m[1],
          m[2],
        ]),
      );
      const names = Object.keys(members).sort().join(',');
      if (names === 'owner' && members.owner === SHARED)
        return {
          tenantId: tenant,
          ownerId: SHARED,
          user,
          roles,
          app,
          reserved: true,
        };
      if (
        names === 'owner,tenant' &&
        members.owner === SYSTEM &&
        members.tenant === PLATFORM
      )
        return {
          tenantId: PLATFORM,
          ownerId: SYSTEM,
          user,
          roles,
          app,
          reserved: true,
        };
      fail(403, 'wow.auth.forbidden', 'The caller may not act in that scope.');
    }
    return {
      tenantId: tenant,
      ownerId: owner,
      user,
      roles,
      app,
      reserved: false,
    };
  }

  function authorize(caller: Caller, name: AggregateName, command: string) {
    const role =
      name === 'dataset_wording' || caller.ownerId === SHARED
        ? SHARED_WRITER
        : caller.ownerId === SYSTEM
          ? SYSTEM_WRITER
          : undefined;
    if (role !== undefined && !caller.roles.includes(role))
      fail(
        403,
        'wow.auth.forbidden',
        `${caller.user ?? 'An anonymous caller'} may not view-store.${name}.${command}: it needs ${role}.`,
      );
  }

  function result(
    aggregate: { name: AggregateName; tenantId: string; id: string },
    requestId: string,
    version: number | null,
    duplicate = false,
  ): Record<string, unknown> {
    const commandId = `command-${++sequence}`;
    const ref = {
      type: { context: 'view-store', name: aggregate.name },
      tenantId: aggregate.tenantId,
      id: aggregate.id,
    };
    return commandResultOf(ref, {
      commandId,
      requestId,
      hop: {
        stage: 'handled',
        context: 'view-store',
        subscription: 'snapshot',
      },
      function: {
        kind: 'snapshot',
        context: 'view-store',
        processor: 'snapshot',
        name: 'snapshot',
      },
      version,
      duplicate,
      signalTime: now(),
    }) as unknown as Record<string, unknown>;
  }

  function reject(
    status: number,
    code: string,
    detail: string,
    ref: { name: AggregateName; tenantId: string; id: string },
    requestId: string,
    extensions: Record<string, unknown> = {},
  ): never {
    const rejected = {
      ...result(ref, requestId, null),
      status: 'rejected',
      error: { code, message: detail, extensions },
    };
    // As the service: each extension is a member of the problem (D211).
    fail(status, code, detail, { ...extensions, result: rejected });
  }

  function commit(
    aggregate: Aggregate,
    state: State,
    ownerId = aggregate.ownerId,
  ) {
    aggregate.version += 1;
    aggregate.state = state;
    aggregate.ownerId = ownerId;
    clock = tick();
    aggregate.lastEventTime = clock;
    aggregate.history.push({
      version: aggregate.version,
      ownerId,
      deleted: aggregate.deleted,
      state: structuredClone(state),
    });
  }

  function create(
    name: AggregateName,
    tenantId: string,
    id: string,
    ownerId: string | null,
  ): Aggregate {
    clock = tick();
    const aggregate: Aggregate = {
      name,
      tenantId,
      id,
      ownerId,
      version: 0,
      deleted: false,
      firstEventTime: clock,
      lastEventTime: clock,
      state: {},
      history: [],
    };
    aggregates.set(keyOf(name, tenantId, id), aggregate);
    return aggregate;
  }

  function recordOf(aggregate: Aggregate): Record<string, unknown> {
    return {
      tenantId: aggregate.tenantId,
      aggregateId: aggregate.id,
      version: aggregate.version,
      ownerId: aggregate.ownerId,
      spaceId: null,
      deleted: aggregate.deleted,
      tags: {},
      firstEventTime: aggregate.firstEventTime,
      lastEventTime: aggregate.lastEventTime,
      operator: null,
      firstOperator: null,
      schedules: {},
      state: structuredClone(aggregate.state),
    };
  }

  /** What a write needs before it is decided: admission, dedup, the aggregate. */
  function admit(
    name: AggregateName,
    command: string,
    caller: Caller,
    headers: Headers,
    id: string,
  ): {
    requestId: string;
    duplicate?: Record<string, unknown>;
    aggregate?: Aggregate;
    expected?: number;
  } {
    if (command === 'wow.lifecycle.TransferOwner')
      fail(
        403,
        'wow.auth.forbidden',
        "A view's owner changes only by sharing or claiming it.",
      );
    authorize(caller, name, command);
    const requestId =
      headers.get(WowHeaders.REQUEST_ID) ?? `request-${++sequence}`;
    const first = answered.get(`${caller.tenantId}\u0000${requestId}`);
    if (first) {
      if (
        first.command !== command ||
        (first.id !== undefined && first.id !== id && id !== '')
      )
        fail(
          409,
          'wow.command.request-id-reused',
          `Request ${requestId} was used by another command.`,
        );
      return { requestId, duplicate: first };
    }
    const match = headers.get('If-Match');
    const expected =
      match === null ? undefined : Number(match.replace(/"/g, ''));
    return {
      requestId,
      aggregate: aggregates.get(keyOf(name, caller.tenantId, id)),
      expected,
    };
  }

  function remember(
    caller: Caller,
    requestId: string,
    command: string,
    answer: Record<string, unknown>,
  ) {
    answered.set(`${caller.tenantId}\u0000${requestId}`, {
      ...answer,
      command,
      id: (answer.ref as { id: string }).id,
    });
  }

  function duplicateOf(first: Record<string, unknown>): Response {
    const { command: _command, id: _id, firstVersion, ...rest } = first;
    void _command;
    void _id;
    return json({
      ...rest,
      commandId: `command-${++sequence}`,
      duplicate: true,
      version: firstVersion ?? rest.version,
    });
  }

  /** The aggregate a command acts on, as the kernel admits it. */
  function existing(
    name: AggregateName,
    caller: Caller,
    aggregate: Aggregate | undefined,
    id: string,
    expected: number | undefined,
  ): Aggregate {
    if (!aggregate)
      fail(
        404,
        'wow.aggregate.not-found',
        `view-store.${name}/${caller.tenantId}/${id} does not exist.`,
      );
    if (aggregate.deleted)
      fail(
        410,
        'wow.aggregate.deleted',
        `view-store.${name}/${id} is deleted.`,
      );
    if (name !== 'dataset_wording' && aggregate.ownerId !== caller.ownerId)
      fail(
        403,
        'wow.aggregate.forbidden',
        `view-store.${name}/${id} is outside the caller's scope.`,
      );
    if (expected !== undefined && expected !== aggregate.version)
      fail(
        412,
        'wow.aggregate.version-mismatch',
        `view-store.${name}/${caller.tenantId}/${id} is at version ${aggregate.version}, not ${expected}.`,
      );
    return aggregate;
  }

  function appOf(
    caller: Caller,
    ref: { name: AggregateName; tenantId: string; id: string },
    requestId: string,
  ): string {
    if (!caller.app)
      reject(
        400,
        'view-store.app-required',
        'The request carries no CoSec-App-Id.',
        ref,
        requestId,
      );
    return caller.app!;
  }

  function invalid(
    problemText: string | undefined,
    ref: { name: AggregateName; tenantId: string; id: string },
    requestId: string,
  ) {
    if (problemText)
      reject(400, 'view-store.invalid', problemText, ref, requestId);
  }

  function configProblem(config: unknown): string | undefined {
    if (config === null || typeof config !== 'object')
      return "A view's config must be an object.";
    if (!KINDS.includes((config as State).kind as string))
      return "A view's config must say its kind.";
    const bytes = new TextEncoder().encode(JSON.stringify(config)).length;
    if (bytes > MAX_CONFIG_BYTES)
      return `A view's config is ${bytes} bytes; the limit is ${MAX_CONFIG_BYTES}.`;
    return undefined;
  }

  function titleProblem(title: unknown): string | undefined {
    if (typeof title !== 'string' || title.trim() === '')
      return "A view's title must not be blank.";
    if (title.trim().length > MAX_TITLE)
      return `A view's title is longer than ${MAX_TITLE} characters.`;
    return undefined;
  }

  function audienceOf(ownerId: string): string {
    return ownerId === SHARED
      ? 'shared'
      : ownerId === SYSTEM
        ? 'system'
        : 'personal';
  }

  function viewCommand(
    command: string,
    id: string,
    caller: Caller,
    headers: Headers,
    body: State,
  ): Response {
    const admitted = admit('view', command, caller, headers, id);
    if (admitted.duplicate) return duplicateOf(admitted.duplicate);
    const { requestId, expected } = admitted;
    const waitsForMove = (headers.get(WowHeaders.WAIT) ?? '').includes(SAGA);
    if (command === 'CreateView') {
      const viewId = id || `view-${++sequence}`;
      const ref = {
        name: 'view' as const,
        tenantId: caller.tenantId,
        id: viewId,
      };
      if (aggregates.has(keyOf('view', caller.tenantId, viewId)))
        fail(409, 'wow.aggregate.exists', `view-store.view/${viewId} exists.`);
      const app = appOf(caller, ref, requestId);
      if (!caller.ownerId)
        reject(
          400,
          'view-store.owner-required',
          'A view is saved by an owner: the caller acts as none.',
          ref,
          requestId,
        );
      invalid(
        (typeof body.definitionId !== 'string' ||
        body.definitionId.trim() === ''
          ? "A view's definitionId must not be blank."
          : undefined) ??
          titleProblem(body.title) ??
          configProblem(body.config),
        ref,
        requestId,
      );
      const aggregate = create('view', caller.tenantId, viewId, caller.ownerId);
      commit(aggregate, {
        definitionId: body.definitionId,
        title: (body.title as string).trim(),
        kind: (body.config as State).kind,
        audience: audienceOf(caller.ownerId!),
        appId: app,
        config: body.config,
      });
      const answer = result(ref, requestId, aggregate.version);
      remember(caller, requestId, command, answer);
      return json(answer);
    }
    const aggregate = existing(
      'view',
      caller,
      admitted.aggregate,
      id,
      expected,
    );
    const ref = aggregate;
    const app = appOf(caller, ref, requestId);
    if (aggregate.state.appId !== app)
      reject(
        404,
        'view-store.not-found',
        `${id} is not found.`,
        ref,
        requestId,
      );
    const state = structuredClone(aggregate.state);
    let firstVersion: number | undefined;
    switch (command) {
      case 'SaveView':
        invalid(configProblem(body.config), ref, requestId);
        commit(aggregate, {
          ...state,
          kind: (body.config as State).kind,
          config: body.config,
        });
        break;
      case 'RenameView':
        invalid(titleProblem(body.title), ref, requestId);
        if ((body.title as string).trim() !== state.title)
          commit(aggregate, { ...state, title: (body.title as string).trim() });
        break;
      case 'ShareView':
        if (state.audience === 'system')
          reject(
            403,
            'view-store.system-view-read-only',
            'A system view never moves audience.',
            ref,
            requestId,
          );
        if (state.audience === 'personal') {
          commit(aggregate, { ...state, audience: 'shared' });
          firstVersion = aggregate.version;
          commit(aggregate, aggregate.state, SHARED);
        }
        break;
      case 'ClaimView': {
        if (state.audience === 'system')
          reject(
            403,
            'view-store.system-view-read-only',
            'A system view never moves audience.',
            ref,
            requestId,
          );
        const to = caller.user;
        if (!to || to.startsWith('('))
          reject(
            400,
            'view-store.owner-required',
            'A view is claimed by a signed-in user.',
            ref,
            requestId,
          );
        if (state.audience === 'personal') {
          if (caller.ownerId !== to)
            invalid('Only a shared view can be claimed.', ref, requestId);
          break;
        }
        const boards = [...aggregates.values()].filter(
          board =>
            board.name === 'view' &&
            !board.deleted &&
            board.id !== id &&
            board.tenantId === aggregate.tenantId &&
            board.ownerId === SHARED &&
            board.state.appId === app &&
            board.state.kind === 'dashboard' &&
            (
              ((board.state.config as State).panels as State[] | undefined) ??
              []
            ).some(panel =>
              ['instanceId', 'opens', 'click.instanceId'].some(
                path => at(panel, path) === id,
              ),
            ),
        );
        if (boards.length > 0)
          reject(
            409,
            'view-store.referenced-by-shared-dashboard',
            `The view is shown by the shared dashboards ${boards.map(board => `"${board.state.title}" (${board.id})`).join(', ')}.`,
            ref,
            requestId,
            {
              boards: boards.map(board => ({
                id: board.id,
                title: board.state.title,
              })),
            },
          );
        commit(aggregate, { ...state, audience: 'personal' });
        firstVersion = aggregate.version;
        commit(aggregate, aggregate.state, to!);
        break;
      }
      case 'wow.lifecycle.DeleteAggregate':
        aggregate.deleted = true;
        commit(aggregate, state);
        break;
    }
    const answer = result(
      ref,
      requestId,
      firstVersion !== undefined && !waitsForMove
        ? firstVersion
        : aggregate.version,
    );
    remember(caller, requestId, command, {
      ...answer,
      ...(firstVersion === undefined ? {} : { firstVersion }),
    });
    return json(answer);
  }

  function preferencesCommand(
    caller: Caller,
    headers: Headers,
    body: State,
  ): Response {
    const app = caller.app ?? '';
    const id = `preferences-${hash(`${caller.ownerId ?? ''}\u0000${app}\u0000${String(body.definitionId)}`)}`;
    const admitted = admit(
      'view_preferences',
      'SetViewPreferences',
      caller,
      headers,
      id,
    );
    if (admitted.duplicate) return duplicateOf(admitted.duplicate);
    const { requestId, expected } = admitted;
    const ref = {
      name: 'view_preferences' as const,
      tenantId: caller.tenantId,
      id,
    };
    if (!caller.app)
      reject(
        400,
        'view-store.app-required',
        'The request carries no CoSec-App-Id.',
        ref,
        requestId,
      );
    let aggregate = admitted.aggregate;
    if (aggregate)
      existing('view_preferences', caller, aggregate, id, expected);
    else if (expected !== undefined && expected !== 0)
      fail(
        412,
        'wow.aggregate.version-mismatch',
        `view-store.view_preferences/${id} is at version 0, not ${expected}.`,
      );
    if (typeof body.definitionId !== 'string' || body.definitionId === '')
      invalid(
        "The preferences' definitionId must not be blank.",
        ref,
        requestId,
      );
    const next = {
      definitionId: body.definitionId,
      appId: app,
      order: body.order ?? [],
      defaultInstanceId: body.defaultInstanceId ?? null,
      autoRun: body.autoRun ?? null,
      lastTabs: body.lastTabs ?? null,
    };
    aggregate ??= create(
      'view_preferences',
      caller.tenantId,
      id,
      caller.ownerId,
    );
    if (JSON.stringify(next) !== JSON.stringify(aggregate.state))
      commit(aggregate, next);
    const answer = result(ref, requestId, aggregate.version);
    remember(caller, requestId, 'SetViewPreferences', answer);
    return json(answer);
  }

  function wordingCommand(
    command: string,
    caller: Caller,
    headers: Headers,
    body: State,
  ): Response {
    const app = caller.app ?? '';
    const id = `wording-${hash(`${app}\u0000${String(body.dataset)}`)}`;
    const admitted = admit('dataset_wording', command, caller, headers, id);
    if (admitted.duplicate) return duplicateOf(admitted.duplicate);
    const { requestId, expected } = admitted;
    const ref = {
      name: 'dataset_wording' as const,
      tenantId: caller.tenantId,
      id,
    };
    if (!caller.app)
      reject(
        400,
        'view-store.app-required',
        'The request carries no CoSec-App-Id.',
        ref,
        requestId,
      );
    let aggregate = admitted.aggregate;
    if (aggregate) existing('dataset_wording', caller, aggregate, id, expected);
    else if (command === 'ClearDatasetWording')
      fail(
        404,
        'wow.aggregate.not-found',
        `view-store.dataset_wording/${id} does not exist.`,
      );
    else if (expected !== undefined && expected !== 0)
      fail(
        412,
        'wow.aggregate.version-mismatch',
        `view-store.dataset_wording/${id} is at version 0, not ${expected}.`,
      );
    aggregate ??= create('dataset_wording', caller.tenantId, id, null);
    const state =
      command === 'SetDatasetWording'
        ? {
            dataset: body.dataset,
            appId: app,
            title: body.title ?? null,
            recordNoun: body.recordNoun ?? null,
            fields: body.fields ?? null,
          }
        : { ...aggregate.state, title: null, recordNoun: null, fields: null };
    if (JSON.stringify(state) !== JSON.stringify(aggregate.state))
      commit(aggregate, state);
    const answer = result(ref, requestId, aggregate.version);
    remember(caller, requestId, command, answer);
    return json(answer);
  }

  function snapshotQuery(
    name: AggregateName,
    caller: Caller,
    body: State,
  ): Response {
    if (!caller.app)
      fail(400, 'wow.query.malformed', 'The request carries no CoSec-App-Id.', {
        violations: [
          {
            path: 'body',
            code: 'APP_REQUIRED',
            message: 'The request carries no CoSec-App-Id.',
          },
        ],
      });
    const filter = (body.filter as Filter | undefined) ?? {
      op: FilterOperator.MATCH_ALL,
    };
    let records = [...aggregates.values()]
      .filter(
        aggregate =>
          aggregate.name === name &&
          !aggregate.deleted &&
          aggregate.tenantId === caller.tenantId &&
          (name === 'dataset_wording' ||
            aggregate.ownerId === caller.ownerId) &&
          aggregate.state.appId === caller.app,
      )
      .map(recordOf)
      .filter(record => matches(record, filter));
    for (const { field, direction } of [
      ...((body.sort as { field: string; direction: string }[]) ?? []),
    ].reverse())
      records.sort((a, b) => {
        const x = at(a, field) as string | number;
        const y = at(b, field) as string | number;
        const order = x < y ? -1 : x > y ? 1 : 0;
        return direction === SortDirection.DESC ? -order : order;
      });
    const include = (body.projection as { include?: string[] } | undefined)
      ?.include;
    const project = (record: Record<string, unknown>) => {
      if (!include?.length) return record;
      const picked: Record<string, unknown> = {};
      for (const path of include) setAt(picked, path, at(record, path));
      return picked;
    };
    if (body.kind === 'single') {
      if (records.length === 0)
        fail(
          404,
          'wow.query.not-found',
          `No record of view-store.${name} matches the query.`,
        );
      return json(project(records[0]));
    }
    if (body.kind === 'list')
      return json(
        records
          .slice(0, (body.limit as number | undefined) ?? 100)
          .map(project),
      );
    if (body.kind === 'cursor') {
      // The service appends the unique id to the sort and answers its cursor
      // as an opaque position after a row; here, the last row's id.
      records = [...records].sort((a, b) => {
        const order = (field: string) => {
          const x = at(a, field) as string | number;
          const y = at(b, field) as string | number;
          return x < y ? -1 : x > y ? 1 : 0;
        };
        for (const { field, direction } of (body.sort as {
          field: string;
          direction: string;
        }[]) ?? []) {
          const by = order(field);
          if (by !== 0) return direction === SortDirection.DESC ? -by : by;
        }
        return order('aggregateId');
      });
      const after = body.cursor as string | null | undefined;
      let start = 0;
      if (after) {
        const index = records.findIndex(record => cursorOf(record) === after);
        if (index < 0)
          fail(400, 'wow.query.invalid', `Not a cursor: ${after}.`);
        start = index + 1;
      }
      const size = (body.size as number | undefined) ?? 10;
      const page = records.slice(start, start + size);
      return json({
        list: page.map(project),
        nextCursor:
          start + size < records.length
            ? cursorOf(page[page.length - 1])
            : null,
      });
    }
    return fail(
      400,
      'wow.query.invalid',
      `The fake serves no ${String(body.kind)} query.`,
    );
  }

  function stateRead(
    caller: Caller,
    id: string,
    query: URLSearchParams,
  ): Response {
    const aggregate = aggregates.get(keyOf('view', caller.tenantId, id));
    if (!aggregate)
      fail(
        404,
        'wow.aggregate.not-found',
        `view-store.view/${id} does not exist.`,
      );
    // What the caller may not see is not found, as on the server (decision D254 ④).
    if (aggregate.ownerId !== caller.ownerId)
      fail(
        404,
        'wow.aggregate.not-found',
        `view-store.view/${id} does not exist.`,
      );
    const version = query.get('version');
    const at =
      version === null
        ? aggregate.history[aggregate.history.length - 1]
        : aggregate.history.find(entry => entry.version === Number(version));
    if (!at)
      fail(
        404,
        'wow.aggregate.not-found',
        `view-store.view/${id} has no version ${version}.`,
      );
    return json(
      {
        ref: {
          type: { context: 'view-store', name: 'view' },
          tenantId: aggregate.tenantId,
          id,
        },
        version: at.version,
        scope: { ownerId: at.ownerId, spaceId: null },
        deleted: at.deleted,
        tags: {},
        state: structuredClone(at.state),
      },
      200,
      { ETag: `"${at.version}"` },
    );
  }

  function systemViews(caller: Caller): Record<string, unknown>[] {
    if (!caller.app)
      fail(
        400,
        'view-store.app-required',
        'The request carries no CoSec-App-Id.',
      );
    const configured = (options.systemViews ?? [])
      .filter(
        view =>
          (!view.tenantId || view.tenantId === caller.tenantId) &&
          (!view.appId || view.appId === caller.app),
      )
      .map(view => ({
        id: view.id,
        definitionId: view.definitionId,
        title: view.title,
        kind: view.config.kind,
        revision: hash(
          JSON.stringify([view.definitionId, view.title, view.config]),
        ),
        config: view.config,
        source: 'configured',
        version: null,
        scope: 'system',
      }));
    const stored = [...aggregates.values()]
      .filter(
        view =>
          view.name === 'view' &&
          !view.deleted &&
          view.tenantId === PLATFORM &&
          view.ownerId === SYSTEM &&
          view.state.appId === caller.app,
      )
      .sort(
        (a, b) => a.firstEventTime - b.firstEventTime || (a.id < b.id ? -1 : 1),
      )
      .map(view => ({
        id: view.id,
        definitionId: view.state.definitionId,
        title: view.state.title,
        kind: view.state.kind,
        revision: hash(
          JSON.stringify([
            view.state.definitionId,
            view.state.title,
            view.state.config,
          ]),
        ),
        config: view.state.config,
        source: 'stored',
        version: view.version,
        scope: 'system',
      }));
    const ids = new Set(stored.map(view => view.id));
    return [...configured.filter(view => !ids.has(view.id)), ...stored];
  }

  async function dataset(
    path: string,
    method: string,
    body: State,
  ): Promise<Response | undefined> {
    if (path === '/wow/dataset' && method === 'GET')
      return json(
        (options.datasets ?? []).map(({ context, dataset: name, title }) => ({
          name: `${context}.${name}`,
          context,
          dataset: name,
          title: title ?? null,
          path: `/wow/dataset/${context}/${name}/query`,
        })),
      );
    const match = /^\/wow\/dataset\/([^/]+)\/([^/]+)\/query$/.exec(path);
    if (!match) return undefined;
    const found = (options.datasets ?? []).find(
      entry => entry.context === match[1] && entry.dataset === match[2],
    );
    if (!found)
      return problem(
        404,
        'wow.dataset.not-found',
        `No dataset ${match[1]}.${match[2]}.`,
      );
    const { source } = found;
    if (method === 'GET') {
      const read = await source.describe?.();
      if (!read?.descriptor) return problem(404, undefined, 'No descriptor.');
      return json(read.descriptor, 200, {
        ETag: `"${read.descriptor.version}"`,
      });
    }
    const { kind, total: _total, ...query } = body;
    void _total;
    switch (kind) {
      case 'paged': {
        const page = await source.paged(query);
        return json({
          total: page.total,
          exact: page.exact ?? true,
          list: page.list,
        });
      }
      case 'cursor':
        return json(await source.cursor(query));
      case 'aggregate':
        return json(await source.aggregate(query));
      default:
        return problem(
          400,
          'wow.query.invalid',
          `The fake serves no ${String(kind)} query.`,
        );
    }
  }

  async function serve(request: SeenRequest): Promise<Response> {
    const { method, path, headers, body, query } = request;
    const datasetAnswer = await dataset(path, method, (body ?? {}) as State);
    if (datasetAnswer) return datasetAnswer;
    const caller = callerOf(headers);
    if (method === 'GET' && path === '/view-store/system-views') {
      const definitionId = query.get('definitionId');
      return json(
        systemViews(caller).filter(
          view => !definitionId || view.definitionId === definitionId,
        ),
      );
    }
    const one = /^\/view-store\/system-views\/([^/]+)$/.exec(path);
    if (method === 'GET' && one) {
      const view = systemViews(caller).find(entry => entry.id === one[1]);
      return view
        ? json(view)
        : problem(
            404,
            'view-store.not-found',
            `System view [${one[1]}] is not found.`,
          );
    }
    const route =
      /^\/view-store\/(view|view_preferences|dataset_wording)(?:\/([^/]+))?(?:\/([^/]+))?$/.exec(
        path,
      );
    if (!route) return problem(404, undefined, `No route ${method} ${path}`);
    const name = route[1] as AggregateName;
    const [, , first, second] = route;
    const payload = (body ?? {}) as State;
    if (first === 'snapshot' && second === 'query' && method === 'POST')
      return snapshotQuery(name, caller, payload);
    if (name === 'view') {
      if (method === 'DELETE' && first && !second)
        return viewCommand(
          'wow.lifecycle.DeleteAggregate',
          first,
          caller,
          headers,
          payload,
        );
      if (method === 'GET' && first && second === 'state')
        return stateRead(caller, first, query);
      const commands: Record<string, string> = {
        'create-view': 'CreateView',
        'save-view': 'SaveView',
        'rename-view': 'RenameView',
        'share-view': 'ShareView',
        'claim-view': 'ClaimView',
        'wow.lifecycle.transfer-owner': 'wow.lifecycle.TransferOwner',
      };
      const segment = second ?? first;
      const command = segment ? commands[segment] : undefined;
      if (method === 'POST' && command)
        return viewCommand(
          command,
          second ? first! : '',
          caller,
          headers,
          payload,
        );
    }
    if (
      name === 'view_preferences' &&
      method === 'POST' &&
      (second ?? first) === 'set-view-preferences'
    )
      return preferencesCommand(caller, headers, payload);
    if (name === 'dataset_wording' && method === 'POST') {
      const segment = second ?? first;
      if (segment === 'set-dataset-wording')
        return wordingCommand('SetDatasetWording', caller, headers, payload);
      if (segment === 'clear-dataset-wording')
        return wordingCommand('ClearDatasetWording', caller, headers, payload);
    }
    return problem(404, undefined, `No route ${method} ${path}`);
  }

  return {
    requests,
    failNext(path, answer) {
      failures.push({ path, answer });
    },
    async fetch(input, init = {}) {
      const url = new URL(
        input instanceof Request ? input.url : String(input),
        'http://view-store.test/',
      );
      const seen: SeenRequest = {
        method: init.method ?? 'GET',
        path: decodeURIComponent(url.pathname),
        query: url.searchParams,
        headers: new Headers(init.headers),
        body:
          typeof init.body === 'string' && init.body !== ''
            ? JSON.parse(init.body)
            : init.body,
      };
      requests.push(seen);
      if (init.signal?.aborted) throw init.signal.reason;
      const failure = failures.findIndex(entry => entry.path.test(seen.path));
      if (failure >= 0) return failures.splice(failure, 1)[0].answer();
      try {
        return await serve(seen);
      } catch (thrown) {
        if (thrown instanceof Answer) return thrown.response;
        throw thrown;
      }
    },
  };
}
