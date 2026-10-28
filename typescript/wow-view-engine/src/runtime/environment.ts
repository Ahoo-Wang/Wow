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

import type { QueryViolation } from '@ahoo-wang/wow-client/dsl';
import type { RecordKey } from '../model/index.js';

/**
 * The only interface between a runtime and its host. Relative dates, the
 * refresh timer, the "is anyone looking" question and the failures a host
 * wants to hear about all pass through it, so `model` through `store` stay
 * free of the DOM and of the system clock, and a test can drive time without
 * faking globals.
 */
export interface RuntimeEnvironment {
  now(): Date;
  /** IANA zone used to resolve relative and preset dates. */
  timeZone: string;
  setTimeout(callback: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  visibility: VisibilitySource;
  /**
   * Told once of every failure the engine meets doing its work — a query, a
   * store call, an export, a render, a chart, a declared action's command —
   * for a host to log or send to its monitoring. The failure is still answered on screen as it
   * always was; this is the host's copy. Whatever it throws is dropped, and
   * left out, nothing is logged anywhere.
   */
  onError?(event: ViewErrorEvent): void;
}

/** A query the engine ran, by what it asked for. */
export type QueryOperation =
  | 'query'
  | 'summaries'
  | 'totals'
  | 'split'
  | 'record'
  | 'kanban'
  | 'candidates'
  | 'labels';

/** A `ViewStore` method, as the engine called it. */
export type StoreOperation =
  | 'list'
  | 'get'
  | 'create'
  | 'save'
  | 'rename'
  | 'delete'
  | 'changeAudience'
  | 'getPreferences'
  | 'setPreferences';

/**
 * A file or a picture made of a view: the rows read for a file, the file
 * handed over, a chart drawn as an image.
 */
export type ExportOperation = 'fetch' | 'deliver' | 'image';

/** Where a chart failed: its library never arrived, or it threw drawing. */
export type ChartStage = 'load' | 'draw';

/** Where a failure happened, each member only where the failing part knows it. */
export interface ViewErrorPlace {
  definitionId?: string;
  /** The saved view, when the failing one is saved. */
  instanceId?: string;
  /** The open view (`ViewRuntime.id`) it failed in. */
  runtimeId?: string;
  /** In a dashboard panel: which panel. */
  panelId?: string;
}

/**
 * What each kind of failure says of where it happened. `operation` is
 * always said, typed for its kind, so a host can switch on it exhaustively;
 * the rest only where the failing part knows it.
 */
export interface ViewErrorContexts {
  /**
   * A query: the view's own (`query`), `summaries`, `totals`, `split`,
   * `record` (one row read whole), `kanban` (a board's lanes), `candidates`
   * (a condition's values) — or `labels`, reference ids read as names, which
   * says the remote instead.
   */
  query:
    | (ViewErrorPlace & {
        operation: Exclude<QueryOperation, 'labels'>;
        /**
         * Which rule a Wow service said the query broke and where — its
         * stable `code` (wow-client's `QueryErrorCodes`, an open list), the
         * JSON path or logical field path, and its message. Absent when the
         * source said none: a budget rejection, a failure the service did
         * not answer, a source that is not Wow. The report waits for the
         * body the source answered with to be read, so this is there when
         * it is said.
         */
        violation?: QueryViolation;
        /**
         * The Wow `code` of the problem the service answered with, as it
         * gave it — `wow.query.invalid`, `wow.aggregate.forbidden`, an
         * application's own — when it gave one.
         */
        errorCode?: string;
      })
    | (ViewErrorPlace & {
        operation: 'labels';
        /** The remote whose labels could not be read. */
        remote: string;
      });
  /** A store call, by the port's method. */
  store: ViewErrorPlace & {
    operation: StoreOperation;
    /** A write's idempotency key, the same across its retries. */
    requestId?: string;
  };
  export: ViewErrorPlace & { operation: ExportOperation };
  /** A part that threw drawing, caught by its render boundary. */
  render: ViewErrorPlace & {
    operation: 'render';
    /** The boundary that caught it. */
    boundary: string;
    /** React's component stack, where given. */
    componentStack?: string;
  };
  /** A chart that could not be drawn, caught by its render boundary. */
  chart: ViewErrorPlace & {
    operation: ChartStage;
    /** The boundary that caught it. */
    boundary: string;
    /** React's component stack, where given. */
    componentStack?: string;
  };
  /** A declared action's command that failed on a record. */
  action: ViewErrorPlace & {
    /** The action's id; a slot's command: its `operation`, else its title. */
    operation: string;
    /** The record the command failed on. */
    recordKey: RecordKey;
  };
}

/** What failed, by the part of the engine it failed in. */
export type ViewErrorKind = keyof ViewErrorContexts;

/** Where a failure of any kind happened. */
export type ViewErrorContext = ViewErrorContexts[ViewErrorKind];

/**
 * One failure, as `RuntimeEnvironment.onError` is told of it: a union on
 * `kind`, each kind with its own context.
 */
export type ViewErrorEvent<K extends ViewErrorKind = ViewErrorKind> = {
  [P in K]: {
    kind: P;
    /** What was thrown or rejected with, as it was: a stack, a code, a body. */
    error: unknown;
    context: ViewErrorContexts[P];
  };
}[K];

export interface VisibilitySource {
  isVisible(): boolean;
  subscribe(listener: () => void): () => void;
}

/** A host that never hides, which is what Node and a test both are. */
export const ALWAYS_VISIBLE: VisibilitySource = {
  isVisible: () => true,
  subscribe: () => () => {},
};

/**
 * The default host: the ambient clock and timers, the ambient zone, and a page
 * that is always visible. `/react` replaces `visibility` with one backed by
 * `document.visibilityState`.
 */
export function defaultRuntimeEnvironment(
  overrides: Partial<RuntimeEnvironment> = {},
): RuntimeEnvironment {
  return {
    now: () => new Date(),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    setTimeout: (callback, ms) => globalThis.setTimeout(callback, ms),
    clearTimeout: handle => {
      globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>);
    },
    visibility: ALWAYS_VISIBLE,
    ...overrides,
  };
}
