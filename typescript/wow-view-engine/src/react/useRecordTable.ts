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

import { useCallback, useMemo, useRef } from 'react';
import type {
  FieldGroupDefinition,
  Issue,
  RecordCardSpec,
  RecordColumn,
  RecordKey,
  RecordLayout,
  RecordSort,
  RecordSummary,
  SortDirection,
  SummaryFunction,
  RuntimeLimits,
} from '../model/index.js';
import {
  columnHidden,
  columnPinned,
  isFieldlessKind,
  nearestPageSize,
} from '../model/index.js';
import { recordColumns, recordSort, recordSummaries } from './recordDraft.js';
import {
  reordered,
  repinned,
  resized,
  withColumnsShown,
} from './recordColumns.js';
import {
  cycledSort,
  offeredPageSizes,
  repairing,
  summariesOf,
} from './recordEdits.js';
import { clampPage, maxSortFields } from '../record/index.js';
import type {
  RecordCardView,
  RecordColumnView,
  RecordPaging,
  RecordRow,
  SummaryRow,
} from '../record/index.js';
import {
  hasResult,
  pendingBesides,
  type QueryStatus,
  type RecordViewRuntime,
} from '../runtime/index.js';
import type { RecordViewConfig } from '../model/index.js';
import { useViewRuntime } from './useViewEngine.js';
import {
  rowsOnScreen,
  standingAnchor,
  toggledSelection,
  type SelectionAnchor,
} from './recordSelection.js';

/** How {@link RecordTableController.toggle} treats the rows around one. */
export interface ToggleSelectionOptions {
  /**
   * Extend from the anchor — the row the last plain toggle landed on — to
   * this one, inclusive and in result order, the way Shift+click does in a
   * file manager: the range goes the way this row goes. With no anchor
   * standing among the rows on screen it is a plain toggle, and sets one.
   */
  range?: boolean;
}

/** How {@link RecordTableController.toggleSort} treats the other columns. */
export interface ToggleSortOptions {
  /** Make the cycled field the whole sort rather than joining it. */
  exclusive?: boolean;
}

export interface RecordTableController {
  /** Columns of the result on screen, which follow the executed config. */
  columns: RecordColumnView[];
  /**
   * The card layout of the same result, projected by the kernel beside the
   * columns; both halves are saved side by side.
   */
  card: RecordCardView;
  /**
   * The field that says which record a row is — the definition's row key,
   * handed on so a control that lists the columns can hold it in place
   * without reading the definition itself. Absent before a runtime exists.
   */
  rowKey?: string;
  /** The picker groups the definition declares, for the column settings. */
  fieldGroups: readonly FieldGroupDefinition[];
  /**
   * The card half of the draft, as the settings edit it — the draft rather
   * than the result's config, for the reason `columnFields` reads the
   * draft: a control shows what the user has said, and the result follows.
   */
  cardSpec: RecordCardSpec;
  /**
   * Changes the card layout — which field titles it, which make its body,
   * where its image comes from, how many stand in a row — and applies at
   * once, as `setColumns` does: the cards follow the config that ran.
   */
  setCard(patch: Partial<RecordCardSpec>): void;
  rows: RecordRow[];
  /**
   * Where the result stands: the page and size that ran, the pages the
   * pager can reach and whether a window cuts them short of the total
   * (`record/paging.ts`). The pager reads these and does no arithmetic of
   * its own.
   */
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

