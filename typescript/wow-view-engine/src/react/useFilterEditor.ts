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
  FieldGroupDefinition,
  FilterGroupOperator,
  FilterLeaf,
  FilterMode,
  FilterNode,
  FilterOperatorName,
  FilterTree,
  Issue,
  ViewConfig,
} from '../model/index.js';
import {
  clearFilter,
  conditions,
  countLeaves,
  describeFilter,
  impliedDeletion,
  insertAt,
  filterIndexes,
  isRootFilterIssue,
  isSimpleTree,
  negateAt,
  nodeAt,
  operatorsOf,
  removeConditionAt,
  sameFilterNode,
  unmarkedErrors,
  updateAt,
  type EditorDescriptor,
  type FieldKindRegistry,
  type FilterPath,
  type FilterSummaryItem,
  isFilterGroup,
} from '../filter/index.js';
import {
  hasAsked,
  type OptionSource,
  type ValueCandidateSource,
  type ViewRuntime,
  type ViewRuntimeState,
} from '../runtime/index.js';
import {
  comparePending,
  filterOverBudget,
  type PendingReport,
} from '../runtime/pending.js';
import { VALUE_CANDIDATE_OPERATORS } from '../analysis/index.js';
import { useViewRuntime } from './useViewEngine.js';

/**
 * One view's filter editor: a tree to edit, plus the things that belong to
 * the view around it — which mode it shows, what is currently applied, and
 * when to run.
 */
