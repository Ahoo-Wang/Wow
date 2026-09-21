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
  FieldDefinition,
  FieldOption,
  Issue,
  NumberFormat,
  RecordColumn,
  RecordKey,
  RecordLayout,
  RecordSort,
  RecordSummary,
  SortDirection,
  SummaryFunction,
  ViewInstance,
} from '../model/index.js';
import { columnPin, type RecordColumnPin } from '../model/index.js';
import {
  recordColumns,
  recordSort,
  recordSummaries,
  wasSound,
} from './recordDraft.js';
import { maxSortFields } from '../record/index.js';
// The side a column is held on, named once in the model and offered here so
// a control can talk about pinning without importing the kernel's types.
export type { RecordColumnPin };
import type {
  RecordColumnView,
  RecordPaging,
  RecordRow,
  SummaryRow,
} from '../record/index.js';
import type {
  QueryStatus,
  RecordViewRuntime,
  ViewRuntimeState,
} from '../runtime/index.js';
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
  /** How the title's values show, when the definition still has the field. */
  titleField?: RecordCardField;
  /** Fields of the card body, in order, with their labels resolved. */
  fields: RecordCardField[];
  /** Field holding an image URL, when the config asks for one. */
  image?: string;
  columns?: 1 | 2 | 3 | 4;
}

export interface RecordCardField {
  field: string;
  label: string;
  /**
   * What the definition says about the field, so a value on a card shows as
   * it does in the field's column. Optional for a controller built by hand.
   */
  kind?: string;
  cell?: string;
  options?: readonly FieldOption[];
  numberFormat?: NumberFormat;
}

/**
 * One column with its pinning changed.
 *
 * The member is rebuilt rather than spread over, because a config is JSON
 * and `{ pinned: undefined }` is not the same object as one without the key:
 * it survives a `dequal` against the saved baseline as a difference, and a
 * view that was only unpinned back to where it started would stay marked as
 * unsaved for the rest of the session.
 */
function repinned(
  column: RecordColumn,
  pinned: RecordColumnPin | null,
): RecordColumn {
  return {
    field: column.field,
    ...(column.width === undefined ? {} : { width: column.width }),
    ...(pinned === null ? {} : { pinned }),
  };
}

/**
 * One column with its width changed, rebuilt for the same reason
 * {@link repinned} is: a config is JSON, and a column back at its automatic
 * width has no `width` key rather than a `width` of `undefined`.
 */
function resized(column: RecordColumn, width: number | null): RecordColumn {
  return {
    field: column.field,
    ...(width === null ? {} : { width }),
    ...(column.pinned === undefined ? {} : { pinned: column.pinned }),
  };
}

/**
 * The summaries to write, in the shape the saved config uses for none.
 *
 * `summaries` is optional, so "no summaries" is spelled two ways — an empty
 * list, or no member at all — and `dirty` is an equality against the saved
 * config, which cannot tell the difference between a shape and a change.
 * Adding a summary and taking it away again therefore left the view unsaved
 * for the rest of the session, with the leave guard asking about an edit
 * that had already been undone. `edit` removes a member given as
 * `undefined`, so answering with the saved config's own spelling makes
 * undoing an undo.
 */
function summariesOf(
  next: RecordSummary[],
  saved: ViewInstance | null,
): RecordSummary[] | undefined {
  if (next.length > 0) return next;
  const config = saved?.config;
  return config?.kind === 'record' && config.summaries !== undefined
    ? []
    : undefined;
}

/**
 * The patch, plus the sound form of any list the draft could not be read
 * from and this patch does not already replace.
 *
 * A list the controller had to repair is a config the kernel refuses over
 * entries that are not on screen — they could not be read, so no control
 * lists them and no control can take them out. Carrying the repair along
 * with whatever the user *did* change is what makes "the first change they
 * make writes the sound list back" true of every list rather than only of
 * the one they touched: with no sortable field left to add, an unreadable
 * `sort` had no other way out at all.
 */
function repairing(
  patch: Partial<RecordViewConfig>,
  state: ViewRuntimeState<RecordViewConfig>,
): Partial<RecordViewConfig> {
  const draft = state.draft;
  const repairs: Partial<RecordViewConfig> = {};

  const sort = recordSort(draft.sort);
  if (patch.sort === undefined && !wasSound(draft.sort, sort))
    repairs.sort = sort;

  const summaries = recordSummaries(draft.summaries);
  if (
    !('summaries' in patch) &&
    draft.summaries !== undefined &&
    !wasSound(draft.summaries, summaries)
  )
    repairs.summaries = summariesOf(summaries, state.saved);

  const columns = recordColumns(draft.table?.columns);
  if (patch.table === undefined && !wasSound(draft.table?.columns, columns))
    repairs.table = { columns };

  return { ...repairs, ...patch };
}

