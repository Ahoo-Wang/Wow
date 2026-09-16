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
import type { FieldOption, FilterLeaf } from '../model/index.js';
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
import { FilterValueEditor } from './FilterValueEditor.js';

export interface FilterPanelProps {
  filter: FilterEditorController;
  /** Candidates for a `remote` value editor, by the key its kind declared. */
  optionsFor?(remote: string): FieldOption[] | undefined;
  disabled?: boolean;
}

/**
 * The condition builder of the simple mode: one row per leaf, a field, an
 * operator and whatever value editor the kind implies.
 *
 * Nothing is applied until submit, which is the whole point of keeping a
 * draft apart from what ran: typing in here never re-queries.
 */
export function FilterPanel({
  filter,
  optionsFor,
  disabled,
}: FilterPanelProps) {
  const leaves = filter.tree.children.flatMap((node, index) =>
    'children' in node ? [] : [{ node, index }],
  );

  return (
    <section
      data-slot="filter-panel"
      aria-label="Filter"
      className="flex flex-col gap-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <ToggleGroup
          value={[filter.mode]}
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

        <DropdownMenu>
          <DropdownMenuTrigger
            render={<Button variant="outline" size="sm" disabled={disabled} />}
          >
            <PlusIcon data-icon="inline-start" />
            Add condition
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            <DropdownMenuGroup>
              {filter.fields.map(field => (
                <DropdownMenuItem
                  key={field.name}
                  onClick={() => filter.addLeaf(field.name)}
                >
                  {field.label}
                </DropdownMenuItem>
              ))}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>

        <Button
          variant="outline"
          size="sm"
          disabled={disabled || filter.count === 0}
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

      {leaves.length > 0 && (
        <FieldGroup>
          {leaves.map(({ node, index }) => (
            <FilterLeafRow
              key={`${node.field}-${index}`}
              filter={filter}
              leaf={node}
              index={index}
              disabled={disabled}
              optionsFor={optionsFor}
            />
          ))}
        </FieldGroup>
      )}
    </section>
  );
}

function FilterLeafRow({
  filter,
  leaf,
  index,
  disabled,
  optionsFor,
}: {
  filter: FilterEditorController;
  leaf: FilterLeaf;
  index: number;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
}) {
  const field = filter.fields.find(entry => entry.name === leaf.field);
  const label = field?.label ?? leaf.field;
  const operators = filter.operatorsFor(leaf.field).map(operator => ({
    label: operator.split('_').join(' ').toLowerCase(),
    value: operator,
  }));
  const editor = filter.editorFor([index]);
  const invalid = filter.issues.some(
    found => found.path[found.path.length - 1] === index,
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
            filter.updateLeaf([index], { operator: value });
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
          onChange={value => filter.updateLeaf([index], { value })}
        />
      )}

      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Remove ${label}`}
        disabled={disabled}
        onClick={() => filter.remove([index])}
      >
        <XIcon />
      </Button>
    </Field>
  );
}