export interface FilterEditorController extends FilterTreeController {
  mode: FilterMode;
  /**
   * The view's own conditions the rows on screen were fetched under, for a
   * summary bar. It reads the config the result carries rather than `applied`,
   * so the bar always describes the data beside it: applying starts a query,
   * and until it answers, `applied` has already moved on. Before any result
   * exists it describes the question on its way, or the one that failed —
   * there are no rows for it to contradict, and the bar stands in its place
   * from the first query rather than appearing as the answer lands. Empty
   * while nothing has been asked (`hasAsked`).
   *
   * It describes `result.own`, not `result.config`: under a host scope filter
   * the two differ, and only the first is addressed by the paths this editor
   * takes — so `clearValue(item.path)` takes out the condition the badge names
   * (each of `item.paths`, for a segment). The scope's own conditions are
   * `scoped`.
   *
   * A dashboard has no conditions of its own to describe (D27): a reader
   * narrows it through its filters, which are a bar of their own, so this
   * is empty there. What it holds in force besides is `fixed`.
   *
   * A draft over the tree budget does not empty it. What the rows came back
   * under was admitted before it ran, so it is within budget whatever the
   * draft has since become, and a summary that blanked while the user edited
   * would stop describing the data it sits beside.
   */
  applied: FilterSummaryItem[];
  /**
   * The host's own conditions, in force alongside `applied` but not this
   * editor's to change: `setScopeFilter` injects them, the draft never holds
   * them, and no path here addresses them. A bar shows them as plain items,
   * with no remove — `clearValue` cannot reach them, and offering it would
   * promise a narrowing the user cannot undo. Empty when no scope is injected.
   */
  scoped: FilterSummaryItem[];
  /**
   * A dashboard's fixed scope (「固定范围」, `DashboardViewConfig.fixed`, D26
   * Q31): the board's own condition every panel runs under, in force beside
   * its filters and no reader's to change — so, like `scoped`, a bar shows it
   * with no remove, and no path here addresses it. Read off `applied`, as a
   * dashboard's `applied` is. Empty for any other view, and on a board built
   * since batch C.
   */
  fixed: FilterSummaryItem[];
  /**
   * Readings in force that nobody wrote: a declared deletion dimension the
   * view's own conditions and the host's scope both leave unanswered reads
   * as "not deleted" (D17-2), and a bar that kept quiet about it would be
   * describing rows it does not show. Like `scoped`, nothing here is the
   * editor's to remove; unlike it, adding the field is how it changes.
   */
  implied: FilterSummaryItem[];
  count: number;
  /** False when the tree needs the advanced editor to be shown faithfully. */
  simple: boolean;
  /**
   * True when the draft says something other than what was last applied —
   * anywhere in the config, not only in the conditions (D17-6). Apply runs
   * the whole draft, so its dot answers for the whole draft: a sort or a
   * page size whose apply was refused, an analysis editor's groups waiting
   * for Run, a filter mode switched and not applied. See `comparePending`.
   */
  pending: boolean;
  /**
   * How many edits wait for Apply; a badge count. The conditions count node
   * by node, both ways — a path the draft has and the applied tree does not
   * is a new condition, and a path only the applied tree has is one removed
   * since, so a cleared filter counts every condition it dropped — and every
   * other member of the config that differs counts as one.
   */
  pendingCount: number;
  /**
   * Whether the conditions themselves differ from the ones applied. This is
   * what `discard` can put back, so the offer to discard follows it rather
   * than `pending`: a sort waiting for apply is pending, and discarding the
   * conditions would not touch it.
   */
  conditionsPending: boolean;
  /**
   * Whether the node at `path` has been edited since the last apply. Compares
   * that node alone — a leaf by field, operator and value, a group by its
   * operator — so one edited condition marks one pill, not its ancestors too.
   * A node the applied tree has nothing at is pending: it is new. A node only
   * the applied tree has is pending too: it is gone.
   */
  isPending(path: FilterPath): boolean;
  /**
   * How many `issues` block apply by addressing a condition of this filter.
   * Every one of them counts, including the ones the panel can draw no pill
   * for — a malformed node has nothing to render — because Apply is refused
   * for those just the same, and a count that left them out would be a
   * button that stays disabled with nothing to show for it. What has no pill
   * is said in the strip above the editor (`unmarkedErrors`), so the count
   * never points at a fix that is nowhere on screen.
   */
  blocked: number;
  /**
   * The blocking errors the panel can draw on no pill, which is where the
   * count above points when it points at nothing: a malformed node has
   * nothing to render, a group wears no marker of its own, and an error
   * about the rest of the config — a column the definition dropped, a page
   * size it no longer admits — was never this editor's to mark. A surface
   * shows these in the strip above the editor.
   *
   * The pair of `blocked`: together they account for every error that stops
   * the view, one on the pills and one in the strip, with neither said twice.
   */
  unmarked: Issue[];
  setMode(mode: FilterMode): void;
  clear(): void;
  /** Applies the draft, which is what runs the query. */
  submit(): void;
  /**
   * Puts the draft conditions back to the ones last applied, and runs
   * nothing.
   *
   * It is an `edit` with no `apply` on purpose: the result on screen was
   * already fetched under `state.applied.filter`, so restoring that tree
   * leaves the query and the rows exactly as they are — re-running would
   * spend a request to arrive at the answer already there. `pending` falls
   * to false because the two trees agree again, which is the whole point.
   *
   * It is not `commands.revert`, which is about the *saved* config: revert
   * throws away every unsaved edit of the view and makes the stored one the
   * draft again. This throws away the unapplied conditions alone and touches
   * neither the saved config nor the columns, sort or paging.
   */
  discard(): void;
  /** Auto-refresh pauses between these two, so typing is never interrupted. */
  focus(): void;
  blur(): void;
}

/** The Issue of a query the source rejected for a named rule (D40). */
function isRejection(found: Issue): boolean {
  return found.code.startsWith('runtime.query.failed.');
}

/**
 * The failed query's Issue, when it names a condition of the view's own
 * filter (`queryFailureIssue`) that `tree` still holds at that path on the
 * same field — an edit that moved or replaced it leaves nothing to mark.
 */
function rejectedCondition(
  error: Issue | undefined,
  tree: FilterTree,
): Issue | null {
  if (!error || !isRejection(error) || error.path[0] !== 'children')
    return null;
  const name = error.params?.name;
  if (typeof name !== 'string') return null;
  const node = nodeAt(tree, filterIndexes(error.path));
  if (!node || isFilterGroup(node)) return null;
  return node.field === name || name.startsWith(`${node.field}.`)
    ? error
    : null;
}

const ROOT: FilterPath = [];
const EMPTY_GROUPS: readonly FieldGroupDefinition[] = [];
/** Stable identity for "no runtime yet", so the memo below stays quiet. */
const EMPTY_FIELDS: readonly FieldDefinition[] = [];
/** Stable identity for "no runtime yet"; every edit produces a new tree. */
const EMPTY_TREE: FilterTree = { op: 'and', children: [] };
/** Stable identity for "no runtime yet", so `unmarked` stays still. */
const EMPTY_ISSUES: readonly Issue[] = [];
const NOTHING_PENDING: PendingReport = {
  pending: false,
  count: 0,
  conditions: false,
};