function cardField(field: FieldDefinition): RecordCardField {
  return {
    field: field.name,
    label: field.label,
    kind: field.kind,
    cell: field.cell ?? field.kind,
    ...(field.options ? { options: field.options } : {}),
    ...(field.numberFormat ? { numberFormat: field.numberFormat } : {}),
  };
}

/** How {@link RecordTableController.toggleSort} treats the other columns. */
export interface ToggleSortOptions {
  /** Make the cycled field the whole sort rather than joining it. */
  exclusive?: boolean;
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
  /**
   * Whether a result ever came back for this view — the last one that did,
   * however old it is by now.
   *
   * It is not `rows.length > 0` and not `status === 'success'`: a failed
   * refresh keeps the rows it could not replace and turns the status to
   * `error`, and a result that matched nothing has no rows. What it answers
   * is the one question a table cannot answer from rows alone — is there a
   * result underneath this status, or is the frame empty because nothing has
   * ever been executed here.
   */
  hasResult: boolean;

  sort: RecordSort[];
  sortOf(field: string): SortDirection | null;
  /**
   * Ascending, then descending, then off. Applies at once, like a table does.
   *
   * By default the field joins the sort — a new one at the end, an existing
   * one in its place — so several columns may order the rows. With
   * `exclusive` the cycled field is the whole sort: what a plain click on a
   * header means, where Shift is what adds. Both are one `edit` and one
   * `apply`; imitating exclusivity with the additive form would spend one
   * query per column being cleared.
   */
  toggleSort(field: string, options?: ToggleSortOptions): void;
  /**
   * Replaces the whole sort, in priority order, and applies at once.
   *
   * `toggleSort` is one column's answer and can only append; an editor that
   * shows the sort as a list needs to say which field comes first, flip one
   * of them and drop one of them, and all three are the same write.
   */
  setSort(sort: RecordSort[]): void;
  /**
   * How many fields this view may sort on at once.
   *
   * A cursor is a position in one total order, and Wow bounds how many
   * fields that order may be built from, so `validateRecord` refuses a
   * longer sort and `apply` never runs: the rows keep the order they had and
   * the view sits in an error the user did not ask for. A control that
   * offers a field therefore has to stop at the ceiling. A paged source has
   * no such bound, and answers with the number of fields it could sort on.
   */
  maxSortFields: number;

  layout: RecordLayout;
  /**
   * The layouts the definition allows, in its order. A switcher offers these
   * and nothing else — and renders nothing at all below two.
   */
  layouts: RecordLayout[];
  setLayout(layout: RecordLayout): void;
  /** Fields of the draft's table layout, in order. */
  columnFields: string[];
  /**
   * Which columns the table shows, in order, and applies at once.
   *
   * A column that goes takes its summary with it: a summary belongs to a
   * column, so one left behind buys an aggregation query with nowhere to
   * appear. Each column that stays is reused as it was configured, so its
   * width and pinning survive.
   */
  setColumns(fields: string[]): void;
  /**
   * Puts the draft's columns in this order and applies at once.
   *
   * A name that is not a column is ignored and a column the caller leaves
   * unnamed keeps its place at the end, so a control that knows about part
   * of the table — one area of the column settings — cannot drop the rest
   * of it by saying nothing about it.
   */
  setColumnOrder(fields: string[]): void;
  /** Which side the draft holds a column on, or null when it is unpinned. */
  pinnedOf(field: string): RecordColumnPin | null;
  /** Holds a column on one side of the table, or lets it go. Applies at once. */
  setPinned(field: string, pinned: RecordColumnPin | null): void;
  /**
   * Sets one column's width in pixels, or `null` to let it size itself
   * again. Applies at once, like the other column commands.
   *
   * The number is taken as given: how narrow a column may be dragged is a
   * question about a grab handle rather than about a config, so the table's
   * own floor lives with the handle. What the kernel refuses is a width that
   * is not a positive finite number of pixels.
   */
  setColumnWidth(field: string, width: number | null): void;
  /** The function the draft summarises a column with, if any. */
  summaryOf(field: string): SummaryFunction | null;
  /**
   * Every field the draft's summaries name, in the order they name them and
   * without a repeat.
   *
   * It is not the column list: a config may summarise a field that is not a
   * column and that the definition no longer declares, and the settings can
   * only offer the way out of such a summary if they are told it is there.
   */
  summaryFields: string[];
  /**
   * Replaces whatever a column summarised with one function, or with none.
   *
   * A config may carry several functions for one field and the table shows
   * all of them; this writes one, because a control that offers a column one
   * summary is the shape the settings have. Setting one therefore drops the
   * others on that column, and `null` leaves it without a summary.
   */
  setSummary(field: string, fn: SummaryFunction | null): void;
  pageSize: number;
  /**
   * Page sizes worth offering: the standard ladder, cut to what the runtime
   * limits admit and with the current size folded in. A size above
   * `maxPageSize` is refused by `validateRecord`, so offering it would be
   * offering a way to break the view — and the saved size has to be in the
   * list whatever it is, or a select shows nothing at all.
   */
  pageSizes: number[];
  setPageSize(size: number): void;

