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
} from '../model/index.js';
import {
  clearFilter,
  countLeaves,
  describeFilter,
  insertAt,
  isSimpleTree,
  nodeAt,
  operatorsOf,
  removeAt,
  sameFilterNode,
  sameFilterTree,
  updateAt,
  walkFilter,
  type EditorDescriptor,
  type FieldKindRegistry,
  type FilterPath,
  type FilterSummaryItem,
  isFilterGroup,
  isFilterLeaf,
} from '../filter/index.js';
import type { ViewRuntime } from '../runtime/index.js';
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
   * and until it answers, `applied` has already moved on. Empty until a result
   * exists.
   *
   * It describes `result.own`, not `result.config`: under a host scope filter
   * the two differ, and only the first is addressed by the paths this editor
   * takes — so `clearValue(item.path)` takes out the condition the badge names.
   * The scope's own conditions are `scoped`.
   *
   * A dashboard is the exception, because it has no result of its own: every
   * panel runs its own query and there is no single config to read one back
   * from. There `state.applied.filter` is described instead — the global
   * condition the panels were asked under — and the workbench decides from
   * the panels whether anything was asked at all.
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
  count: number;
  /** False when the tree needs the advanced editor to be shown faithfully. */
  simple: boolean;
  /**
   * True when the draft says something other than what was last applied.
   * False for a draft over the tree budget, which cannot be applied at all.
   */
  pending: boolean;
  /**
   * How many nodes `isPending` holds for; a badge count. It counts both
   * trees: a path the draft has and the applied tree does not is a new
   * condition, and a path only the applied tree has is one removed since —
   * both are edits waiting for Apply, and a cleared filter is only the
   * second kind.
   */
  pendingCount: number;
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
  setMode(mode: FilterMode): void;
  clear(): void;
  /** Applies the draft, which is what runs the query. */
  submit(): void;
  /** Auto-refresh pauses between these two, so typing is never interrupted. */
  focus(): void;
  blur(): void;
}

const ROOT: FilterPath = [];
const EMPTY_GROUPS: readonly FieldGroupDefinition[] = [];
/** Stable identity for "no runtime yet", so the memo below stays quiet. */
const EMPTY_FIELDS: readonly FieldDefinition[] = [];
/** Stable identity for "no runtime yet"; every edit produces a new tree. */
const EMPTY_TREE: FilterTree = { op: 'and', children: [] };

/**
 * True for a finding about the tree this editor draws rather than a nested
 * one. A metric's, an element's or a dashboard panel's filter is validated in
 * its own scope and re-pathed under ['metrics', …], ['elements', …] or
 * ['panels', …]; the root tree's own findings sit at the config root or under
 * ['children', …].
 */
function isOwnFilterPath(found: Issue): boolean {
  return found.path.length === 0 || found.path[0] === 'children';
}