/**
 * The tree the applied bar describes: the one the rows on screen came back
 * under, or — before any rows have — the one the question on its way (or the
 * one that failed) was sent under.
 *
 * A data view reads its result first, which is the whole reason the bar
 * does not follow `applied`: between apply and answer the two differ, and
 * the rows on screen answer the older one. With no rows on screen there is
 * nothing for the newer one to contradict, and a bar that waited for the
 * first answer appeared as it landed and pushed the result down the page;
 * `hasAsked` says a query was sent, and a query that was sent was admitted,
 * so the tree is within budget. A dashboard asks nothing of its own — the
 * panels hold the queries, under no condition of the board's but its fixed
 * scope and its filters (D27) — so there is no tree to describe.
 */
function askedFilter(
  state: ViewRuntimeState<ViewConfig> | null,
): FilterTree | undefined {
  if (!state || state.applied.kind === 'dashboard') return undefined;
  if (state.result) return ownFilter(state.result.own);
  return hasAsked(state) ? state.applied.filter : undefined;
}

/** A view's own conditions; none for a dashboard, which has none (D27). */
function ownFilter(config: ViewConfig | undefined): FilterTree | undefined {
  return config?.kind === 'dashboard' ? undefined : config?.filter;
}

/** A dashboard's fixed scope in force, when it holds a condition at all. */
function fixedScope(
  runtime: ViewRuntime | null,
  state: ViewRuntimeState<ViewConfig> | null,
): FilterTree | undefined {
  if (runtime?.kind !== 'dashboard' || !state) return undefined;
  const fixed: unknown = (state.applied as { fixed?: unknown }).fixed;
  return isFilterGroup(fixed) ? fixed : undefined;
}

/**
 * Editing of the draft filter tree, addressed by path.
 *
 * It holds no state of its own: every action is an `edit` on the runtime, so
 * two editors over one view agree, and undo is just not submitting. Over a
 * dashboard there is no tree to edit (D27): the tree is empty, and every
 * edit of it does nothing.
 */
