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
 * Editing a filter tree, and nothing about the view around it: the
 * `FilterTreeController` contract and `treeController`, the one stateless
 * implementation every editor binds — a view's draft (`useFilterEditor`), a
 * condition's own predicate (`ConditionPill`), a metric's condition
 * (`MetricCondition`).
 */

import type {
  FieldDefinition,
  FieldGroupDefinition,
  FilterGroupOperator,
  FilterLeaf,
  FilterNode,
  FilterOperatorName,
  FilterTree,
  Issue,
} from '../model/index.js';
import {
  conditions,
  insertAt,
  negateAt,
  nodeAt,
  operatorsOf,
  removeConditionAt,
  updateAt,
  type EditorDescriptor,
  type FieldKindRegistry,
  type FilterPath,
  isFilterGroup,
} from '../filter/index.js';
import { holdsTime, isDurationOperator } from '../filter/kinds/duration.js';
import type { OptionSource, ValueCandidateSource } from '../runtime/index.js';
import { VALUE_CANDIDATE_OPERATORS } from '../analysis/index.js';

const ROOT: FilterPath = [];

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

interface TreeControllerInput {
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
  /**
   * Whether a condition here may be a time since another moment (N3's
   * `EXPRESSION`). Left out, it may; one entry of an element is asked
   * nothing of the kind — Wow keeps it out of `ELEMENT_MATCH` — so the
   * predicate block says `false`.
   */
  durations?: boolean;
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
  // still draws it, read-only. A field left with no operator at all — its
  // source admits no condition on it (capabilities.md 5) — is left out too:
  // what the source cannot do is not offered.
  return fields.filter(field => {
    if (used.has(field.name)) return false;
    const kind = kinds?.get(field.kind);
    return kind !== undefined && operatorsOf(field, kind).length > 0;
  });
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
      if (!definition || !kind) return [];
      // A time since another moment needs another time to run from: with
      // none beside this one, or inside one entry, it is not offered.
      const durations =
        input.durations !== false &&
        fields.some(entry => entry.name !== field && holdsTime(entry));
      return operatorsOf(definition, kind).filter(
        operator => durations || !isDurationOperator(operator),
      );
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