  selection: RecordKey[];
  /**
   * The selected rows of the current result, in result order. A bulk action
   * needs the rows and not only their keys — it names what it is about to
   * act on — and the selection is keys alone.
   */
  selectedRows: RecordRow[];
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

/**
 * The ladder a page-size control offers from, before the limits cut it and
 * the current size is folded in.
 */
const PAGE_SIZES = [10, 20, 50, 100];

/** Stable identities for "no runtime yet", so memo dependencies stay still. */
const NO_SORT: RecordSort[] = [];
const NO_COLUMNS: RecordColumn[] = [];
const NO_SUMMARIES: RecordSummary[] = [];
const NO_SELECTION: RecordKey[] = [];
const NO_ROWS: RecordRow[] = [];
const NO_LAYOUTS: RecordLayout[] = [];
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
  // Result order, not click order: a bulk action lists what it will touch,
  // and the list has to read like the table above it.
  const selectedRows = useMemo(
    () =>
      selection.length === 0
        ? NO_ROWS
        : rows.filter(row => selected.has(row.key)),
    [rows, selected, selection],
  );
  // Read through `recordDraft`, so what this hands the UI is always a
  // list of well-formed entries whatever the store held — see the rule
  // on that module, and `docs/design/react.md`.
  const draft = state?.draft;
  const sort = useMemo(
    () => (draft ? recordSort(draft.sort) : NO_SORT),
    [draft],
  );
  const tableColumns = useMemo(
    () => (draft ? recordColumns(draft.table?.columns) : NO_COLUMNS),
    [draft],
  );
  const summaries = useMemo(
    () => (draft ? recordSummaries(draft.summaries) : NO_SUMMARIES),
    [draft],
  );

  const toggleSort = useCallback(
    (field: string, options?: ToggleSortOptions) => {
      if (!runtime) return;
      const current = recordSort(runtime.getSnapshot().draft.sort);
      const at = current.findIndex(entry => entry.field === field);
      // The same cycle either way: off → ascending → descending → off.
      const turned: SortDirection | null =
        at < 0 ? 'ASC' : current[at].direction === 'ASC' ? 'DESC' : null;
      let next: RecordSort[];
      if (options?.exclusive) {
        // This column alone, wherever it stood: a plain click says "order
        // the rows by this", not "also by this".
        next = turned === null ? [] : [{ field, direction: turned }];
      } else if (at < 0) {
        // A new field joins at the end; an existing one keeps its place,
        // because the order of `sort` is the priority between columns.
        next = [...current, { field, direction: 'ASC' }];
      } else if (turned === null) {
        next = current.filter((_entry, index) => index !== at);
      } else {
        next = current.map((entry, index) =>
          index === at ? { field, direction: turned } : entry,
        );
      }
      runtime.edit({ sort: next });
      runtime.apply();
    },
    [runtime],
  );

