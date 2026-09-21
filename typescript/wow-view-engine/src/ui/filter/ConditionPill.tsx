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

import { XIcon } from 'lucide-react';
import type {
  FieldOption,
  FilterLeaf,
  FilterTree,
  Issue,
  IssuePath,
} from '../../model/index.js';
import {
  elementFields,
  isBlankLeafValue,
  writeValue,
  type FilterPath,
} from '../../filter/index.js';
import {
  treeController,
  type FilterTreeController,
} from '../../react/index.js';
import { Button } from '../components/button.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { SelectContent } from '../popups.js';
import { useViewMessages } from '../MessagesProvider.js';
import { FilterValueEditor } from '../FilterValueEditor.js';
import { GroupBlock } from './GroupBlock.js';

/**
 * The one credential for "said, but not yet asked".
 *
 * A draft is only worth keeping apart from what ran if the difference is
 * visible, and it is visible in one place per node rather than in a banner
 * that says some condition somewhere has moved.
 */
export function PendingDot() {
  const messages = useViewMessages();
  return (
    <span className="bg-primary absolute -top-0.5 -right-0.5 size-1.5 rounded-full">
      <span className="sr-only">{messages.label('label.filter.pending')}</span>
    </span>
  );
}

/**
 * One condition: a field, an operator and whatever value editor the kind
 * implies, as one inline pill. Blank — a field chosen and nothing said yet —
 * it is dashed; wrong, it is marked invalid. A condition that holds a tree
 * is a block instead: its header is the same field and operator, its body
 * the group it holds.
 */
