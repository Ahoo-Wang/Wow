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

import type { FilterExpression } from '@ahoo-wang/fetcher-wow';
import type {
  FieldDefinition,
  FilterValue,
  FieldKindId,
  FieldOption,
  FilterLeaf,
  FilterOperatorName,
  FilterTree,
  Issue,
  IssuePath,
  RuntimeLimits,
} from '../model/index.js';
import type { FieldKindDescription } from './describe.js';

/**
 * Everything the engine needs to know about one field type.
 *
 * A kind owns the shape of its values, so an application can add a type
 * without the kernel learning anything about it. The React editor registers
 * separately under the same id: a kind stays free of components, which is why
 * a saved config never holds a component name.
 */
export interface FieldKind {
  id: FieldKindId;
  /** Operators this kind supports, in the order an editor should offer them. */
  operators: FilterOperatorName[];
  defaultOperator: FilterOperatorName;
  /**
   * The value a leaf starts from, which is normally "nothing yet". A picked
   * field is a question the user has not finished asking, so the starting
   * value must not narrow anything: seeding a number with `0` would silently
   * apply `amount = 0` the moment the row appeared.
   */
  emptyValue(operator: FilterOperatorName, field: FieldDefinition): unknown;
  /**
   * Whether this value still counts as unsupplied. The registry's default
   * covers `null`, `''` and `[]`; a kind whose empty shape is its own — a
   * reference holding no items, a predicate whose every condition is itself
   * unfilled — says so here. It replaces the default rather than extending
   * it, so a kind that also starts from `''` or `[]` must recognise those too.
   */
  isBlank?(context: FieldKindBlankContext): boolean;
  /**
   * The tree this value holds, for a kind whose value is a condition rather
   * than a scalar.
   *
   * Declaring it is what keeps the budget honest. `maxFilterDepth` and
   * `maxFilterNodes` exist because a tree arrives from a store and must not
   * exhaust the stack; a tree hidden inside a value would be a second
   * dimension nobody counted, so a kind that holds one says so and the
   * budget walks it with everything else.
   *
   * The operator is passed because a stored leaf keeps its old value when the
   * operator changes: a predicate left behind under `IS_EMPTY` is not a tree
   * this leaf asks anything with, and a kind that answers with it anyway has
   * the budget charged for a tree nothing ever validates. A kind gates this
   * on the operator exactly as its `isBlank` and `validate` do.
   */
  nested?(
    value: unknown,
    field: FieldDefinition,
    operator: FilterOperatorName,
  ): NestedTree | null;
  /**
   * Whether a leaf of this kind tests one value of the record.
   *
   * Defaults to true. `array` and `elementMatch` compile to conditions over
   * the entries of a collection, and `search` compiles to a full-text match
   * across the record, so none of the three has a single value to test. Wow
   * refuses all three wherever a filter has to be a whole-value predicate —
   * a metric's own filter — so a kind that compiles to one of those shapes
   * must say so here, or it passes that check and is refused by the server
   * instead.
   */
  scalar?: boolean;
  /**
   * Whether this kind's `name` is a handle for the editor rather than a path
   * into the document.
   *
   * Wow calls the filters that name no field root filters — `SEARCH` and the
   * metadata ones — and refuses them inside an element predicate, so a kind
   * that compiles to one must say so or the server rejects a condition the
   * engine called usable. `FIELDLESS_FIELD_KIND_IDS` names the built-ins;
   * this flag is how a custom kind joins them, and it is what
   * `isFieldlessKind` reads whenever the registry has resolved the kind.
   */
  fieldless?: true;
  /** Reports why a value cannot be used; an empty array admits it. */
  validate(context: FieldKindValidateContext): Issue[];
  /** Maps one admitted leaf onto the Wow protocol. */
  compile(context: FieldKindCompileContext): FilterExpression;
  /** Describes the editor implied by the operator and the value's variant. */
  editor(
    operator: FilterOperatorName,
    field: FieldDefinition,
    value?: unknown,
  ): EditorDescriptor;
  /**
   * One applied condition, for the summary bar: its parts, and the English
   * line they read as.
   *
   * The parts are what makes the bar translatable — it is the most visible
   * text of the result area, and a kind that returned a sentence put it
   * beyond every catalogue. `text` stays the English reading, because a host
   * may consume `FilterSummaryItem.text` as it stands.
   */
  describe(context: FieldKindDescribeContext): FieldKindDescription;
}

/** A tree held inside a leaf's value, and the fields it is written against. */
export interface NestedTree {
  tree: FilterTree;
  fields: readonly FieldDefinition[];
}

export interface FieldKindBlankContext {
  value: unknown;
  operator: FilterOperatorName;
  field: FieldDefinition;
  /**
   * The registry in play, for a kind whose value is itself a condition: its
   * blankness is that of the conditions it holds, judged by their own kinds.
   */
  kinds: FieldKindRegistry;
}

export interface FieldKindValidateContext {
  value: unknown;
  operator: FilterOperatorName;
  field: FieldDefinition;
  /**
   * The registry in play, for a kind whose value is itself a condition. It
   * is passed rather than imported so a predicate admits custom kinds on the
   * same terms as the tree around it.
   */
  kinds: FieldKindRegistry;
  /** Location of the leaf, so an issue points at the offending node. */
  path: IssuePath;
  /**
   * The budget the caller admitted the tree under, for a kind whose value is
   * itself a condition. Without it a nested predicate would be judged against
   * `DEFAULT_RUNTIME_LIMITS` while the tree around it was judged against the
   * caller's, so one filter would answer to two budgets. It is optional
   * because an editor may ask a kind about one leaf outside any walk.
   */
  limits?: Pick<RuntimeLimits, 'maxFilterDepth' | 'maxFilterNodes'>;
}