export function useFilterEditor(
  runtime: ViewRuntime | null,
): FilterEditorController {
  const state = useViewRuntime(runtime);
  // A dashboard's fields come from its draft rather than from a definition,
  // so this follows the state and not the runtime's identity.
  const fields = useMemo(
    () => runtime?.fields ?? EMPTY_FIELDS,
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `fields` is a getter over `state`
    [runtime, state],
  );
  const kinds = runtime?.kinds;
  // The runtime whose conditions are edited here: none on a dashboard.
  const owner = runtime?.kind === 'dashboard' ? null : runtime;
  const tree = ownFilter(state?.draft) ?? EMPTY_TREE;
  // The budget findings of this filter alone. An analysis metric's or a
  // dashboard panel's own filter reports the very same codes, so the code
  // alone would let an oversized tree the editor does not draw switch off
  // `isPending` for the root tree — the path is what says the finding is
  // this filter's, exactly as `issues` below reads it.
  const overBudget = filterOverBudget(state?.issues ?? EMPTY_ISSUES);

  /**
   * Edits compose within one event: `edit` is synchronous, so each action
   * reads the tree the runtime holds now rather than the one its render
   * closed over — which is what `current` is for.
   */
  const change = useCallback(
    (update: (current: FilterTree) => FilterTree) => {
      const draft = runtime?.getSnapshot().draft;
      if (!runtime || !draft || draft.kind === 'dashboard') return;
      runtime.edit({ filter: update(draft.filter) });
    },
    [runtime],
  );

  // `validateFilter` addresses a node by its path (`[0]`, `[1, 0]`), so the
  // code is what says an Issue belongs to the filter at all — and the path
  // is what says it belongs to *this* filter: an element's or a dashboard
  // panel's own filter is validated in its own scope and re-pathed under
  // ['elements', …] or ['panels', …], which would otherwise mark top-level
  // conditions as invalid.
  //
  // A query the service rejected for one condition marks that condition too
  // (D40): the runtime addresses its Issue to the applied tree's condition
  // on the field the service named, and it is drawn on the draft's pill for
  // as long as the draft still holds that condition there.
  const issues = useMemo(() => {
    const found = (state?.issues ?? EMPTY_ISSUES).filter(
      entry =>
        entry.code.startsWith('config.filterMode.') ||
        (entry.code.startsWith('filter.') && isRootFilterIssue(entry)),
    );
    const rejected = rejectedCondition(state?.query.error, tree);
    return rejected ? [...found, rejected] : found;
  }, [state, tree]);
  const fieldGroups =
    runtime?.definition.kind === 'data'
      ? (runtime.definition.fieldGroups ?? EMPTY_GROUPS)
      : EMPTY_GROUPS;
  // The editing itself is `treeController`, bound to the runtime: it is the
  // same controller a nested editor runs over a leaf's value, so the nine
  // actions live once. What this hook adds is the view around the tree.
  const base = useMemo(
    () =>
      treeController({
        tree,
        fields,
        fieldGroups,
        kinds,
        issues,
        current: () => ownFilter(owner?.getSnapshot().draft) ?? tree,
        onChange: next => owner?.edit({ filter: next }),
        optionSource: remote => runtime?.optionSource(remote) ?? null,
        valueCandidates: field => runtime?.valueCandidates(field) ?? null,
      }),
    [tree, fields, fieldGroups, kinds, issues, runtime, owner],
  );

  // What "not applied yet" is measured against is the tree `apply` promoted,
  // which is not the one the result carries: between the two a query is in
  // flight, and the editor must not go on offering to apply what it just did.
  const inForce = ownFilter(state?.applied) ?? EMPTY_TREE;

  // An over-budget draft is not compared at all. It is deeper or wider than
  // admission allows — a tree from a store may even hold a cycle — so
  // walking it once per render would be work spent on conditions the panel
  // refuses to draw, and it already blocked apply. Nothing about it is
  // pending because nothing about it can be applied.
  const isPending = useCallback(
    (path: FilterPath) =>
      !overBudget && !sameFilterNode(nodeAt(tree, path), nodeAt(inForce, path)),
    [overBudget, tree, inForce],
  );

  // The whole config, not the tree alone: Apply runs all of it (D17-6).
  const pending = useMemo(
    () =>
      state
        ? comparePending(state.draft, state.applied, state.issues)
        : NOTHING_PENDING,
    [state],
  );

  return {
    ...base,
    mode:
      state && state.draft.kind !== 'dashboard'
        ? state.draft.filterMode
        : 'simple',
    // The result's own config, which was admitted before it ran and is
    // therefore within budget by construction — an over-budget draft blocked
    // apply, so it is not what produced these rows and does not silence what
    // did. `own` rather than `config`: a merged scope moves every path.
    applied: useMemo(() => {
      const ran = askedFilter(state);
      return !ran || !kinds ? [] : describeFilter(fields, ran, kinds);
    }, [state, fields, kinds]),
    // The scope in force now rather than the one the result ran under: it is
    // the host's statement about what the user is looking at, and a host that
    // narrows it has narrowed the question before the answer arrives.
    scoped: useMemo(
      () => {
        const scope = runtime?.scopeFilter;
        return !scope || !kinds ? [] : describeFilter(fields, scope, kinds);
      },
      // eslint-disable-next-line react-hooks/exhaustive-deps -- `scopeFilter` is a getter the runtime notifies through
      [runtime, state, fields, kinds],
    ),
    // The board's own, which the reader sees in force and cannot take out.
    fixed: useMemo(() => {
      const fixed = fixedScope(runtime, state);
      return !fixed || !kinds ? [] : describeFilter(fields, fixed, kinds);
    }, [runtime, state, fields, kinds]),
    // Against the same trees the lists above describe: what ran, and what
    // the host and the board hold in force beside it.
    implied: useMemo(() => {
      const ran = askedFilter(state);
      return !ran || !kinds
        ? []
        : impliedDeletion(
            fields,
            [ran, runtime?.scopeFilter, fixedScope(runtime, state)],
            kinds,
          );
    }, [runtime, state, fields, kinds]),
    count: countLeaves(tree),
    simple: isSimpleTree(tree),
    pending: pending.pending,
    pendingCount: pending.count,
    conditionsPending: pending.conditions,
    isPending,
    // Conditions only: an error elsewhere in the config blocks apply too, but
    // it is not this editor's to count. Within the tree every error counts,
    // pill or no pill — a malformed node the panel skips still stops apply,
    // and the strip above the editor is where it is read.
    // A rejected condition marks its pill but blocks nothing: the draft is
    // admitted, and the service is the one that refused it.
    blocked: issues.filter(
      found =>
        found.severity === 'error' &&
        found.path[0] === 'children' &&
        !isRejection(found),
    ).length,
    // Over every finding of the view rather than over `issues`, which is
    // narrowed to this tree: an error about the columns or the page size is
    // marked nowhere either, and the strip is the only place it is read.
    unmarked: useMemo(
      () => unmarkedErrors(state?.issues ?? EMPTY_ISSUES, tree),
      [state, tree],
    ),
    setMode: useCallback(
      (mode: FilterMode) => owner?.edit({ filterMode: mode }),
      [owner],
    ),
    clear: useCallback(() => change(clearFilter), [change]),
    submit: useCallback(() => runtime?.apply(), [runtime]),
    // Read off the snapshot rather than off `inForce`, for the same reason
    // every other command here does: `edit` is synchronous, so the tree put
    // back is the one the runtime holds at the moment of the click.
    discard: useCallback(() => {
      const applied = runtime?.getSnapshot().applied;
      if (!runtime || !applied || applied.kind === 'dashboard') return;
      runtime.edit({ filter: applied.filter });
    }, [runtime]),
    focus: useCallback(() => runtime?.setEditing(true), [runtime]),
    blur: useCallback(() => runtime?.setEditing(false), [runtime]),
  };
}

