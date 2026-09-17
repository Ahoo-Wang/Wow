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

import { FilterIcon, PlusIcon, XIcon } from 'lucide-react';
import type {
  FieldOption,
  FilterGroup,
  FilterLeaf,
  IssuePath,
} from '../model/index.js';
import type { FilterPath } from '../filter/index.js';
import type { FilterEditorController } from '../react/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Field, FieldGroup, FieldLabel } from './components/field.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { useViewMessages } from './MessagesProvider.js';
import { FilterValueEditor } from './FilterValueEditor.js';

export interface FilterPanelProps {
  filter: FilterEditorController;
  /** Candidates for a `remote` value editor, by the key its kind declared. */
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
}

/**
 * The condition builder: one row per leaf, a field, an operator and whatever
 * value editor the kind implies. Simple mode shows the root's leaves flat;
 * anything the simple editor cannot show faithfully — a group anywhere — gets
 * the advanced one, where groups are framed blocks that can be flipped
 * between and/or, nested, filled and removed.
 *
 * Nothing is applied until submit, which is the whole point of keeping a
 * draft apart from what ran: typing in here never re-queries.
 */
export function FilterPanel({
  filter,
  optionsFor,
  disabled,
}: FilterPanelProps) {
  const advanced = filter.mode === 'advanced' || !filter.simple;
  const messages = useViewMessages();
  // A stored tree can exceed the depth or node budget; the validator reports
  // it as an error, and the panel must not recurse into it anyway.
  const overBudget = filter.issues.some(
    found =>
      found.severity === 'error' &&
      (found.code === 'filter.tree.too-deep' ||
        found.code === 'filter.tree.too-many-nodes'),
  );

  return (
    <section
      data-slot="filter-panel"
      aria-label="Filter"
      className="flex flex-col gap-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <ToggleGroup
          // The effective mode, not the saved one: a simple config holding a
          // tree the simple editor cannot show opens in the advanced one, and
          // the toggle says so instead of contradicting the editor below.
          value={[advanced ? 'advanced' : 'simple']}
          onValueChange={value => {
            const next = value[0];
            if (next === 'simple' || next === 'advanced') filter.setMode(next);
          }}
          variant="outline"
          size="sm"
          aria-label="Filter mode"
        >
          <ToggleGroupItem value="simple">Simple</ToggleGroupItem>
          <ToggleGroupItem value="advanced">Advanced</ToggleGroupItem>
        </ToggleGroup>

        <AddCondition filter={filter} parent={[]} disabled={disabled} />

        {advanced && (
          <Button
            variant="outline"
            size="sm"
            disabled={disabled}
            onClick={() => filter.addGroup('or')}
          >
            <PlusIcon data-icon="inline-start" />
            Add group
          </Button>
        )}

        <Button
          variant="outline"
          size="sm"
          // An over-budget tree may hold no leaf at all — deep groups — and
          // clearing it is then the only way back to an editable filter.
          disabled={disabled || (filter.count === 0 && !overBudget)}
          onClick={filter.clear}
        >
          Clear
        </Button>
        <Button size="sm" disabled={disabled} onClick={filter.submit}>
          <FilterIcon data-icon="inline-start" />
          Apply
        </Button>

        {filter.applied.length > 0 && (
          <div className="flex flex-wrap items-center gap-1">
            {filter.applied.map(item => (
              <Badge key={item.path.join('.')} variant="secondary">
                {item.text}
              </Badge>
            ))}
          </div>
        )}
      </div>

      {overBudget ? (
        <p
          data-slot="filter-too-large"
          className="text-muted-foreground text-sm"
        >
          {messages.label('label.filter.too-large')}
        </p>
      ) : advanced ? (
        <GroupBlock
          filter={filter}
          group={filter.tree}
          path={[]}
          disabled={disabled}
          optionsFor={optionsFor}
        />
      ) : (
        filter.count > 0 && (
          <FieldGroup>
            {filter.tree.children.map((node, index) =>
              'children' in node ? null : (
                <FilterLeafRow
                  key={`${node.field}-${index}`}
                  filter={filter}
                  leaf={node}
                  path={[index]}
                  disabled={disabled}
                  optionsFor={optionsFor}
                />
              ),
            )}
          </FieldGroup>
        )
      )}
    </section>
  );
}

