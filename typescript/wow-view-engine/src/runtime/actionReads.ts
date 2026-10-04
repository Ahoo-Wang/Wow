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

/** The paths the rows on screen were fetched with, and every declared one. */
interface Paths {
  fetched: readonly string[];
  declared: ReadonlySet<string>;
}

/** What one open record view watches its actions' reads with. */
interface ReadWatch {
  paths(): Paths;
  /** Tells of `field`, read by `action` and not fetched, once per view. */
  unfetched(field: string, action: string): void;
  /**
   * Each row's one proxy, while the paths it was made for hold: a rule
   * asked twice of a row sees the same object, so a memo the host keys by
   * row works in development as it does without the proxy.
   */
  readonly proxies: WeakMap<RecordRow, { paths: Paths; row: RecordRow }>;
  /** The action whose rule is reading now, which a read is told under. */
  reading: string | null;
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
    paths: Paths;
  } | null = null;
  watches.set(runtime, {
    proxies: new WeakMap(),
    reading: null,
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
      // A view never saved is keyed by its definition, so opening it again
      // does not tell of the same field again.
      const view = runtime.getSnapshot().saved?.id ?? definition;
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
  const seen = (row: RecordRow): RecordRow => {
    if (!fetchedRows.has(row)) return row;
    const paths = watch.paths();
    const kept = watch.proxies.get(row);
    if (kept?.paths === paths) return kept.row;
    const proxied: RecordRow = {
      key: row.key,
      data: watchingReads(row.data, '', paths, field => {
        if (watch.reading !== null) watch.unfetched(field, watch.reading);
      }),
    };
    watch.proxies.set(row, { paths, row: proxied });
    return proxied;
  };
  /** `rule` asked of `row` as seen, its reads told under `action`. */
  const asking = <T>(action: RecordAction, rule: () => T): T => {
    const outer = watch.reading;
    watch.reading = action.id;
    try {
      return rule();
    } finally {
      watch.reading = outer;
    }
  };
  return list.map(action => {
    // The action itself stays underneath, so every member the surface
    // reads — the host's own, on a class's prototype too — reads through.
    const rules: Partial<Record<keyof RecordAction, unknown>> = {};
    if (action.hidden)
      rules.hidden = (row: RecordRow, context: ActionContext) =>
        asking(action, () => action.hidden?.(seen(row), context));
    if (action.available)
      rules.available = (row: RecordRow, context: ActionContext) =>
        asking(action, () => action.available?.(seen(row), context));
    if (action.changesAt)
      rules.changesAt = (row: RecordRow, context: ActionContext) =>
        asking(action, () => action.changesAt?.(seen(row), context));
    return Object.assign(Object.create(action) as RecordAction, rules);
  });
}

/**
 * `value` at `path`, its reads watched (only its reads: anything else goes
 * to `value` untouched): what was fetched reads as it is,
 * what holds fetched paths under it reads through another such view, and a
 * declared path that was not fetched is told of — and reads as it is, so a
 * rule decides exactly as it would without the proxy. `made` keeps one view
 * per object, so a nested value read twice is the same object twice.
 */
function watchingReads<T extends object>(
  value: T,
  path: string,
  paths: Paths,
  unfetched: (field: string) => void,
  made: WeakMap<object, object> = new WeakMap(),
): T {
  const kept = made.get(value);
  if (kept) return kept as T;
  const view = new Proxy(value, {
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
          ? watchingReads(found, at, paths, unfetched, made)
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
  made.set(value, view);
  return view;
}

/**
 * Whether `target[key]` must read as itself: a proxy may not stand in for
 * a property that can neither change nor be redefined.
 */
function frozenAt(target: object, key: string): boolean {
  const own = Object.getOwnPropertyDescriptor(target, key);
  return own !== undefined && !own.configurable && own.writable === false;
}
