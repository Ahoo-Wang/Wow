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

import { useCallback, useMemo } from 'react';
import type {
  Issue,
  RecordKey,
  RecordLayout,
  RecordSort,
  SortDirection,
} from '../model/index.js';
import type {
  RecordColumnView,
  RecordPaging,
  RecordRow,
  SummaryRow,
} from '../record/index.js';
import type { QueryStatus, RecordViewRuntime } from '../runtime/index.js';
import type { RecordViewConfig } from '../model/index.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * The card layout of the result on screen, resolved against the definition.
 *
 * A card is not a narrow table: the saved config says which field titles it,
 * which fields make up its body and where its image comes from, and none of
 * that is derivable from the table's columns.
 */
export interface RecordCardView {
  /** Field whose value titles each card; the row key when it holds none. */
  title: string;
  /** Fields of the card body, in order, with their labels resolved. */
  fields: RecordCardField[];
  /** Field holding an image URL, when the config asks for one. */
  image?: string;
  columns?: 1 | 2 | 3 | 4;
}

export interface RecordCardField {
  field: string;
  label: string;
}

export interface RecordTableController {
  /** Columns of the result on screen, which follow the executed config. */
  columns: RecordColumnView[];
  /** The card layout of the same result; both are saved side by side. */
  card: RecordCardView;
  rows: RecordRow[];
  paging: RecordPaging | null;
  summaries: SummaryRow | null;
  status: QueryStatus;
  error: Issue | null;
  /** True while a query is in flight and rows are still the previous ones. */
  loading: boolean;

  sort: RecordSort[];
  sortOf(field: string): SortDirection | null;
  /** Ascending, then descending, then off. Applies at once, like a table does. */
  toggleSort(field: string): void;

  layout: RecordLayout;
  setLayout(layout: RecordLayout): void;
  /** Fields of the draft's table layout, in order. */
  columnFields: string[];
  setColumns(fields: string[]): void;
  pageSize: number;
  setPageSize(size: number): void;

  selection: RecordKey[];
  isSelected(key: RecordKey): boolean;
  toggle(key: RecordKey): void;
  /** Selects every row of the current result, or clears the selection. */
  toggleAll(): void;
  clearSelection(): void;

  /** Paged sources only; a cursor source has no page numbers to jump to. */
  goTo(index: number): void;
  /**
   * Whether there is a page after this one: the next cursor for a cursor
   * source, and the total against the page reached for a paged one. A source
   * that reports no total cannot say, so it is taken as "there may be".
   */
  hasNext: boolean;
  /** No-op at the end, where there is no next page to ask for. */
  next(): void;
  previous(): void;
  refresh(): void;
}

/** Stable identities for "no runtime yet", so memo dependencies stay still. */
const NO_SORT: RecordSort[] = [];
const NO_SELECTION: RecordKey[] = [];
const NO_CARD: RecordCardView = { title: '', fields: [] };

/**
 * A record view as a table renders it, with no vendor types in sight.
 *
 * Rows and columns come from the last successful result rather than the draft,
 * because the kernel projected them from the config that ran. Changing sort or
 * columns therefore applies immediately; changing a filter waits for submit.
 */