/** One group as a framed block: its operator, its children, room to add. */
function GroupBlock({
  filter,
  group,
  path,
  disabled,
  optionsFor,
}: {
  filter: FilterEditorController;
  group: FilterGroup;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
}) {
  const nested = path.length > 0;

  return (
    <div
      data-slot="filter-group"
      role="group"
      aria-label={group.op === 'or' ? 'Any of' : 'All of'}
      className="flex flex-col gap-2 rounded-md border border-border p-2"
    >
      <div className="flex items-center gap-1">
        <ToggleGroup
          value={[group.op]}
          onValueChange={value => {
            const next = value[0];
            if (next === 'and' || next === 'or') filter.updateGroup(path, next);
          }}
          variant="outline"
          size="sm"
          disabled={disabled}
          aria-label={
            nested ? `Group operator ${path.join('.')}` : 'Group operator'
          }
        >
          <ToggleGroupItem value="and">All of</ToggleGroupItem>
          <ToggleGroupItem value="or">Any of</ToggleGroupItem>
        </ToggleGroup>

        {nested && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="Remove group"
            disabled={disabled}
            onClick={() => filter.remove(path)}
          >
            <XIcon />
          </Button>
        )}
      </div>

      {group.children.map((child, index) =>
        'children' in child ? (
          <GroupBlock
            key={index}
            filter={filter}
            group={child}
            path={[...path, index]}
            disabled={disabled}
            optionsFor={optionsFor}
          />
        ) : (
          <FilterLeafRow
            key={`${child.field}-${index}`}
            filter={filter}
            leaf={child}
            path={[...path, index]}
            disabled={disabled}
            optionsFor={optionsFor}
          />
        ),
      )}

      <div className="flex flex-wrap items-center gap-1">
        <AddCondition
          filter={filter}
          parent={path}
          disabled={disabled}
          label="Add condition in this group"
        />
        <Button
          variant="ghost"
          size="sm"
          disabled={disabled}
          onClick={() => filter.addGroup('and', path)}
        >
          <PlusIcon data-icon="inline-start" />
          Add group
        </Button>
      </div>
    </div>
  );
}

/** The field picker that appends a leaf to one group. */
function AddCondition({
  filter,
  parent,
  disabled,
  label = 'Add condition',
}: {
  filter: FilterEditorController;
  parent: FilterPath;
  disabled?: boolean;
  label?: string;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="outline" size="sm" disabled={disabled} />}
      >
        <PlusIcon data-icon="inline-start" />
        {label}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuGroup>
          {filter.fields.map(field => (
            <DropdownMenuItem
              key={field.name}
              onClick={() => filter.addLeaf(field.name, parent)}
            >
              {field.label}
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function FilterLeafRow({
  filter,
  leaf,
  path,
  disabled,
  optionsFor,
}: {
  filter: FilterEditorController;
  leaf: FilterLeaf;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
}) {
  const field = filter.fields.find(entry => entry.name === leaf.field);
  const label = field?.label ?? leaf.field;
  const operators = filter.operatorsFor(leaf.field).map(operator => ({
    label: operator.split('_').join(' ').toLowerCase(),
    value: operator,
  }));
  const editor = filter.editorFor(path);
  // `validateFilter` addresses a node as ['children', 0, 'children', 1, …];
  // its numeric segments are exactly this leaf's path.
  const invalid = filter.issues.some(found =>
    samePath(numericPath(found.path), path),
  );

  return (
    <Field
      orientation="horizontal"
      data-invalid={invalid || undefined}
      className="items-center"
    >
      <FieldLabel className="min-w-28">{label}</FieldLabel>

      <Select
        items={operators}
        value={leaf.operator}
        disabled={disabled}
        onValueChange={value => {
          if (typeof value === 'string')
            filter.updateLeaf(path, { operator: value });
        }}
      >
        <SelectTrigger aria-label={`${label} operator`} size="sm">
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

      {editor && (
        <FilterValueEditor
          editor={editor}
          value={leaf.value}
          label={`${leaf.field} value`}
          disabled={disabled}
          options={editor.remote ? optionsFor?.(editor.remote) : undefined}
          onChange={value => filter.updateLeaf(path, { value })}
        />
      )}

      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Remove ${label}`}
        disabled={disabled}
        onClick={() => filter.remove(path)}
      >
        <XIcon />
      </Button>
    </Field>
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
