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

import type {
  AnalysisViewConfig,
  DashboardDefinition,
  DashboardFilters,
  DashboardViewConfig,
  DataViewDefinition,
  FieldDefinition,
  FilterTree,
  Issue,
  PagingMode,
  RecordData,
  RecordKey,
  RecordPageTarget,
  RecordViewConfig,
  RuntimeLimits,
  ViewConfig,
  ViewInstance,
  ViewScope,
} from '../model/index.js';
import type { FieldKindRegistry } from '../filter/index.js';
import type { RuntimeEnvironment } from './environment.js';
import type { RequestRunner } from './requestRunner.js';
import type { OptionSource, ProjectedView, ViewSource } from './source.js';
import type { DataViewConfig } from './execute.js';
import type { ExportRowsOptions, ExportedRows } from './exportRows.js';
import type { ValueCandidateSource } from './valueCandidates.js';
import type { WriteState } from './write.js';
import type { DashboardRuntime } from './dashboardRuntime.js';
import type { HeldFilters } from './dashboard/contract.js';

/**
 * One open view. A small store with `subscribe` and `getSnapshot`, so React
 * binds to it with `useSyncExternalStore` and nothing else is needed.
 *
 * The three states it keeps apart are the whole design: `draft` is what the
 * editor shows, `applied` is what was last executed, and `result` is what came
 * back, tagged with the config that produced it.
 */