  /**
   * The draft's sort: what the sort editor edits, and what the next header
   * press cycles from.
   */
  sort: RecordSort[];
  /**
   * The sort the rows on screen were fetched by — the config that ran, not
   * the draft. The header's arrows and `aria-sort` say this one: while a
   * sort waits for Apply, a header pointing down over rows that go up would
   * be lying about them. Empty before anything has run.
   */
  ranSort: RecordSort[];
  sortOf(field: string): SortDirection | null;
  /**
   * Ascending, then descending, then off.
   *
   * By default the field joins the sort — a new one at the end, an existing
   * one in its place — so several columns may order the rows. With
   * `exclusive` the cycled field is the whole sort: what a plain click on a
   * header means, where Shift is what adds. Both are one `edit` and at most
   * one `apply`; imitating exclusivity with the additive form would spend
   * one query per column being cleared.
   *
   * **It runs only when nothing else waits.** A press is about the order of
   * the rows, so it applies at once as a table does — unless the draft holds
   * another edit waiting for Apply, a range condition above all. Running
   * then would apply that edit on the user's behalf; the sort joins it
   * instead, the pending dot shows, and Apply runs the two together
   * (`pendingBesides`, the rule the analysis header keeps).
   */
  toggleSort(field: string, options?: ToggleSortOptions): void;
  /**
   * Replaces the whole sort, in priority order — and runs under the same
   * rule as `toggleSort`: at once when nothing else waits, otherwise with
   * the edits waiting for Apply.
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
  /**
   * Fields of the draft's table layout, in order — the columns switched
   * off among them, because a hidden column keeps its place in the order
   * and a control that lists the table's columns has to list it there.
   */
  columnFields: string[];
  /** Whether the draft has this column switched off. */
  hiddenOf(field: string): boolean;
  /**
   * Which columns the table shows, and applies at once.
   *
   * A column the config already knows is switched off **in place** —
   * `hidden: true`, its entry kept — rather than taken out of the list, so
   * switching it back on puts it back where it was rather than at the end.
   * Which is also why this says nothing about the order: that is
   * {@link setColumnOrder}'s, and a column that is not in the list has no
   * order to give. A named field the config does not know yet joins at the
   * end, as a column has to start somewhere.
   *
   * A column that goes takes its summary with it: a summary belongs to a
   * column, so one left behind buys an aggregation query with nowhere to
   * appear. Each column that stays is reused as it was configured, so its
   * width and pinning survive being switched off and on again.
   *
   * The two entries that have nowhere to come back to leave the list
   * instead: a column the definition no longer offers, and a second entry
   * for a column already kept. Both are one row in the settings and both
   * are configs the kernel refuses (`record.field.unknown`,
   * `record.column.duplicate`), so switching them off is the repair it has
   * always been — hiding one would leave the query and the save blocked by
   * the control that had just run.
   */
  setColumns(fields: string[]): void;
  /**
   * Puts the draft's columns in this order and applies at once.
   *
   * A name that is not a column is ignored and a column the caller leaves
   * unnamed keeps its place at the end, so a control that knows about part
   * of the table — one area of the column settings — cannot drop the rest
   * of it by saying nothing about it.
   *
   * The columns are all of them, the switched-off ones included: a hidden
   * column has a place in the order, which is what makes switching it back
   * on put it back where it was, so a caller that means to order the whole
   * table names it along with the rest.
   */
  setColumnOrder(fields: string[]): void;
  /** Whether the draft holds this column against the table's left edge. */
  pinnedOf(field: string): boolean;
  /**
   * Holds a column against the left edge, or lets it go. Applies at once.
   *
   * There is one end to pin to (D19): the right edge is the host's action
   * column, which is a render slot rather than anything a config names.
   */
  setPinned(field: string, pinned: boolean): void;
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
   * Page sizes worth offering: the layout's ladder — `limits.pageSizes` for
   * a table, `limits.cardPageSizes` for cards, which are whole rows at every
   * width — cut to what the runtime limits admit and with the current size
   * folded in. A size above
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
  /**
   * Selects or unselects one row, or a range with `{ range: true }`. A
   * plain toggle moves the anchor a range extends from; a range does not,
   * so a second Shift+click re-draws the range from the same row. The
   * anchor belongs to the rows on screen: another page, or another
   * question applied, lets it go — a refresh of the same page keeps it.
   */
  toggle(key: RecordKey, options?: ToggleSelectionOptions): void;
  /** Selects every row of the current result, or clears the selection. */
  toggleAll(): void;
  clearSelection(): void;
  /** Picks exactly these rows, in this order. */
  select(keys: readonly RecordKey[]): void;

  /**
   * Paged sources only; a cursor source has no page numbers to jump to. A
   * page past the last reachable one lands on that one — past the end, the
   * reader wants the end, and a page beyond the source's window is one the
   * source refuses.
   */
  goTo(index: number): void;
  /**
   * Whether there is a page after this one: the next cursor for a cursor
   * source; for a paged one, the pages it can reach against the page that
   * ran. A source that reports no total cannot say, so it is taken as
   * "there may be" — up to its window, where it declares one.
   */
  hasNext: boolean;
  /** No-op at the end, where there is no next page to ask for. */
  next(): void;
  previous(): void;
  refresh(): void;
}