/**
 * Keeps a leaf's value usable when its operator changes.
 *
 * Each operator implies a value shape — `EQ` takes one number, `BETWEEN` two,
 * `IN` a list — so carrying the old value across a switch would mark the row
 * invalid the moment the user picked a different operator, and block apply on
 * a mistake they did not make. The kind decides: a value its new operator
 * still admits is left alone, so `GT` to `GTE` keeps what was typed, and only
 * a value the new operator rejects is replaced by that operator's empty one.
 *
 * A patch that carries its own value is the editor writing what the user
 * typed, and is never second-guessed.
 */
function reseedValue(
  previous: FilterLeaf,
  next: FilterLeaf,
  patch: Partial<FilterLeaf>,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry | undefined,
): FilterLeaf {
  if (patch.operator === undefined || patch.operator === previous.operator)
    return next;
  if ('value' in patch) return next;

  const field = byName.get(next.field);
  if (!field || !kinds) return next;
  const kind = kinds.get(field.kind);
  if (!kind) return next;

  const admitted = kind.validate({
    value: next.value,
    operator: next.operator,
    field,
    kinds,
    path: [],
  });
  if (!admitted.some(found => found.severity === 'error')) return next;

  return {
    ...next,
    value: kind.emptyValue(next.operator, field) as FilterLeaf['value'],
  };
}

/**
 * The part of the controller that edits a tree, and nothing about the view
 * around it.
 *
 * `useFilterEditor` is one implementation, bound to a runtime's draft. A
 * condition that holds a condition — an element match — is another, bound to
 * a leaf's value, and the two render through the same components because the
 * thing being edited is the same thing.
 */