export function ConditionPill({
  filter,
  leaf,
  path,
  disabled,
  optionsFor,
  isPending,
}: {
  filter: FilterTreeController;
  leaf: FilterLeaf;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
  isPending?: (path: FilterPath) => boolean;
}) {
  const messages = useViewMessages();
  const pending = isPending?.(path) === true;
  const field = filter.fields.find(entry => entry.name === leaf.field);
  const label = field?.label ?? leaf.field;
  const operators = filter.operatorsFor(leaf.field).map(operator => ({
    // The catalogue names every `FilterOperator`; the derived spelling is
    // the fallback for one a host's own kind offers, as it is in the summary
    // bar. It is a last resort and not a style: `OWNER_ID` derives to
    // `owner id`, which is the enum with a space in it.
    label: messages.label(
      `label.operator.${operator}`,
      undefined,
      operator.split('_').join(' ').toLowerCase(),
    ),
    value: operator,
  }));
  const editor = filter.editorFor(path);
  const kind = field && filter.kinds?.get(field.kind);
  const blank =
    field !== undefined &&
    kind !== undefined &&
    filter.kinds !== undefined &&
    isBlankLeafValue(leaf.value, leaf.operator, field, kind, filter.kinds);
  // `validateFilter` addresses a node as ['children', 0, 'children', 1, …];
  // its numeric segments are exactly this leaf's path.
  // An error marks the pill invalid; a warning marks it, in the theme's
  // warning colour, without saying it is wrong — the condition still runs.
  const own = filter.issues.filter(found =>
    samePath(numericPath(found.path), path),
  );
  const invalid = own.some(found => found.severity === 'error');
  const warned = !invalid && own.some(found => found.severity === 'warning');
  const holdsTree = editor?.input === 'predicate';
  // Two inputs need two cells' room; below two columns there is only the one.
  // A list of numbers asks for the same room: its values sit beside the field
  // they are typed into, and in one cell they wrap after the first.
  const wide =
    editor?.range === true ||
    (editor?.input === 'number' && editor.multiple === true) ||
    editor?.input === 'date' ||
    editor?.input === 'dateRange' ||
    editor?.input === 'relativeDate';

  const operatorSelect = (
    <Select
      items={operators}
      value={leaf.operator}
      disabled={disabled}
      onValueChange={value => {
        if (typeof value === 'string')
          filter.updateLeaf(path, { operator: value });
      }}
    >
      <SelectTrigger
        aria-label={messages.label('label.filter.operator-of', {
          field: label,
        })}
        size="sm"
        className="h-7 w-full border-0 bg-transparent px-1 shadow-none"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {operators.map(operator => (
            <SelectItem key={operator.value} value={operator.value}>
              {operator.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );

  const remove = (
    <Button
      variant="ghost"
      size="icon-sm"
      aria-label={messages.label('label.filter.remove-of', { field: label })}
      disabled={disabled}
      onClick={() => filter.remove(path)}
    >
      <XIcon />
    </Button>
  );

  if (holdsTree)
    return (
      <div
        data-slot="filter-element"
        role="group"
        aria-label={messages.label('label.filter.condition-of', {
          field: label,
        })}
        data-invalid={invalid || undefined}
        data-warning={warned || undefined}
        data-blank={blank || undefined}
        data-pending={pending || undefined}
        className="border-border data-[blank]:border-dashed data-[invalid]:border-destructive data-[warning]:border-warning relative col-span-full flex flex-col gap-1 rounded-md border p-2"
      >
        {pending && <PendingDot />}
        <div className="flex items-center gap-1">
          <span className="shrink-0 text-sm font-medium whitespace-nowrap">
            {label}
          </span>
          <div className="w-44 shrink-0">{operatorSelect}</div>
          {remove}
        </div>
        <NestedPredicate
          filter={filter}
          leaf={leaf}
          path={path}
          disabled={disabled}
          optionsFor={optionsFor}
        />
      </div>
    );

  return (
    <div
      data-slot="filter-condition"
      role="group"
      aria-label={messages.label('label.filter.condition-of', {
        field: label,
      })}
      data-invalid={invalid || undefined}
      data-warning={warned || undefined}
      data-blank={blank || undefined}
      data-wide={wide || undefined}
      data-pending={pending || undefined}
      className="border-border bg-muted/40 data-[blank]:border-dashed data-[invalid]:border-destructive data-[warning]:border-warning @[40rem]:data-[wide]:col-span-2 relative flex min-w-0 items-center gap-1 rounded-md border py-0.5 pr-0.5 pl-2 text-sm"
    >
      {pending && <PendingDot />}
      <span className="w-16 shrink-0 truncate font-medium" title={label}>
        {label}
      </span>
      <div className="w-24 shrink-0">{operatorSelect}</div>
      <div className="min-w-0 flex-1">
        {editor && (
          <FilterValueEditor
            editor={editor}
            value={leaf.value}
            // The field's title, as the other three names on this row use:
            // an identifier on screen is a word the interface never says
            // anywhere else.
            label={messages.label('label.filter.value-of', {
              field: label,
            })}
            disabled={disabled}
            options={editor.remote ? optionsFor?.(editor.remote) : undefined}
            onChange={value => filter.updateLeaf(path, { value })}
          />
        )}
      </div>
      {remove}
    </div>
  );
}

/**
 * A condition whose value is itself a condition, on the fields of an array's
 * elements.
 *
 * It renders the same group the outer filter renders, because it is the same
 * thing: `treeController` writes each change straight back into the leaf that
 * carries the tree. Two conditions written side by side outside this block
 * are satisfied by any entries, one each; inside it they must be satisfied by
 * the same entry.
 */
function NestedPredicate({
  filter,
  leaf,
  path,
  disabled,
  optionsFor,
}: {
  filter: FilterTreeController;
  leaf: FilterLeaf;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
}) {
  const field = filter.fields.find(entry => entry.name === leaf.field);
  // Built on every render rather than memoised: `filter` and `path` are fresh
  // each time, so a memo keyed on them memoised nothing and only hid that a
  // controller is a plain object over the values already in hand.
  const nested = treeController({
    tree: asTree(leaf.value),
    fields: field ? elementFields(field) : [],
    kinds: filter.kinds,
    // The kind reports a predicate's findings under the leaf that holds it,
    // so they are rebased here to address the nested tree instead.
    issues: rebase(filter.issues, path),
    onChange: tree => filter.updateLeaf(path, { value: writeValue(tree) }),
  });

  return (
    <div className="min-w-0">
      <GroupBlock
        filter={nested}
        group={nested.tree}
        path={[]}
        disabled={disabled}
        optionsFor={optionsFor}
        scope={field?.label ?? leaf.field}
      />
    </div>
  );
}

/** A leaf's value read as the tree it holds; an unfinished one is empty. */
function asTree(value: unknown): FilterTree {
  return value !== null &&
    typeof value === 'object' &&
    Array.isArray((value as FilterTree).children)
    ? (value as FilterTree)
    : { op: 'and', children: [] };
}

/** Findings under one leaf, addressed against the tree that leaf carries. */
function rebase(issues: readonly Issue[], path: FilterPath): Issue[] {
  const prefix = path.flatMap(index => ['children', index]);
  return issues.flatMap(found => {
    const own = found.path.slice(0, prefix.length);
    if (own.join('.') !== prefix.join('.')) return [];
    return [{ ...found, path: found.path.slice(prefix.length) }];
  });
}

/** The node indexes of an issue path, comparable against a `FilterPath`. */
function numericPath(path: IssuePath): FilterPath {
  return path.filter(segment => typeof segment === 'number');
}

function samePath(a: FilterPath, b: FilterPath): boolean {
  return (
    a.length === b.length && a.every((segment, index) => segment === b[index])
  );
}