/** Stable identities for "no runtime yet", so memo dependencies stay still. */
const NO_SORT: RecordSort[] = [];
const NO_COLUMNS: RecordColumn[] = [];
const NO_SUMMARIES: RecordSummary[] = [];
const NO_SELECTION: RecordKey[] = [];
const NO_ROWS: RecordRow[] = [];
const NO_LAYOUTS: RecordLayout[] = [];
const NO_CARD: RecordCardView = { title: '', fields: [] };
const NO_GROUPS: readonly FieldGroupDefinition[] = [];
const NO_CARD_SPEC: RecordCardSpec = { title: '', fields: [] };

/**
 * A record view as a table renders it, with no vendor types in sight.
 *
 * Rows and columns come from the last successful result rather than the draft,
 * because the kernel projected them from the config that ran. Changing columns
 * therefore applies immediately, and so does a sort while nothing else waits
 * (`toggleSort`); changing a filter waits for submit.
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
  const ran = state?.result?.own;
  const ranSort = useMemo(() => (ran ? recordSort(ran.sort) : NO_SORT), [ran]);
  const tableColumns = useMemo(
    () => (draft ? recordColumns(draft.table?.columns) : NO_COLUMNS),
    [draft],
  );
  const summaries = useMemo(
    () => (draft ? recordSummaries(draft.summaries) : NO_SUMMARIES),
    [draft],
  );

  // One sort write, from the header or the editor. The repairs a patch
  // carries (`repairing`) are this write's own, so they are laid over both
  // sides with the sort rather than counted as someone else's edit.
  const sortNow = useCallback(
    (sort: RecordSort[]) => {
      if (!runtime) return;
      const snapshot = runtime.getSnapshot();
      const patch = repairing({ sort }, snapshot);
      const others = pendingBesides(snapshot, patch);
      runtime.edit(patch);
      if (!others) runtime.apply();
    },
    [runtime],
  );

  const toggleSort = useCallback(
    (field: string, options?: ToggleSortOptions) => {
      if (!runtime) return;
      sortNow(
        cycledSort(
          recordSort(runtime.getSnapshot().draft.sort),
          field,
          options?.exclusive ?? false,
        ),
      );
    },
    [runtime, sortNow],
  );

  const editAndApply = useCallback(
    (patch: Partial<RecordViewConfig>) => {
      if (!runtime) return;
      runtime.edit(repairing(patch, runtime.getSnapshot()));
      runtime.apply();
    },
    [runtime],
  );

  // Where the last plain toggle landed. The controller's own rather than
  // the runtime's: it is how one pair of hands is picking rows, not a fact
  // about the view, and nothing but the next toggle ever reads it.
  const anchor = useRef<SelectionAnchor | null>(null);
  const toggle = useCallback(
    (key: RecordKey, options?: ToggleSelectionOptions) => {
      if (!runtime) return;
      const snapshot = runtime.getSnapshot();
      const onScreen = rowsOnScreen(snapshot);
      const from =
        options?.range && onScreen
          ? standingAnchor(anchor.current, onScreen.rows, onScreen.mark)
          : null;
      runtime.select(
        toggledSelection(snapshot.selection, onScreen?.rows ?? [], key, from),
      );
      // A range leaves the anchor where it is; anything else is a plain
      // toggle, a Shift+click with nothing to extend from included.
      if (from === null)
        anchor.current = onScreen ? { key, rows: onScreen.mark } : null;
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

  // The paging facts are the kernel's, worked out from the page and size
  // that ran; the draft's size may be a different one still on its way.
  const hasNext = paging?.hasNext ?? false;
  const definition =
    runtime?.definition.kind === 'data' ? runtime.definition : undefined;

  return {
    columns: view?.columns ?? [],
    // Like the columns, the card follows the config that ran rather than the
    // draft, so a body field appears with the rows it belongs to.
    card: view?.card ?? NO_CARD,
    rowKey: definition?.record?.rowKey,
    fieldGroups: definition?.fieldGroups ?? NO_GROUPS,
    cardSpec: state?.draft.card ?? NO_CARD_SPEC,
    setCard: useCallback(
      (patch: Partial<RecordCardSpec>) => {
        if (!runtime) return;
        editAndApply({
          card: { ...runtime.getSnapshot().draft.card, ...patch },
        });
      },
      [editAndApply, runtime],
    ),
    rows,
    paging,
    summaries: data?.kind === 'record' ? data.summaries : null,
    status: state?.query.status ?? 'idle',
    error: state?.query.error ?? null,
    loading: state?.query.status === 'loading',
    hasResult: hasResult(state),

    sort,
    sortOf: useCallback(
      (field: string) =>
        sort.find(entry => entry.field === field)?.direction ?? null,
      [sort],
    ),
    ranSort,
    toggleSort,
    setSort: sortNow,
    // The kernel owns the rule; the controller only hands it on, so the
    // ceiling a control stops at is the one `validateRecord` refuses past.
    maxSortFields: definition ? maxSortFields(definition) : 0,

    layout: state?.draft.layout ?? 'table',
    layouts: definition?.record?.layouts ?? NO_LAYOUTS,
    setLayout: useCallback(
      (layout: RecordLayout) => {
        if (!runtime) return;
        // The page size moves to the nearest rung of the new layout's
        // ladder in the same edit: a page of cards is whole rows, a page of
        // a table is the product's ladder, and the two meet at their nearest
        // (20 rows come back as 24 cards and go back as 20). A size already
        // on the ladder stays. A new size is a new query, so it runs.
        const current = runtime.getSnapshot().draft.pageSize;
        const size = nearestPageSize(
          ladderOf(runtime.limits, layout).filter(
            rung => rung <= runtime.limits.maxPageSize,
          ),
          current,
        );
        if (size !== current) {
          editAndApply({ layout, pageSize: size });
          return;
        }
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
      [editAndApply, runtime],
    ),
    columnFields: useMemo(
      () => tableColumns.map(column => column.field),
      [tableColumns],
    ),
    // Read through `columnHidden`, so a config that came out of a store
    // saying `hidden: 'yes'` answers "shown" rather than making the type
    // this declares a lie. `validateRecord` reports the value separately.
    hiddenOf: useCallback(
      (field: string) =>
        columnHidden(
          tableColumns.find(column => column.field === field)?.hidden,
        ),
      [tableColumns],
    ),
    setColumns: useCallback(
      (fields: string[]) => {
        if (!runtime) return;
        const state = runtime.getSnapshot();
        const visible = new Set(fields);
        // What a switched-off column can come back to: a field the
        // definition still offers, and that a row actually holds.
        const offered = new Set(
          (runtime.definition.kind === 'data' ? runtime.definition.fields : [])
            .filter(field => !isFieldlessKind(field.kind))
            .map(field => field.name),
        );
        // A summary belongs to a column, so a column that goes takes its
        // summary with it — in this one update. Left behind, the runtime
        // keeps asking for an aggregate with nowhere to appear: the scope
        // row stands empty, a failed aggregate warns about a summary nobody
        // can see, and the settings disable the select that would clear it.
        editAndApply({
          table: {
            columns: withColumnsShown(
              recordColumns(state.draft.table?.columns),
              fields,
              offered,
            ),
          },
          summaries: summariesOf(
            recordSummaries(state.draft.summaries).filter(summary =>
              visible.has(summary.field),
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
        editAndApply({
          table: {
            columns: reordered(
              recordColumns(runtime.getSnapshot().draft.table?.columns),
              fields,
            ),
          },
        });
      },
      [editAndApply, runtime],
    ),
    // Read through `columnPinned`, so the type this declares is true even of
    // a config that came out of a store saying `pinned: 'left'`.
    pinnedOf: useCallback(
      (field: string) =>
        columnPinned(
          tableColumns.find(column => column.field === field)?.pinned,
        ),
      [tableColumns],
    ),
    setPinned: useCallback(
      (field: string, pinned: boolean) => {
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
    pageSizes: useMemo(
      () =>
        offeredPageSizes(
          ladderOf(runtime?.limits, state?.draft.layout ?? 'table'),
          runtime?.limits.maxPageSize,
          pageSize,
        ),
      [pageSize, runtime, state?.draft.layout],
    ),
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
    select: useCallback(
      (keys: readonly RecordKey[]) => runtime?.select([...keys]),
      [runtime],
    ),

    // Page numbers only mean something to a paged source.
    goTo: useCallback(
      (index: number) => {
        if (paging?.mode !== 'paged') return;
        runtime?.page({ index: clampPage(paging, index) });
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

/** The page-size ladder a layout offers from (`RuntimeLimits`). */
function ladderOf(
  limits: RuntimeLimits | undefined,
  layout: RecordLayout,
): readonly number[] {
  if (!limits) return [];
  return layout === 'card' ? limits.cardPageSizes : limits.pageSizes;
}