export interface FilterTreeController {
  tree: FilterTree;
  fields: readonly FieldDefinition[];
  /** The picker groups the definition declares; none for a dashboard's or an element's fields. */
  fieldGroups: readonly FieldGroupDefinition[];
  /** The registry admission used, so a nested editor admits by the same one. */
  kinds: FieldKindRegistry | undefined;
  issues: Issue[];
  addLeaf(field: string, parent?: FilterPath): void;
  /**
   * Fields a new condition may be added on under `parent`: every field not
   * already a condition of that group, since a group holds one condition per
   * field, and of a kind the registry knows, since a condition cannot be made
   * on one it does not. The picker lists these rather than `fields`.
   */
  fieldsFor(parent?: FilterPath): FieldDefinition[];
  /**
   * Sets a condition back to "nothing said yet": its value becomes the
   * kind's empty value for its operator, so the row stays and the condition
   * leaves the query. On a group, every condition in it. This is what the
   * applied summary's remove does — it takes a condition out of force without
   * taking the field away from the editor.
   */
  clearValue(path: FilterPath): void;
  updateLeaf(path: FilterPath, patch: Partial<FilterLeaf>): void;
  addGroup(op: FilterGroupOperator, parent?: FilterPath): void;
  updateGroup(path: FilterPath, op: FilterGroupOperator): void;
  /**
   * Flips the condition at `path` between itself and "not itself": a leaf is
   * wrapped in a `nor` group of its own, a leaf already alone in one is
   * unwrapped. `path` is the leaf's, as the pill holds it. This is simple
   * mode's switch (D18-7); advanced mode has the group operator for it.
   */
  negate(path: FilterPath): void;
  /**
   * Takes out whatever `path` names — and the negation around it, when the
   * path is that of a leaf alone in one. A caller holds a condition by its
   * leaf's path whether or not it is negated, so this is the only removal
   * either kind of pill needs (`removeConditionAt`).
   */
  remove(path: FilterPath): void;
  operatorsFor(field: string): FilterOperatorName[];
  editorFor(path: FilterPath): EditorDescriptor | null;
  /**
   * The remote candidates behind a `remote` editor's key, from the runtime
   * this editor is bound to; `null` where the host wired none, and absent
   * on a controller with no runtime at all. A nested editor over a leaf's
   * value shares its parent's.
   */
  optionSource?(remote: string): OptionSource | null;
  /**
   * The values the condition at `path` may be picked from, counted from the
   * data (`ViewRuntime.valueCandidates`), or `null` where none are offered:
   * its field is not one the data can list, or its operator takes something
   * other than one of the field's own values — a substring, a range, no
   * value at all. Absent on a controller whose fields are not the view's
   * own: a nested predicate's are an element's, which the view's data does
   * not count.
   */
  valueCandidates?(path: FilterPath): ValueCandidateSource | null;
}

export interface TreeControllerInput {
  tree: FilterTree;
  fields: readonly FieldDefinition[];
  fieldGroups?: readonly FieldGroupDefinition[];
  kinds: FieldKindRegistry | undefined;
  /** Issues already rebased onto this tree. */
  issues: Issue[];
  /**
   * The tree an action reads at the moment it runs, where that is not the
   * `tree` it was built over: an editor bound to a runtime reads the draft
   * the runtime holds at the click, so two edits in one event compose —
   * adding a group and then a leaf inside it works. Left out, actions read
   * `tree`, which is what a nested editor over a leaf's value wants.
   */
  current?(): FilterTree;
  onChange(tree: FilterTree): void;
  /** See `FilterTreeController.optionSource`. */
  optionSource?(remote: string): OptionSource | null;
  /** The candidate source of a field by name; see `FilterTreeController.valueCandidates`. */
  valueCandidates?(field: string): ValueCandidateSource | null;
}

/** The tree with the node at `path` set to say nothing; see `clearValue`. */
function blankAt(
  tree: FilterTree,
  path: FilterPath,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry | undefined,
): FilterTree {
  const blankLeaf = (leaf: FilterLeaf): FilterLeaf => {
    const field = byName.get(leaf.field);
    const kind = field && kinds?.get(field.kind);
    if (!field || !kind) return leaf;
    return {
      ...leaf,
      value: kind.emptyValue(leaf.operator, field) as FilterLeaf['value'],
    };
  };
  const blankNode = (node: FilterNode): FilterNode =>
    isFilterGroup(node)
      ? { ...node, children: node.children.map(blankNode) }
      : blankLeaf(node);
  if (path.length === 0) return blankNode(tree) as FilterTree;
  return updateAt(tree, path, blankNode);
}

/** The fields a condition can still be added on at `parent`; see `fieldsFor`. */
function addableFields(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  parent: FilterPath,
  kinds: FieldKindRegistry | undefined,
): FieldDefinition[] {
  // A negated condition is still this group's condition on its field, which
  // is why the kernel's `conditions` is asked rather than the children read:
  // the wrapper it is stored in is the kernel's to know.
  const used = new Set(
    conditions(nodeAt(tree, parent)).map(condition => condition.leaf.field),
  );
  // A field whose kind the registry does not know is left out, because
  // `addLeaf` refuses it: there is no default operator to seed and no empty
  // value to start from, so the row it offered did nothing when clicked.
  // A condition a stored config already holds on such a field is a different
  // question — the definition changed under a saved view — and the panel
  // still draws it, read-only.
  return fields.filter(
    field => !used.has(field.name) && kinds?.has(field.kind) === true,
  );
}