export interface FieldKindCompileContext {
  leaf: FilterLeaf;
  field: FieldDefinition;
  /** The registry in play; see `FieldKindValidateContext`. */
  kinds: FieldKindRegistry;
  /** Evaluation moment and zone; the kernel never reads the system clock. */
  now: Date;
  timeZone: string;
}

export interface FieldKindDescribeContext {
  leaf: FilterLeaf;
  field: FieldDefinition;
  /** The registry in play; see `FieldKindValidateContext`. */
  kinds: FieldKindRegistry;
}

/**
 * Every input shape the engine can draw, as values and not as a type alone.
 *
 * `FilterValueEditor` switches over exactly this union and a kind picks one
 * of its members — there is no renderer registry, so a kind that asks for
 * anything else asks for a control nobody wrote. The list lives here, beside
 * the union it spells out, so admission can hold a kind to the contract
 * instead of the surface degrading to a text box: `validateFilter` refuses
 * such a leaf with `filter.kind.unknown-editor`.
 */
export const EDITOR_INPUTS = [
  'none',
  'text',
  'number',
  'boolean',
  /** One of the three deletion readings, worded by the catalogue. */
  'deletion',
  'select',
  'remote',
  'date',
  'dateRange',
  'relativeDate',
  /** Not a value: a condition, built with the same editor as the outer one. */
  'predicate',
] as const;

export type EditorInput = (typeof EDITOR_INPUTS)[number];

/** Whether a kind named an input the engine has a control for. */
export function isKnownEditorInput(input: unknown): input is EditorInput {
  return EDITOR_INPUTS.includes(input as EditorInput);
}

/** Shape of the input an editor should render; never a component name. */
export interface EditorDescriptor {
  input: EditorInput;
  /** The input collects several values, e.g. for `IN`. */
  multiple?: boolean;
  /** Two bounds rather than one value, e.g. for `BETWEEN`. */
  range?: boolean;
  /** Static candidates, for `select`. */
  options?: FieldOption[];
  /** Candidate source key, for `remote`. */
  remote?: string;
  /** Whether the value carries a time of day. */
  withTime?: boolean;
}

/** Kinds available to the kernels, keyed by id. */
export type FieldKindRegistry = ReadonlyMap<FieldKindId, FieldKind>;

export function createFieldKindRegistry(
  kinds: readonly FieldKind[],
): FieldKindRegistry {
  return new Map(kinds.map(kind => [kind.id, kind]));
}

/**
 * Adds or replaces kinds, so an application extends the built-in set without
 * mutating it.
 */
export function withFieldKinds(
  registry: FieldKindRegistry,
  kinds: readonly FieldKind[],
): FieldKindRegistry {
  const merged = new Map(registry);
  for (const kind of kinds) merged.set(kind.id, kind);
  return merged;
}

/** Operators a field actually offers: its own list narrows its kind's. */
export function operatorsOf(
  field: FieldDefinition,
  kind: FieldKind,
): FilterOperatorName[] {
  if (!field.operators) return [...kind.operators];
  return field.operators.filter(operator => kind.operators.includes(operator));
}

/**
 * Reads an admitted leaf value as the shape its kind defined. Only
 * `validateFilter` decides admissibility; a compile step runs after it.
 */
export function readValue<T>(value: unknown): T {
  return value as T;
}

/**
 * Stores a value a kind produced, the mirror of `readValue`.
 *
 * A kind owns the shape of its values and declares it as an interface, which
 * TypeScript does not consider assignable to a JSON index signature even when
 * every field is JSON. Admission still runs on the way back in.
 */
export function writeValue<T>(value: T): FilterValue {
  return value as FilterValue;
}

/**
 * Whether a leaf is still waiting to be filled in.
 *
 * An unfinished condition is an ordinary state of an editor, not a mistake:
 * the user picked a field and has not yet said what to compare it to. Such a
 * leaf is neither validated against its kind nor compiled into the query, so
 * adding a row narrows nothing and blocks nothing until it says something.
 *
 * An operator that needs no input is never blank. `IS_NULL` is the whole
 * condition and the value beside it is never read, so dropping it for looking
 * empty would delete the condition. The kind already declares which operators
 * those are by describing their editor as `none`, which is a better answer
 * than a list here could be, because a custom kind gets it right too.
 */
export function isBlankLeafValue(
  value: unknown,
  operator: FilterOperatorName,
  field: FieldDefinition,
  kind: FieldKind,
  kinds: FieldKindRegistry,
): boolean {
  if (kind.editor(operator, field).input === 'none') return false;
  if (kind.isBlank) return kind.isBlank({ value, operator, field, kinds });
  return (
    value === null ||
    value === undefined ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}

export function issue(
  code: string,
  path: IssuePath,
  params?: Issue['params'],
  severity: Issue['severity'] = 'error',
): Issue {
  return params ? { code, severity, path, params } : { code, severity, path };
}