/**
 * Editing of the draft filter tree, addressed by path.
 *
 * It holds no state of its own: every action is an `edit` on the runtime, so
 * two editors over one view agree, and undo is just not submitting.
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
  const tree = state?.draft.filter ?? EMPTY_TREE;
  // The budget findings of this filter alone. An analysis metric's or a
  // dashboard panel's own filter reports the very same codes, so the code
  // alone would let an oversized tree the editor does not draw switch off
  // `pending` and `pendingCount` for the root tree — the path is what says
  // the finding is this filter's, exactly as `issues` below reads it.
  const overBudget = (state?.issues ?? []).some(
    found =>
      (found.code === 'filter.tree.too-deep' ||
        found.code === 'filter.tree.too-many-nodes') &&
      isOwnFilterPath(found),
  );

  const byName = useMemo(
    () => new Map(fields.map(field => [field.name, field])),
    [fields],
  );

  /**
   * Edits compose within one event: `edit` is synchronous, so each action
   * reads the tree the runtime holds now rather than the one its render
   * closed over. Adding a group and then a leaf inside it works.
   */
  const change = useCallback(
    (update: (current: FilterTree) => FilterTree) => {
      if (!runtime) return;
      runtime.edit({ filter: update(runtime.getSnapshot().draft.filter) });
    },
    [runtime],
  );

  const addLeaf = useCallback(
    (field: string, parent: FilterPath = ROOT) => {
      const definition = byName.get(field);
      const kind = definition && kinds?.get(definition.kind);
      if (!definition || !kind) return;
      // A field may narrow its kind's operators, and the kind's default is
      // not always one of them; starting outside the allowed set would make
      // every new condition invalid on arrival.
      const allowed = operatorsOf(definition, kind);
      const operator = allowed.includes(kind.defaultOperator)
        ? kind.defaultOperator
        : allowed[0];
      if (!operator) return;

      const leaf: FilterNode = {
        field,
        operator,
        // The kind decides what an untouched value looks like for its operator.
        value: kind.emptyValue(operator, definition) as FilterLeaf['value'],
      };
      change(current => insertAt(current, parent, leaf));
    },
    [byName, kinds, change],
  );

  const updateLeaf = useCallback(
    (path: FilterPath, patch: Partial<FilterLeaf>) => {
      change(current =>
        updateAt(current, path, node => {
          if ('children' in node) return node;
          const next = { ...node, ...patch };
          return reseedValue(node, next, patch, byName, kinds);
        }),
      );
    },
    [byName, kinds, change],
  );

  const addGroup = useCallback(
    (op: FilterGroupOperator, parent: FilterPath = ROOT) => {
      change(current => insertAt(current, parent, { op, children: [] }));
    },
    [change],
  );

  const updateGroup = useCallback(
    (path: FilterPath, op: FilterGroupOperator) => {
      // `updateAt` leaves the root alone by design, so the root's operator is
      // written directly: a tree may be one big OR.
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
    [change],
  );

  // `validateFilter` addresses a node by its path (`[0]`, `[1, 0]`), so the
  // code is what says an Issue belongs to the filter at all — and the path
  // is what says it belongs to *this* filter: an element's or a dashboard
  // panel's own filter is validated in its own scope and re-pathed under
  // ['elements', …] or ['panels', …], which would otherwise mark top-level
  // conditions as invalid.
  const issues = (state?.issues ?? []).filter(
    found =>
      found.code.startsWith('config.filterMode.') ||
      (found.code.startsWith('filter.') && isOwnFilterPath(found)),
  );

  // What "not applied yet" is measured against is the tree `apply` promoted,
  // which is not the one the result carries: between the two a query is in
  // flight, and the editor must not go on offering to apply what it just did.
  const inForce = state?.applied.filter ?? EMPTY_TREE;

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

  const pendingCount = useMemo(() => {
    if (overBudget) return 0;
    let count = 0;
    const visited = new Set<string>();
    for (const { node, path } of walkFilter(tree)) {
      const at = indexesOf(path);
      visited.add(at.join(','));
      if (!sameFilterNode(node, nodeAt(inForce, at))) count += 1;
    }
    // A condition taken out of the draft is still an edit not applied: the
    // rows on screen were fetched under it, and `isPending` at its path says
    // so. Counting the draft alone would leave a badge of 0 beside an Apply
    // button that has something to do — a cleared filter most of all.
    for (const { path } of walkFilter(inForce)) {
      const at = indexesOf(path);
      if (!visited.has(at.join(','))) count += 1;
    }
    return count;
  }, [overBudget, tree, inForce]);

  return {
    tree,
    mode: state?.draft.filterMode ?? 'simple',
    fields,
    fieldGroups:
      runtime?.definition.kind === 'data'
        ? (runtime.definition.fieldGroups ?? EMPTY_GROUPS)
        : EMPTY_GROUPS,
    kinds,
    issues,
    // The result's own config, which was admitted before it ran and is
    // therefore within budget by construction — an over-budget draft blocked
    // apply, so it is not what produced these rows and does not silence what
    // did. `own` rather than `config`: a merged scope moves every path.
    applied: useMemo(() => {
      // A dashboard's `result` is always null — the panels hold the queries —
      // so what was asked is read from the applied config itself. A data
      // view keeps reading its result, which is the whole reason the bar
      // does not follow `applied`: between apply and answer they differ.
      const ran =
        runtime?.kind === 'dashboard'
          ? state?.applied.filter
          : state?.result?.own.filter;
      return !ran || !kinds ? [] : describeFilter(fields, ran, kinds);
    }, [runtime, state, fields, kinds]),
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
    count: countLeaves(tree),
    simple: isSimpleTree(tree),
    pending: !overBudget && !sameFilterTree(tree, inForce),
    pendingCount,
    isPending,
    // Conditions only: an error elsewhere in the config blocks apply too, but
    // it is not this editor's to count. Within the tree every error counts,
    // pill or no pill — a malformed node the panel skips still stops apply,
    // and the strip above the editor is where it is read.
    blocked: issues.filter(
      found => found.severity === 'error' && found.path[0] === 'children',
    ).length,
    setMode: useCallback(
      (mode: FilterMode) => runtime?.edit({ filterMode: mode }),
      [runtime],
    ),
    addLeaf,
    fieldsFor: useCallback(
      (parent: FilterPath = ROOT) => addableFields(tree, fields, parent),
      [tree, fields],
    ),
    clearValue: useCallback(
      (path: FilterPath) =>
        change(current => blankAt(current, path, byName, kinds)),
      [change, byName, kinds],
    ),
    updateLeaf,
    addGroup,
    updateGroup,
    remove: useCallback(
      (path: FilterPath) => change(current => removeAt(current, path)),
      [change],
    ),
    clear: useCallback(() => change(clearFilter), [change]),
    submit: useCallback(() => runtime?.apply(), [runtime]),
    focus: useCallback(() => runtime?.setEditing(true), [runtime]),
    blur: useCallback(() => runtime?.setEditing(false), [runtime]),
    operatorsFor: useCallback(
      (field: string) => {
        const definition = byName.get(field);
        const kind = definition && kinds?.get(definition.kind);
        return definition && kind ? operatorsOf(definition, kind) : [];
      },
      [byName, kinds],
    ),
    editorFor: useCallback(
      (path: FilterPath) => {
        const node = nodeAt(tree, path);
        if (!node || 'children' in node) return null;
        const definition = byName.get(node.field);
        const kind = definition && kinds?.get(definition.kind);
        return kind && definition
          ? kind.editor(node.operator, definition, node.value)
          : null;
      },
      [byName, kinds, tree],
    ),
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
   * field. The picker lists these rather than `fields`.
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
  remove(path: FilterPath): void;
  operatorsFor(field: string): FilterOperatorName[];
  editorFor(path: FilterPath): EditorDescriptor | null;
}

export interface TreeControllerInput {
  tree: FilterTree;
  fields: readonly FieldDefinition[];
  fieldGroups?: readonly FieldGroupDefinition[];
  kinds: FieldKindRegistry | undefined;
  /** Issues already rebased onto this tree. */
  issues: Issue[];
  onChange(tree: FilterTree): void;
}

/**
 * A walk's path as the editor addresses nodes. `walkFilter` interleaves the
 * `'children'` key with each index, and a `FilterPath` is the indexes alone.
 */
function indexesOf(path: readonly (string | number)[]): FilterPath {
  return path.filter((step): step is number => typeof step === 'number');
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

/** The fields not yet a condition of the group at `parent`; see `fieldsFor`. */
function addableFields(
  tree: FilterTree,
  fields: readonly FieldDefinition[],
  parent: FilterPath,
): FieldDefinition[] {
  const group = nodeAt(tree, parent);
  const used = new Set(
    isFilterGroup(group)
      ? group.children.filter(isFilterLeaf).map(leaf => leaf.field)
      : [],
  );
  return fields.filter(field => !used.has(field.name));
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
  const change = (update: (current: FilterTree) => FilterTree) =>
    onChange(update(tree));

  return {
    tree,
    fields,
    fieldGroups: input.fieldGroups ?? [],
    kinds,
    issues,
    fieldsFor(parent = ROOT) {
      return addableFields(tree, fields, parent);
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
    remove(path) {
      change(current => removeAt(current, path));
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