export interface ViewRuntime<C extends ViewConfig = ViewConfig> {
  /** Runtime identity, distinct from the instance id: an unsaved view has one too. */
  readonly id: string;
  readonly kind: C['kind'];
  readonly definition: DefinitionFor<C>;
  /**
   * Fields the filter editor edits against: a data view's own, a dashboard's
   * declared global ones. A dashboard's set follows its draft, so read this
   * on every render rather than once per runtime.
   */
  readonly fields: readonly FieldDefinition[];
  /** The registry admission used, which an editor must edit against. */
  readonly kinds: FieldKindRegistry;
  /**
   * The budgets this view was admitted under. An editor offers within them
   * rather than offering a choice the kernel will then refuse: a page size
   * above `maxPageSize` is an error the user made by picking from a list the
   * UI drew, which is the UI's fault and not theirs.
   */
  readonly limits: RuntimeLimits;
  /**
   * The remote candidates behind a `reference` field's `remote` key, or
   * `null` when the host wired no `resolveOptions`. The editor asks here
   * rather than reaching for the engine: a runtime is what a workbench
   * holds, and a value editor two levels down has no engine to reach.
   */
  optionSource(remote: string): OptionSource | null;
  /**
   * The values a condition on `field` may be picked from, counted from this
   * view's data within the injected scope (`ValueCandidateSources`), or
   * `null` where they are not offered: a field the definition does not let
   * be grouped by value, one with `options` or `remote` of its own, and every
   * field of a dashboard, which has no data of its own to count.
   */
  valueCandidates(field: string): ValueCandidateSource | null;
  getSnapshot(): ViewRuntimeState<C>;
  subscribe(listener: () => void): () => void;
  /**
   * Changes the draft only, synchronously.
   *
   * A member given as `undefined` is **removed** rather than set to it. A
   * config is JSON, where a member that is not there and one that is
   * `undefined` are the same config but not the same object — and `dirty`
   * is an equality against the saved one, so setting it left a view
   * permanently unsaved and the leave guard asking about an edit the user
   * had already undone. An editor that takes the last entry out of an
   * optional list therefore says `undefined` and gets the config back as it
   * was.
   */
  edit(patch: Partial<C>): void;
  /** Promotes a valid draft to `applied` and executes it. */
  apply(): void;
  /**
   * Takes the draft back to the saved baseline and puts it in force again. A
   * view that was never saved has no baseline to return to, so it is a no-op
   * there; what is on screen is all there is.
   */
  revert(): void;
  /**
   * Re-runs `applied` from the first page. A no-op while `applied` was never
   * admitted: a view opened on a config the definition refuses waits for a
   * fix, and no command runs it as it stands.
   */
  refresh(): void;
  /** Called when an editor takes or loses focus; pauses auto-refresh. */
  setEditing(active: boolean): void;
  /**
   * Whether the view refreshes itself on the interval it applied (on when
   * opened). Off, the timer is held for good — the interval stays what it
   * was, and saving writes it unchanged — until it is on again: an embed
   * whose host asked for no auto-refresh (`EmbeddedView`'s `autoRefresh`).
   * A refresh asked for still runs.
   */
  setAutoRefresh(on: boolean): void;
  /**
   * Whether the draft runs on its own a moment after its question changes
   * (`autoApply.ts`); which members are the question is declared per kind
   * by the model (`autoRunMembers`), and the range still waits for `apply`.
   * Off by default: the workbench switches it on from the user's preference.
   */
  setAutoApply(on: boolean): void;
  /**
   * An outer condition ANDed onto the applied filter; never touches the draft.
   *
   * Returns what the condition was refused for, which is empty when it is in
   * force. A refusal changes nothing: the scope in force stays in force, the
   * result on screen stays on screen, and the host is told — the one thing
   * this view must never do is narrow less than the page asked without
   * saying so.
   */
  setScopeFilter(tree: FilterTree | null): Issue[];
  /**
   * What the scope last asked for was refused for, or empty while what was
   * asked for is in force.
   *
   * A refusal is the host's condition and not the view's defect, so it is not
   * among `state.issues` and does not stop the view (D17-5): a view opened
   * under a scope its definition cannot take runs un-narrowed and says this.
   * Read rather than only returned by `setScopeFilter`, because a scope goes
   * in at construction as well, and a host that opened one through
   * `ViewEngine.open` has no return value to read it from.
   */
  readonly refusedScope: Issue[];
  /**
   * The outer condition in force, as it was last admitted, or `null` while
   * none is — including a scope that was asked for and refused.
   *
   * It is read rather than only written because the conditions the rows came
   * back under are two things and not one: the view's own, which the editor
   * addresses and may take out, and the host's, which are in force and are
   * nobody's here to remove. A summary that reads the merged tree can tell
   * neither apart — see `ViewResult.own`.
   */
  readonly scopeFilter: FilterTree | null;
  /**
   * The host this view runs against: its clock, its timers, its visibility.
   *
   * Read rather than only written because `nextRefreshAt` is a reading of
   * *this* clock and means nothing against another one. A countdown that
   * asked the system clock would drift away from the timer it claims to be
   * counting to the moment a test, a demo or a server-rendered page injected
   * a clock of its own — which is the whole reason the environment exists.
   * `ViewEngine` already hands the same object out for its time zone.
   */
  readonly environment: RuntimeEnvironment;
  dispose(): void;
  /** True once disposed: every command is a no-op from then on. */
  readonly disposed: boolean;
}

/**
 * The definition a config belongs to. A dashboard owns no data, so its
 * definition is a catalogue entry with no fields and no capabilities.
 */
export type DefinitionFor<C extends ViewConfig> = C extends DashboardViewConfig
  ? DashboardDefinition
  : DataViewDefinition;

/** Paging and selection belong to Record alone. */
export interface RecordViewRuntime<
  P extends PagingMode = PagingMode,
> extends ViewRuntime<RecordViewConfig> {
  page(target: RecordPageTarget<P>): void;
  select(keys: RecordKey[]): void;
  /**
   * Every row the **applied** conditions match, paged out behind the screen
   * for an export — the same filter and sort, without the page on screen.
   *
   * It runs beside the view rather than through it: no scheduler slot, no
   * `apply`, and `state.result` is untouched, so the rows the user is reading
   * stay exactly as they are while a long export runs. Stopped by
   * `options.signal`, capped at `limits.exportMax`.
   */
  exportRows(options?: ExportRowsOptions): Promise<ExportedRows>;
  /**
   * One record, whole, by its row key — for a detail panel (`fetchRecord`):
   * every field, within the injected scope and not the page's conditions.
   * `null` when it is no longer there.
   */
  fetchRecord(key: RecordKey, signal?: AbortSignal): Promise<RecordData | null>;
}

