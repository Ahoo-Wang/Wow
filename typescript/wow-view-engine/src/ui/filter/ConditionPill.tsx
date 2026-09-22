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

import { EqualNotIcon, XIcon } from 'lucide-react';
import { cn } from 'cn';
import type {
  FieldOption,
  FilterLeaf,
  FilterOperatorName,
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
import { IconButton, IconTooltip } from '../IconButton.js';
import { Badge } from '../components/badge.js';
import { Toggle } from '../components/toggle.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectValue,
} from '../components/select.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import { SelectContent, TooltipContent } from '../popups.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { FilterValueEditor } from '../FilterValueEditor.js';
import { PendingDot, PENDING_AT_CORNER } from '../PendingDot.js';
import { PillSelectTrigger } from '../variants.js';
import { UnsupportedValue } from './inputs/unsupported.js';
import { GroupBlock } from './GroupBlock.js';

/**
 * The pill's own frame, shared by the editable condition and the read-only
 * one, so a condition nothing can edit is still recognisably a condition and
 * sits in the same track as its neighbours.
 */
const PILL_FRAME =
  'border-border bg-muted/40 data-[blank]:border-dashed data-[invalid]:border-destructive data-[warning]:border-warning @[40rem]:data-[wide]:col-span-2 relative flex min-w-0 flex-wrap items-center gap-1 rounded-md border py-0.5 pr-0.5 pl-2 text-sm';

/**
 * One condition: a field, an operator and whatever value editor the kind
 * implies, as one inline pill. Blank — a field chosen and nothing said yet —
 * it is dashed; wrong, it is marked invalid. A condition that holds a tree
 * is a block instead: its header is the same field and operator, its body
 * the group it holds.
 *
 * A condition whose kind the registry does not know is drawn read-only. The
 * picker cannot produce one (`fieldsFor`), so it only ever arrives from a
 * stored config whose definition has since changed, and the only two things
 * the user can do with it are read it and take it out — which is exactly
 * what the read-only pill offers.
 */