/** See `FilterTreeController.valueCandidates`. */
function candidatesAt(
  tree: FilterTree,
  path: FilterPath,
  byName: ReadonlyMap<string, FieldDefinition>,
  kinds: FieldKindRegistry | undefined,
  sourceOf: (field: string) => ValueCandidateSource | null,
): ValueCandidateSource | null {
  const node = nodeAt(tree, path);
  if (!node || isFilterGroup(node)) return null;
  if (!VALUE_CANDIDATE_OPERATORS.includes(node.operator)) return null;
  const field = byName.get(node.field);
  const kind = field && kinds?.get(field.kind);
  // The kind decides what the value is typed into, and a list of values
  // replaces only a text box: a kind drawing anything else has a control of
  // its own for this operator.
  if (!field || kind?.editor(node.operator, field, node.value).input !== 'text')
    return null;
  return sourceOf(node.field);
}

/**
 * A controller over any tree. It holds no state: every action produces the
 * next tree and hands it to `onChange`, which is what lets a nested one write
 * straight back into the leaf that carries it.
 */
export function treeController(
  input: TreeControllerInput,
): FilterTreeController {
  const { tree, fields, kinds, issues, onChange } = input;
  const byName = new Map(fields.map(field => [field.name, field]));
  const sourceOf = input.valueCandidates;
  const change = (update: (current: FilterTree) => FilterTree) =>
    onChange(update(input.current?.() ?? tree));

  return {
    tree,
    fields,
    fieldGroups: input.fieldGroups ?? [],
    kinds,
    issues,
    ...(input.optionSource ? { optionSource: input.optionSource } : {}),
    ...(sourceOf
      ? {
          valueCandidates: (path: FilterPath) =>
            candidatesAt(tree, path, byName, kinds, sourceOf),
        }
      : {}),
    fieldsFor(parent = ROOT) {
      return addableFields(tree, fields, parent, kinds);
    },
    clearValue(path) {
      change(current => blankAt(current, path, byName, kinds));
    },
    addLeaf(field, parent = ROOT) {
      const definition = byName.get(field);
      const kind = definition && kinds?.get(definition.kind);
      if (!definition || !kind) return;
      const allowed = operatorsOf(definition, kind);
      const operator = allowed.includes(kind.defaultOperator)
        ? kind.defaultOperator
        : allowed[0];
      if (!operator) return;
      change(current =>
        insertAt(current, parent, {
          field,
          operator,
          value: kind.emptyValue(operator, definition) as FilterLeaf['value'],
        }),
      );
    },
    updateLeaf(path, patch) {
      change(current =>
        updateAt(current, path, node => {
          if ('children' in node) return node;
          const next = { ...node, ...patch };
          return reseedValue(node, next, patch, byName, kinds);
        }),
      );
    },
    addGroup(op, parent = ROOT) {
      change(current => insertAt(current, parent, { op, children: [] }));
    },
    updateGroup(path, op) {
      if (path.length === 0) {
        change(current => (current.op === op ? current : { ...current, op }));
        return;
      }
      change(current =>
        updateAt(current, path, node =>
          'children' in node ? { ...node, op } : node,
        ),
      );
    },
    negate(path) {
      change(current => negateAt(current, path));
    },
    remove(path) {
      change(current => removeConditionAt(current, path));
    },
    operatorsFor(field) {
      const definition = byName.get(field);
      const kind = definition && kinds?.get(definition.kind);
      return definition && kind ? operatorsOf(definition, kind) : [];
    },
    editorFor(path) {
      const node = nodeAt(tree, path);
      if (!node || 'children' in node) return null;
      const definition = byName.get(node.field);
      const kind = definition && kinds?.get(definition.kind);
      return kind && definition
        ? kind.editor(node.operator, definition, node.value)
        : null;
    },
  };
}