/** What opening an instance returns; narrow it by `runtime.kind`. */
export type AnyViewRuntime =
  RecordViewRuntime | ViewRuntime<AnalysisViewConfig> | DashboardRuntime;

/**
 * What `ViewEngine` needs beyond the public contract: admission at the scope
 * a write is headed for, and the three ways an outcome reaches an open view.
 * Both runtime classes implement it, which is how the engine stays
 * indifferent to the kind.
 */
export interface ManagedViewRuntime<
  C extends ViewConfig = ViewConfig,
> extends ViewRuntime<C> {
  /**
   * Admission of the draft as it would stand at a target scope, which is what
   * "save as shared" has to ask: a dashboard may reference views the people it
   * would be shared with cannot read.
   */
  issuesAt(scope: ViewScope): Issue[];
  /** Advances the saved baseline once the store has confirmed this view's write. */
  markSaved(instance: ViewInstance): void;
  /**
   * Advances the baseline because a write elsewhere moved it: the same
   * instance open in another view, or renamed from the list. Whatever write
   * of this view's own is still unsettled stays so, for its recovery actions.
   */
  moveBaseline(instance: ViewInstance): void;
  /** Replaces the draft with the store's state, for "reload" on a conflict. */
  adoptSaved(instance: ViewInstance): void;
  setWrite(write: WriteState | null): void;
}

/** Keeps the narrow type through `create`, which knows its config statically. */
export type RuntimeFor<C extends ViewConfig> = C extends RecordViewConfig
  ? RecordViewRuntime
  : ViewRuntime<C>;

export type QueryStatus = 'idle' | 'loading' | 'success' | 'error';

export interface ViewQueryState {
  status: QueryStatus;
  error?: Issue;
  requestId?: string;
}

export interface ViewResult<C> {
  /** The config that produced this data, scope filter included. */
  config: C;
  /**
   * The view's own half of it: the `applied` config as it was promoted,
   * before the scope filter was merged in.
   *
   * A summary of the conditions in force addresses the draft through this one.
   * `mergeFilters` appends the scope as a trailing group, and wraps an `or` or
   * `nor` draft as the first child of an `and`, so a path into `config.filter`
   * addresses neither the draft's tree nor anything the editor may remove —
   * and the host's own condition would sit in the bar looking removable.
   * Equal to `config` when nothing is injected, and for a dashboard, which
   * runs no query of its own.
   */
  own: C;
  data: ProjectedView;
  receivedAt: number;
  /**
   * How long the answer took, in milliseconds on the environment's clock:
   * from the moment it was asked to the moment it landed, a wait in the
   * engine's queue included — that is time the person waited too. A page
   * re-asked because the result shrank out from under it counts from the
   * first asking. The analysis result's footer says it («耗时 0.4 秒»).
   */
  elapsedMs: number;
}

export interface ViewRuntimeState<C> {
  /** The saved baseline; `null` while the view has never been saved. */
  saved: ViewInstance | null;
  title: string;
  scope: ViewScope;
  draft: C;
  applied: C;
  /**
   * Admission of the draft as it would run: with the injected scope filter
   * ANDed in, because that is the config `apply` executes. An `error` blocks
   * `apply` and every write. The scope is appended after the draft's own
   * conditions, so a path into the draft's tree is unchanged by it.
   */
  issues: Issue[];
  dirty: boolean;
  query: ViewQueryState;
  result: ViewResult<C> | null;
  /** Row keys of the current result only; cleared when the result changes. */
  selection: RecordKey[];
  write: WriteState | null;
  editing: boolean;
  /** See `ViewRuntime.setAutoApply`. */
  autoApply: boolean;
  /**
   * When the next automatic refresh is due, on the environment's clock, or
   * `null` while no timer is armed — no interval in force, or one of the four
   * reasons the runtime holds it.
   *
   * It is the timer's own due time rather than a second opinion about it: set
   * where the timer is armed, cleared where it is stopped. A control counting
   * down to the next refresh reads this against `environment.now()`, so what
   * it says and what will happen cannot come apart; a countdown run off a
   * clock of its own would.
   */
  nextRefreshAt: number | null;
}