  const editAndApply = useCallback(
    (patch: Partial<RecordViewConfig>) => {
      if (!runtime) return;
      runtime.edit(repairing(patch, runtime.getSnapshot()));
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
    const titleField = byName.get(cardSpec.title);
    return {
      title: cardSpec.title,
      ...(titleField ? { titleField: cardField(titleField) } : {}),
      // A field the definition dropped is left out rather than shown as a
      // blank row; `validateRecord` reports it separately.
      fields: cardSpec.fields.flatMap(name => {
        const field = byName.get(name);
        return field ? [cardField(field)] : [];
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
    hasResult: state?.result != null,

    sort,
    sortOf: useCallback(
      (field: string) =>
        sort.find(entry => entry.field === field)?.direction ?? null,
      [sort],
    ),
    toggleSort,
    setSort: useCallback(
      (sort: RecordSort[]) => editAndApply({ sort }),
      [editAndApply],
    ),
    // The kernel owns the rule; the controller only hands it on, so the
    // ceiling a control stops at is the one `validateRecord` refuses past.
    maxSortFields:
      runtime?.definition.kind === 'data'
        ? maxSortFields(runtime.definition)
        : 0,

    layout: state?.draft.layout ?? 'table',
    layouts:
      runtime?.definition.kind === 'data'
        ? (runtime.definition.record?.layouts ?? NO_LAYOUTS)
        : NO_LAYOUTS,
    setLayout: useCallback(
      (layout: RecordLayout) => {
        if (!runtime) return;
        runtime.edit({ layout });
        // Both layouts draw the same result, so switching normally needs
        // no query. A view whose saved layout the definition no longer
        // allows has no result at all, though: `apply` was refused on
        // open, `refresh` is a no-op until something has been admitted,
        // and the switch that repairs it would otherwise leave the screen
        // as empty as it found it.
        const state = runtime.getSnapshot();
        if (state.result === null && state.query.status === 'idle')
          runtime.apply();
      },
      [runtime],
    ),
    columnFields: useMemo(
      () => tableColumns.map(column => column.field),
      [tableColumns],
    ),
    setColumns: useCallback(
      (fields: string[]) => {
        if (!runtime) return;
        // Reuse each column as it was configured: rebuilding from the field
        // name alone would drop its width and pinning on the next save.
        const existing = new Map(
          recordColumns(runtime.getSnapshot().draft.table?.columns).map(
            column => [column.field, column],
          ),
        );
        // A summary belongs to a column, so a column that goes takes its
        // summary with it — in this one update. Left behind, the runtime
        // keeps asking for an aggregate with nowhere to appear: the scope
        // row stands empty, a failed aggregate warns about a summary nobody
        // can see, and the settings disable the select that would clear it.
        const shown = new Set(fields);
        const state = runtime.getSnapshot();
        editAndApply({
          table: {
            columns: fields.map(field => existing.get(field) ?? { field }),
          },
          summaries: summariesOf(
            recordSummaries(state.draft.summaries).filter(summary =>
              shown.has(summary.field),
            ),
            state.saved,
          ),
        });
      },
      [editAndApply, runtime],
    ),
    setColumnOrder: useCallback(
      (fields: string[]) => {
        if (!runtime) return;
        const columns = recordColumns(
          runtime.getSnapshot().draft.table?.columns,
        );
        const byField = new Map(columns.map(column => [column.field, column]));
        const named = new Set<string>();
        const ordered = fields.flatMap(field => {
          const column = byField.get(field);
          // A name repeated by the caller would otherwise become a second
          // column of the same field, which `validateRecord` then refuses.
          if (!column || named.has(field)) return [];
          named.add(field);
          return [column];
        });
        editAndApply({
          table: {
            columns: [
              ...ordered,
              ...columns.filter(column => !named.has(column.field)),
            ],
          },
        });
      },
      [editAndApply, runtime],
    ),
    // Read through `columnPin`, so the type this declares is true even of a
    // config that came out of a store saying `pinned: 'top'`.
    pinnedOf: useCallback(
      (field: string) =>
        columnPin(tableColumns.find(column => column.field === field)?.pinned),
      [tableColumns],
    ),
    setPinned: useCallback(
      (field: string, pinned: RecordColumnPin | null) => {
        if (!runtime) return;
        editAndApply({
          table: {
            columns: recordColumns(
              runtime.getSnapshot().draft.table?.columns,
            ).map(column =>
              column.field === field ? repinned(column, pinned) : column,
            ),
          },
        });
      },
      [editAndApply, runtime],
    ),
    setColumnWidth: useCallback(
      (field: string, width: number | null) => {
        if (!runtime) return;
        editAndApply({
          table: {
            columns: recordColumns(
              runtime.getSnapshot().draft.table?.columns,
            ).map(column =>
              column.field === field ? resized(column, width) : column,
            ),
          },
        });
      },
      [editAndApply, runtime],
    ),
    summaryOf: useCallback(
      (field: string) =>
        summaries.find(entry => entry.field === field)?.fn ?? null,
      [summaries],
    ),
    summaryFields: useMemo(
      () => [...new Set(summaries.map(entry => entry.field))],
      [summaries],
    ),
    setSummary: useCallback(
      (field: string, fn: SummaryFunction | null) => {
        if (!runtime) return;
        const state = runtime.getSnapshot();
        const rest = recordSummaries(state.draft.summaries).filter(
          entry => entry.field !== field,
        );
        editAndApply({
          summaries: summariesOf(
            fn === null ? rest : [...rest, { field, fn }],
            state.saved,
          ),
        });
      },
      [editAndApply, runtime],
    ),
    pageSize,
    pageSizes: useMemo(() => {
      const max = runtime?.limits.maxPageSize;
      const offered = PAGE_SIZES.filter(
        size => max === undefined || size <= max,
      );
      // The saved size joins whatever it is: a view saved at 500 under an
      // older limit still has to show the size it is running at.
      return [
        ...new Set([...offered, ...(pageSize > 0 ? [pageSize] : [])]),
      ].sort((left, right) => left - right);
    }, [pageSize, runtime]),
    setPageSize: useCallback(
      (pageSize: number) => editAndApply({ pageSize }),
      [editAndApply],
    ),

    selection,
    selectedRows,
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