export function useRecordTable(
  runtime: RecordViewRuntime | null,
): RecordTableController {
  const state = useViewRuntime(runtime);
  const data = state?.result?.data;
  const view = data?.kind === 'record' ? data.view : null;

  const rows = useMemo(() => view?.rows ?? [], [view]);
  const selection = useMemo(() => state?.selection ?? NO_SELECTION, [state]);
  const selected = useMemo(() => new Set(selection), [selection]);
  const sort = state?.draft.sort ?? NO_SORT;

  const toggleSort = useCallback(
    (field: string) => {
      if (!runtime) return;
      const current = runtime.getSnapshot().draft.sort;
      const at = current.findIndex(entry => entry.field === field);
      // A new field joins at the end; an existing one keeps its place, because
      // the order of `sort` is the priority between columns.
      const next: RecordSort[] =
        at < 0
          ? [...current, { field, direction: 'ASC' }]
          : current[at].direction === 'ASC'
            ? current.map((entry, index) =>
                index === at ? { field, direction: 'DESC' } : entry,
              )
            : current.filter((_entry, index) => index !== at);
      runtime.edit({ sort: next });
      runtime.apply();
    },
    [runtime],
  );

  const editAndApply = useCallback(
    (patch: Partial<RecordViewConfig>) => {
      if (!runtime) return;
      runtime.edit(patch);
      runtime.apply();
    },
    [runtime],
  );

  const toggle = useCallback(
    (key: RecordKey) => {
      if (!runtime) return;
      const current = runtime.getSnapshot().selection;
      runtime.select(
        current.includes(key)
          ? current.filter(entry => entry !== key)
          : [...current, key],
      );
    },
    [runtime],
  );

  const toggleAll = useCallback(() => {
    if (!runtime) return;
    const keys = runtime.getSnapshot().result?.data;
    const all =
      keys?.kind === 'record' ? keys.view.rows.map(row => row.key) : [];
    runtime.select(
      runtime.getSnapshot().selection.length === all.length ? [] : all,
    );
  }, [runtime]);

  const paging = view?.paging ?? null;
  const pageSize = state?.draft.pageSize ?? 0;

  // Like the columns, the card follows the config that ran rather than the
  // draft, so a body field appears with the rows it belongs to. Labels come
  // from the definition; the kernel projects columns, not cards.
  const cardSpec = state?.result?.config.card ?? null;
  const fields = runtime?.definition.fields;
  const card = useMemo<RecordCardView>(() => {
    if (!cardSpec) return NO_CARD;
    const byName = new Map((fields ?? []).map(field => [field.name, field]));
    return {
      title: cardSpec.title,
      // A field the definition dropped is left out rather than shown as a
      // blank row; `validateRecord` reports it separately.
      fields: cardSpec.fields.flatMap(name => {
        const field = byName.get(name);
        return field ? [{ field: name, label: field.label }] : [];
      }),
      ...(cardSpec.image === undefined ? {} : { image: cardSpec.image }),
      ...(cardSpec.columns === undefined ? {} : { columns: cardSpec.columns }),
    };
  }, [cardSpec, fields]);

  const hasNext =
    paging === null
      ? false
      : paging.mode === 'cursor'
        ? paging.nextCursor !== null
        : // A source that returns no total cannot rule the next page out.
          paging.total === undefined ||
          pageSize <= 0 ||
          paging.index * pageSize < paging.total;

  return {
    columns: view?.columns ?? [],
    card,
    rows,
    paging,
    summaries: data?.kind === 'record' ? data.summaries : null,
    status: state?.query.status ?? 'idle',
    error: state?.query.error ?? null,
    loading: state?.query.status === 'loading',

    sort,
    sortOf: useCallback(
      (field: string) =>
        sort.find(entry => entry.field === field)?.direction ?? null,
      [sort],
    ),
    toggleSort,

    layout: state?.draft.layout ?? 'table',
    setLayout: useCallback(
      (layout: RecordLayout) => runtime?.edit({ layout }),
      [runtime],
    ),
    columnFields: useMemo(
      () => (state?.draft.table.columns ?? []).map(column => column.field),
      [state],
    ),
    setColumns: useCallback(
      (fields: string[]) => {
        if (!runtime) return;
        // Reuse each column as it was configured: rebuilding from the field
        // name alone would drop its width and pinning on the next save.
        const existing = new Map(
          runtime
            .getSnapshot()
            .draft.table.columns.map(column => [column.field, column]),
        );
        editAndApply({
          table: {
            columns: fields.map(field => existing.get(field) ?? { field }),
          },
        });
      },
      [editAndApply, runtime],
    ),
    pageSize,
    setPageSize: useCallback(
      (pageSize: number) => editAndApply({ pageSize }),
      [editAndApply],
    ),

    selection,
    isSelected: useCallback((key: RecordKey) => selected.has(key), [selected]),
    toggle,
    toggleAll,
    clearSelection: useCallback(() => runtime?.select([]), [runtime]),

    // Page numbers only mean something to a paged source.
    goTo: useCallback(
      (index: number) => {
        if (paging?.mode !== 'paged') return;
        runtime?.page({ index });
      },
      [runtime, paging],
    ),
    hasNext,
    next: useCallback(() => {
      if (!runtime || !paging || !hasNext) return;
      // Which move "next" is depends on the protocol the definition declared;
      // past the last page there is nothing to ask either source for.
      if (paging.mode === 'cursor') {
        runtime.page({ cursor: paging.nextCursor });
        return;
      }
      runtime.page({ index: paging.index + 1 });
    }, [runtime, paging, hasNext]),
    previous: useCallback(() => {
      if (!runtime || paging?.mode !== 'paged' || paging.index <= 1) return;
      runtime.page({ index: paging.index - 1 });
    }, [runtime, paging]),
    refresh: useCallback(() => runtime?.refresh(), [runtime]),
  };
}