export function ConditionPill({
  filter,
  leaf,
  path,
  disabled,
  optionsFor,
  isPending,
  negatable,
  negated,
}: {
  filter: FilterTreeController;
  leaf: FilterLeaf;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
  isPending?: (path: FilterPath) => boolean;
  /**
   * Whether the pill offers its negation switch (D18-7). Simple mode's
   * strip does; advanced mode has the group operator for the same thing,
   * and a switch beside a "None of" header would say it twice.
   */
  negatable?: boolean;
  /**
   * True when this condition sits alone in a `nor` group of its own — then
   * `path` is the leaf's inside that wrapper, and removing the condition
   * removes the wrapper with it.
   */
  negated?: boolean;
}) {
  const messages = useViewMessages();
  const pending = isPending?.(path) === true;
  const field = filter.fields.find(entry => entry.name === leaf.field);
  const label = field?.label ?? leaf.field;
  const operators = filter.operatorsFor(leaf.field).map(operator => ({
    label: operatorLabel(messages, operator),
    value: operator,
  }));
  const editor = filter.editorFor(path);
  const kind = field && filter.kinds?.get(field.kind);
  // A field the definition still declares, of a kind the registry does not
  // know. A field the definition has dropped is a different finding
  // (`filter.field.unknown`) with a different fix — this one names a kind
  // that could be registered.
  const unregistered = field !== undefined && kind === undefined;
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
  // Which control is wrong, and not only which pill. `data-invalid` below
  // is a border, and a border is nothing a screen reader reads: a reader
  // tabbing into a refused condition heard an ordinary select and an
  // ordinary box. The code says which one it is about — an operator the
  // kind does not support is the select's, a value rule is the value
  // editor's, and a field the definition has dropped is the whole
  // condition's, so both carry it: there is no control for the field, whose
  // name is the word at the front of the pill.
  const errors = own.filter(found => found.severity === 'error');
  const invalidOperator = errors.some(
    found => !found.code.startsWith('filter.value.'),
  );
  const invalidValue = errors.some(
    found => !found.code.startsWith('filter.operator.'),
  );
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
      {/* One border per condition (D12): the pill is the field, so the
          select inside it draws none of its own. The variant carries that,
          not a `className` of colours — `ui/variants.tsx`. */}
      <PillSelectTrigger
        aria-label={messages.label('label.filter.operator-of', {
          field: label,
        })}
        aria-invalid={invalidOperator || undefined}
        size="sm"
        className="h-7 w-full px-1"
      >
        <SelectValue />
      </PillSelectTrigger>
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
    <IconButton
      label={messages.label('label.filter.remove-of', { field: label })}
      variant="ghost"
      size="icon-sm"
      disabled={disabled}
      onClick={() => filter.remove(negated ? path.slice(0, -1) : path)}
    >
      <XIcon />
    </IconButton>
  );

  // The switch sits with the ✕ at the end, so a condition that is not
  // negated reads as the plain sentence it is; once pressed, the word joins
  // the sentence in front of the operator — "Created *not* between …" — and
  // the switch stays pressed to say where it is undone. The two are one
  // state: `data-negated` on the pill is what a test and a stylesheet read.
  const negate = negatable && (
    <IconTooltip
      label={messages.label('label.filter.negate-of', { field: label })}
      render={
        <Toggle
          data-slot="filter-negate"
          size="sm"
          className="size-7 min-w-7 px-0"
          pressed={negated === true}
          disabled={disabled}
          onPressedChange={() => filter.negate(path)}
        />
      }
    >
      <EqualNotIcon />
    </IconTooltip>
  );
  const negatedWord = negated && (
    <Badge
      data-slot="filter-negated"
      variant="secondary"
      className="shrink-0 px-1.5"
    >
      {messages.label('label.filter.negated')}
    </Badge>
  );

  // The field's name is the one word on this row with a width of its own, so
  // it is the one that truncates. The whole of it is one hover away — a
  // `Tooltip` rather than the native `title` this used to be (D16), because
  // the ellipsis is just as final for a keyboard and a touch user, who never
  // get `title` at all.
  const name = (
    <Tooltip>
      <TooltipTrigger
        render={<span className="w-16 shrink-0 truncate font-medium" />}
      >
        {label}
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );

  if (unregistered)
    return (
      <div
        data-slot="filter-condition"
        data-unsupported=""
        role="group"
        aria-label={messages.label('label.filter.condition-of', {
          field: label,
        })}
        // Refused, in the tone every refused condition wears: `validateFilter`
        // reports `filter.kind.unregistered` for this very leaf, so the border
        // is not a second opinion but the same one. It is set here rather than
        // read from `invalid` because the pill must look refused even where
        // this editor was handed no issues at all — a host rendering the tree
        // without a runtime still has no editor for the kind.
        data-invalid=""
        data-pending={pending || undefined}
        data-negated={negated || undefined}
        className={PILL_FRAME}
      >
        {pending && <PendingDot named className={PENDING_AT_CORNER} />}
        {name}
        {negatedWord}
        {/* The operator as the word the dropdown would have shown, not as a
            dropdown: the kind is what declares which operators a field
            offers, and without it there is no list to choose from. An empty
            select was the old answer, and it read as a choice the user had
            failed to make. */}
        <span className="w-24 shrink-0 truncate">
          {operatorLabel(messages, leaf.operator)}
        </span>
        <UnsupportedValue
          kind={field.kind}
          value={leaf.value}
          className="min-w-24 flex-1"
        />
        {remove}
      </div>
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
        data-negated={negated || undefined}
        className="border-border data-[blank]:border-dashed data-[invalid]:border-destructive data-[warning]:border-warning relative col-span-full flex flex-col gap-1 rounded-md border p-2"
      >
        {pending && <PendingDot named className={PENDING_AT_CORNER} />}
        <div className="flex items-center gap-1">
          <span className="shrink-0 text-sm font-medium whitespace-nowrap">
            {label}
          </span>
          {negatedWord}
          <div className="w-44 shrink-0">{operatorSelect}</div>
          {negate}
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
      data-negated={negated || undefined}
      // `flex-wrap`: the value takes a line of its own where the strip is too
      // narrow to hold the whole sentence on one — see the floor on the value
      // below. Nothing wraps at a strip width the track was designed for.
      className={PILL_FRAME}
    >
      {pending && <PendingDot named className={PENDING_AT_CORNER} />}
      {name}
      {negatedWord}
      <div className="w-24 shrink-0">{operatorSelect}</div>
      {/* One border per condition (D12): the pill is the field, so the
          value control inside it draws none of its own — like the operator
          select beside it — and shows focus by the ring alone. That used to
          be written here, as `[&_[data-slot=input]]:…` reaching two levels
          down into whichever vendored components the editor happened to
          render; it is each control's own variant now (`ui/variants.tsx`),
          so a registry rename cannot silently give the pill its borders
          back. */}
      <div
        data-slot="filter-value"
        className={cn(
          'min-w-0 flex-1',
          // The floor that decides where the pill wraps. A value control is
          // the answer itself, and below a few characters it stops being one:
          // on a 420-wide strip the field's name and the operator left 38px,
          // in which a range's second box ran 31px past the ✕ and an enum
          // select showed its chevron and none of the value. The floor makes
          // the value drop to a line of its own instead, where it has the
          // whole pill — 6rem for one input, 9rem for the two a range or a
          // date needs. Above the floor it still takes only what is left, and
          // clamps.
          wide ? 'min-w-36' : 'min-w-24',
        )}
      >
        {/* `field` is defined wherever `editor` is — the descriptor comes
            from its kind — and is named so the fallback can say which kind
            asked for an input nobody wrote. */}
        {editor && field && (
          <FilterValueEditor
            editor={editor}
            kind={field.kind}
            value={leaf.value}
            // The field's title, as the other three names on this row use:
            // an identifier on screen is a word the interface never says
            // anywhere else.
            label={messages.label('label.filter.value-of', {
              field: label,
            })}
            disabled={disabled}
            invalid={invalidValue || undefined}
            options={editor.remote ? optionsFor?.(editor.remote) : undefined}
            source={
              editor.remote
                ? (filter.optionSource?.(editor.remote) ?? null)
                : null
            }
            onChange={value => filter.updateLeaf(path, { value })}
          />
        )}
      </div>
      {negate}
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
    ...(filter.optionSource ? { optionSource: filter.optionSource } : {}),
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

/**
 * One operator as a word.
 *
 * The catalogue names every `FilterOperator`; the derived spelling is the
 * fallback for one a host's own kind offers, as it is in the summary bar. It
 * is a last resort and not a style: `OWNER_ID` derives to `owner id`, which
 * is the enum with a space in it. The select and the read-only reading below
 * share it, so a condition nothing can edit still says the same word it would
 * have said in the dropdown.
 */
function operatorLabel(
  messages: MessageFormatters,
  operator: FilterOperatorName,
): string {
  return messages.label(
    `label.operator.${operator}`,
    undefined,
    operator.split('_').join(' ').toLowerCase(),
  );
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