/**
 * Whether this view ever got a result, even one that is now out of date.
 *
 * Not `rows.length > 0` and not `status === 'success'`: a failed refresh
 * keeps the rows it could not replace and turns to `error`, and a successful
 * result matching zero rows has no rows at all. The table, the applied bar
 * and the result block all ask the same question of the same member, so they
 * ask it here — a spelling of `state.result != null` at every call site is
 * one place each for it to start meaning something else.
 *
 * Structural in its argument, because it is asked of whatever holds a
 * snapshot: a runtime's state, a controller's, or nothing yet.
 */
export function hasResult(
  state: { result: unknown } | null | undefined,
): boolean {
  return state?.result != null;
}

/**
 * Whether this view has put a question to its source: a result came back,
 * one is on its way, or the last one failed.
 *
 * It is the wider question the chrome around a result asks — the applied
 * conditions, a toolbar that reads the question out — because each of those
 * has something true to say from the moment a query is sent, not only from
 * the moment one lands. Drawn only once rows arrived, they appeared under
 * the reader's eyes as the first answer landed and pushed the result down
 * the page, and a first query that failed left the failure standing alone
 * with nothing around it to act on. A query that was sent was admitted, so
 * `applied` is a config the screen may describe.
 *
 * What it is not: a view whose config was refused before it ran. Nothing
 * was asked there, and the status line is what speaks.
 */
export function hasAsked(
  state: { result: unknown; query: { status: QueryStatus } } | null | undefined,
): boolean {
  if (hasResult(state)) return true;
  const status = state?.query.status;
  return status === 'loading' || status === 'error';
}

export interface ViewRuntimeOptions<C extends DataViewConfig> {
  id: string;
  definition: DataViewDefinition;
  config: C;
  title: string;
  scope: ViewScope;
  saved?: ViewInstance | null;
  kinds: FieldKindRegistry;
  limits: RuntimeLimits;
  environment: RuntimeEnvironment;
  source: ViewSource;
  runner: RequestRunner;
  /** See `ViewRuntime.optionSource`. */
  resolveOptions?(key: string): OptionSource;
  /** An outer condition in force from the first execution on. */
  scopeFilter?: FilterTree | null;
  /**
   * False inside a dashboard, which times the refresh of every panel itself
   * rather than letting each one run a timer of its own.
   */
  autoRefresh?: boolean;
}

/** How `ViewEngine.open` opens a view, beyond which one. */
export interface OpenOptions {
  /**
   * An outer condition in force from the first query, in the view's own field
   * names. It is admitted with the config rather than after it, so a host that
   * scopes a view — an order page showing one customer's shipments — never
   * lets an unscoped query leave, and never shows rows outside its scope.
   */
  scopeFilter?: FilterTree | null;
  /**
   * The tab a dashboard opens on (D22 E) — a host's route says it. Left out,
   * the one this reader last read the board on (`ViewPreferences.lastTabs`);
   * a tab the board lacks opens its first. Nothing else reads it.
   */
  tab?: string | null;
  /**
   * What a dashboard's filters hold as it opens (D22 F) — a host's address
   * keeps them. Left out, every filter at its default; what the board does
   * not take is left out, the rest taken (`DashboardRuntime.refusedFilters`
   * says what was left out). Nothing else reads it.
   */
  filters?: DashboardFilters | null;
  /**
   * The filters a host holds as the dashboard opens (`holdFilters`): in
   * force from the first query, over `filters` or the defaults. Nothing
   * else reads it.
   */
  held?: HeldFilters | null;
}
