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
 * What a declared action's rule read off a row that the row never fetched,
 * told in development (host-integration.md 5).
 *
 * A row brings only what the view needs (`recordProjection`): the shown
 * columns, the row key, the card's fields, what the sort and the summaries
 * read, and `record.rowFields`. A rule that reads anything else — 「发货」
 * reading `state.status` on a table that hides the status column — reads
 * nothing, and the action is greyed out on every row without a word. A
 * rule is a function, so which fields it reads is only seen as it runs: in
 * a development build the rows the rules are asked about are read-only
 * proxies that note a read of a field the row did not fetch, and the
 * engine's `onIssue` hears it once per view and field, with the field, the
 * action and the way out (`record.rowFields`). A production build makes no
 * proxy and registers nothing: the rules read the rows as they are.
 */

import { issue } from '../filter/index.js';
import {
  isFieldlessKind,
  type DataViewDefinition,
  type FieldDefinition,
  type Issue,
  type RecordViewConfig,
} from '../model/index.js';
import { recordProjection, type RecordRow } from '../record/index.js';
import type { ActionContext, RecordAction, RecordActions } from './actions.js';
import { inDevelopment } from './failure/issueReport.js';
import type { RecordViewRuntime } from './viewRuntimeTypes.js';

/** What one open record view watches its actions' reads with. */
interface ReadWatch {
  /** The paths the rows on screen were fetched with, and every declared one. */
  paths(): { fetched: readonly string[]; declared: ReadonlySet<string> };
  /** Tells of `field`, read by `action` and not fetched, once per view. */
  unfetched(field: string, action: string): void;
}

/** Each watched runtime's watch; only ever filled in development. */
const watches = new WeakMap<object, ReadWatch>();

/**
 * Watches what the declared actions read off the rows of `runtime`, in a
 * development build; anywhere else it does nothing. `told` is the engine's
 * own: one view opened twice is told of a field once.
 */
export function watchActionReads(
  runtime: RecordViewRuntime,
  report: (found: Issue) => void,
  told: Set<string>,
): void {
  if (!inDevelopment()) return;
  // Worked out again only when the definition or the applied config moved.
  let last: {
    definition: DataViewDefinition;
    applied: RecordViewConfig;
    paths: ReturnType<ReadWatch['paths']>;
  } | null = null;
  watches.set(runtime, {
    paths() {
      const definition = runtime.definition;
      const applied = runtime.getSnapshot().applied;
      if (last?.definition === definition && last.applied === applied)
        return last.paths;
      const paths = {
        fetched: recordProjection(definition, applied).include ?? [],
        declared: declaredPaths(definition.fields),
      };
      last = { definition, applied, paths };
      return paths;
    },
    unfetched(field, action) {
      const definition = runtime.definition.id;
      const view = runtime.getSnapshot().saved?.id ?? runtime.id;
      const key = [definition, view, field].join('\u0000');
      if (told.has(key)) return;
      told.add(key);
      report(
        issue(
          'record.action.unfetched',
          [],
          { action, field, definition, view },
          'warning',
        ),
      );
    },
  });
}

/**
 * Every path a row could bring: the definition's fields and their elements',
 * bar a field-less kind's handle and a field the source will not project —
 * `rowFields` brings neither, so neither is told of.
 */
function declaredPaths(fields: readonly FieldDefinition[]): Set<string> {
  const paths = new Set<string>();
  for (const field of fields) {
    if (isFieldlessKind(field.kind) || field.projectable === false) continue;
    paths.add(field.name);
    for (const element of field.elements ?? [])
      paths.add(`${field.name}.${element.name}`);
  }
  return paths;
}

/**
 * `list` as the surface over `runtime` asks it, about `rows`: unchanged
 * unless the runtime is watched (`watchActionReads`, development only), and
 * then each action's rules — `hidden`, `available`, `changesAt` — read
 * those rows through a proxy that tells of a field they did not fetch. A
 * row not among them (the record detail's, read whole) is handed as it is,
 * and so is every row `run` is handed.
 */
export function watchedActions(
  list: RecordActions | undefined,
  runtime: RecordViewRuntime | null,
  rows: readonly RecordRow[],
): RecordActions | undefined {
  const watch = runtime ? watches.get(runtime) : undefined;
  if (!list || !watch) return list;
  const fetchedRows = new Set(rows);
  return list.map(action => {
    const seen = (row: RecordRow): RecordRow =>
      fetchedRows.has(row)
        ? readsOf(row, watch.paths(), field =>
            watch.unfetched(field, action.id),
          )
        : row;
    // The action itself stays underneath, so every member the surface
    // reads — the host's own, on a class's prototype too — reads through.
    const rules: Partial<Record<keyof RecordAction, unknown>> = {};
    if (action.hidden)
      rules.hidden = (row: RecordRow, context: ActionContext) =>
        action.hidden?.(seen(row), context);
    if (action.available)
      rules.available = (row: RecordRow, context: ActionContext) =>
        action.available?.(seen(row), context);
    if (action.changesAt)
      rules.changesAt = (row: RecordRow, context: ActionContext) =>
        action.changesAt?.(seen(row), context);
    return Object.assign(Object.create(action) as RecordAction, rules);
  });
}

/** `row` with its data read through `watchingReads`, which tells of `unfetched`. */
function readsOf(
  row: RecordRow,
  paths: ReturnType<ReadWatch['paths']>,
  unfetched: (field: string) => void,
): RecordRow {
  return { key: row.key, data: watchingReads(row.data, '', paths, unfetched) };
}

/**
 * `value` at `path`, its reads watched (only its reads: anything else goes
 * to `value` untouched): what was fetched reads as it is,
 * what holds fetched paths under it reads through another such view, and a
 * declared path that was not fetched is told of — and reads as it is, so a
 * rule decides exactly as it would without the proxy.
 */
function watchingReads<T extends object>(
  value: T,
  path: string,
  paths: ReturnType<ReadWatch['paths']>,
  unfetched: (field: string) => void,
): T {
  return new Proxy(value, {
    get(target, key, receiver) {
      const found: unknown = Reflect.get(target, key, receiver);
      if (typeof key !== 'string') return found;
      const list = Array.isArray(target);
      // An array's own members (`length`, `some`) are not fields; its
      // elements are read under the array's own path.
      if (list && !/^\d+$/.test(key)) return found;
      const at = list ? path : path === '' ? key : `${path}.${key}`;
      if (paths.fetched.some(each => at === each || at.startsWith(`${each}.`)))
        return found;
      if (paths.fetched.some(each => each.startsWith(`${at}.`)))
        return found !== null &&
          typeof found === 'object' &&
          !frozenAt(target, key)
          ? watchingReads(found, at, paths, unfetched)
          : found;
      if (
        [...paths.declared].some(
          each =>
            at === each ||
            each.startsWith(`${at}.`) ||
            at.startsWith(`${each}.`),
        )
      )
        unfetched(at);
      return found;
    },
  });
}

/**
 * Whether `target[key]` must read as itself: a proxy may not stand in for
 * a property that can neither change nor be redefined.
 */
function frozenAt(target: object, key: string): boolean {
  const own = Object.getOwnPropertyDescriptor(target, key);
  return own !== undefined && !own.configurable && own.writable === false;
}
