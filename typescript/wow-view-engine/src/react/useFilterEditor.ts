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
  updateAt,
  type EditorDescriptor,
  type FieldKindRegistry,
  type FilterPath,
  type FilterSummaryItem,
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
  /** Conditions currently in force, for a summary bar. */
  applied: FilterSummaryItem[];
  count: number;
  /** False when the tree needs the advanced editor to be shown faithfully. */
  simple: boolean;
  setMode(mode: FilterMode): void;
  clear(): void;
  /** Applies the draft, which is what runs the query. */
  submit(): void;
  /** Auto-refresh pauses between these two, so typing is never interrupted. */
  focus(): void;
  blur(): void;
}

const ROOT: FilterPath = [];
/** Stable identity for "no runtime yet", so the memo below stays quiet. */
const EMPTY_FIELDS: readonly FieldDefinition[] = [];
/** Stable identity for "no runtime yet"; every edit produces a new tree. */
const EMPTY_TREE: FilterTree = { op: 'and', children: [] };

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
  // The budget findings of this filter alone: the issue filter below keeps
  // element- and panel-scoped trees out, so a hit here is the top-level one.
  const overBudget = (state?.issues ?? []).some(
    found =>
      found.code === 'filter.tree.too-deep' ||
      found.code === 'filter.tree.too-many-nodes',
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

  return {
    tree,
    mode: state?.draft.filterMode ?? 'simple',
    fields,
    kinds,
    // `validateFilter` addresses a node by its path (`[0]`, `[1, 0]`), so the
    // code is what says an Issue belongs to the filter at all — and the path
    // is what says it belongs to *this* filter: an element's or a dashboard
    // panel's own filter is validated in its own scope and re-pathed under
    // ['elements', …] or ['panels', …], which would otherwise mark top-level
    // conditions as invalid.
    issues: (state?.issues ?? []).filter(
      found =>
        found.code.startsWith('config.filterMode.') ||
        (found.code.startsWith('filter.') &&
          (found.path.length === 0 || found.path[0] === 'children')),
    ),
    // An over-budget draft also blocked apply, so what was applied last is
    // the oversized tree itself; summarising it would walk every leaf and
    // render one line per condition. The findings say so instead.
    applied: useMemo(
      () =>
        overBudget || !state || !kinds
          ? []
          : describeFilter(fields, state.applied.filter, kinds),
      [overBudget, state, fields, kinds],
    ),
    count: countLeaves(tree),
    simple: isSimpleTree(tree),
    setMode: useCallback(
      (mode: FilterMode) => runtime?.edit({ filterMode: mode }),
      [runtime],
    ),
    addLeaf,
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
  /** The registry admission used, so a nested editor admits by the same one. */
  kinds: FieldKindRegistry | undefined;
  issues: Issue[];
  addLeaf(field: string, parent?: FilterPath): void;
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
  kinds: FieldKindRegistry | undefined;
  /** Issues already rebased onto this tree. */
  issues: Issue[];
  onChange(tree: FilterTree): void;
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
    kinds,
    issues,
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
