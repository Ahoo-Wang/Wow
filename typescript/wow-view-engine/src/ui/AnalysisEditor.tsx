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

import { useId } from 'react';
import { PlayIcon, PlusIcon, XIcon } from 'lucide-react';
import type {
  AnalysisGroup,
  AnalysisMetric,
  FieldGroupDefinition,
} from '../model/index.js';
import { CHART_TYPES } from '../model/index.js';
import type {
  AnalysisEditorController,
  AnalysisFieldOption,
} from '../react/index.js';
import { Button } from './components/button.js';
import { IconButton } from './IconButton.js';
import { Checkbox } from './components/checkbox.js';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Field, FieldGroup, FieldLabel } from './components/field.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { SPACE } from './layout.js';
import { DropdownMenuContent, SelectContent } from './popups.js';
import { crossesBoundary, leavesEditor } from './FilterPanel.js';
import { GroupedMenu } from './FieldMenu.js';
import { NumberInput } from './FilterValueEditor.js';
import { useViewMessages } from './MessagesProvider.js';

export interface AnalysisEditorProps {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}

/**
 * What to group by, what to measure, and how to draw it.
 *
 * Only what the definition's capability declares is offered: a backend that
 * cannot sum a column never shows the option, so an unrunnable query cannot
 * be built by clicking.
 */
export function AnalysisEditor({ analysis, disabled }: AnalysisEditorProps) {
  const messages = useViewMessages();
  const editorId = useId();
  const groupable = analysis.fields.filter(field => field.groups.length > 0);
  const measurable = analysis.fields.filter(
    field =>
      field.functions.length > 0 ||
      field.distinctCount ||
      field.percentile ||
      field.any,
  );

  return (
    <section
      data-slot="analysis-editor"
      aria-label={messages.label('label.analysis.editor')}
      className="flex flex-col gap-3"
      // Auto-refresh holds while a control in here has focus, as in the
      // filter panel; a move between two controls inside is neither.
      onFocus={event => {
        if (crossesBoundary(event)) analysis.focus();
      }}
      onBlur={event => {
        if (leavesEditor(event)) analysis.blur();
      }}
    >
      <div className="flex flex-wrap items-center gap-2">
        <AddMenu
          label={messages.label('label.analysis.add-group')}
          disabled={disabled}
          fields={groupable}
          groups={analysis.fieldGroups}
          onPick={field =>
            analysis.addGroup(defaultGroup(field, aliasesOf(analysis)))
          }
        />
        <AddMenu
          label={messages.label('label.analysis.add-metric')}
          disabled={disabled}
          fields={measurable}
          groups={analysis.fieldGroups}
          countable={analysis.countable}
          onPick={field =>
            analysis.addMetric(defaultMetric(field, aliasesOf(analysis)))
          }
          onCount={() =>
            analysis.addMetric({
              type: 'COUNT',
              alias: freeAlias('count', aliasesOf(analysis)),
            })
          }
        />

        <ToggleGroup
          value={[analysis.layout]}
          onValueChange={value => {
            const next = value[0];
            if (next === 'table' || next === 'chart') analysis.setLayout(next);
          }}
          variant="outline"
          size="sm"
          spacing={0}
          aria-label={messages.label('label.analysis.layout')}
        >
          <ToggleGroupItem value="table">
            {messages.label('label.layout.table')}
          </ToggleGroupItem>
          <ToggleGroupItem value="chart">
            {messages.label('label.layout.chart')}
          </ToggleGroupItem>
        </ToggleGroup>

        <ChartTypeSelect analysis={analysis} disabled={disabled} />

        {/* The visible word is the name: an `aria-label` beside it was a
            second, different wording that a reader heard instead of the one
            on screen. `Field` carries the pairing, and the checkbox takes
            its name from the label pointing at it. `w-auto` because a
            vertical `Field` is full width by default, and this one is one
            item on a wrapping toolbar row. */}
        <Field orientation="horizontal" className="w-auto">
          <Checkbox
            id={`${editorId}-totals`}
            checked={analysis.totals}
            disabled={disabled}
            onCheckedChange={checked => analysis.setTotals(checked === true)}
          />
          <FieldLabel htmlFor={`${editorId}-totals`}>
            {messages.label('label.analysis.totals')}
          </FieldLabel>
        </Field>

        {/*
          A limit has no blank: clearing the field leaves the last one in
          force rather than asking for zero rows, and the field stays empty
          while the next one is typed.
        */}
        <NumberInput
          label={messages.label('label.analysis.row-limit')}
          className="w-24"
          disabled={disabled}
          value={analysis.limit}
          onNumber={next => {
            if (next !== null) analysis.setLimit(next);
          }}
        />

        <Button size="sm" disabled={disabled} onClick={analysis.submit}>
          <PlayIcon data-icon="inline-start" />
          {messages.label('label.analysis.run')}
        </Button>
      </div>

      {/*
        The group and metric rows are rows inside one block, so they take
        `SPACE.ROWS`. Vendored `FieldGroup` brings its own `gap-5` — 20px, a
        step that is not on the four-step ruler at all and is wider than the
        16px between two whole blocks, which would put a block's own rows
        further apart than the blocks themselves. `ui/components/**` is
        upstream's and is not edited by hand, so the seam is closed at the
        call site; `cn` merges the two `gap-*` and the one passed in wins.
        (The segmented switch above needs no such thing: its own `spacing`
        prop is the registry's way of asking for a joined group.)
      */}
      {(analysis.groups.length > 0 || analysis.metrics.length > 0) && (
        <FieldGroup className={SPACE.ROWS}>
          {analysis.groups.map((group, index) => (
            <GroupRow
              key={group.alias}
              analysis={analysis}
              group={group}
              index={index}
              disabled={disabled}
            />
          ))}
          {analysis.metrics.map((metric, index) => (
            <MetricRow
              key={metric.alias}
              analysis={analysis}
              metric={metric}
              index={index}
              disabled={disabled}
            />
          ))}
        </FieldGroup>
      )}
    </section>
  );
}

function AddMenu({
  label,
  fields,
  groups,
  disabled,
  countable,
  onPick,
  onCount,
}: {
  label: string;
  fields: AnalysisFieldOption[];
  groups: readonly FieldGroupDefinition[];
  disabled?: boolean;
  countable?: boolean;
  onPick(field: AnalysisFieldOption): void;
  onCount?(): void;
}) {
  const messages = useViewMessages();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="outline" size="sm" disabled={disabled} />}
      >
        <PlusIcon data-icon="inline-start" />
        {label}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        {countable === true && onCount && (
          <DropdownMenuGroup>
            <DropdownMenuItem onClick={onCount}>
              {messages.label('label.analysis.row-count')}
            </DropdownMenuItem>
          </DropdownMenuGroup>
        )}
        <GroupedMenu
          items={fields}
          groups={groups}
          itemKey={field => field.field}
          render={field => (
            <DropdownMenuItem key={field.field} onClick={() => onPick(field)}>
              {field.label}
            </DropdownMenuItem>
          )}
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function ChartTypeSelect({
  analysis,
  disabled,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  // `CHART_TYPES` is this package's own constant, so every member is named in
  // the catalogue and none of these can fall back.
  const items = CHART_TYPES.map(type => ({
    label: messages.label(`label.chart.type.${type}`),
    value: type,
  }));
  return (
    <Select
      items={items}
      value={analysis.chart.type}
      disabled={disabled}
      onValueChange={value => {
        if (typeof value === 'string') analysis.setChartType(value);
      }}
    >
      <SelectTrigger
        aria-label={messages.label('label.analysis.chart-type')}
        size="sm"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {items.map(item => (
            <SelectItem key={item.value} value={item.value}>
              {item.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}

function GroupRow({
  analysis,
  group,
  index,
  disabled,
}: {
  analysis: AnalysisEditorController;
  group: AnalysisGroup;
  index: number;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const field = analysis.fields.find(entry => entry.field === group.field);
  // What a definition declares it can group by. The catalogue names every
  // member of the enum; the derived spelling stays as the fallback because
  // this list comes from a host's capability data rather than from here.
  const types = (field?.groups ?? []).map(type => ({
    label: messages.label(
      `label.group.type.${type}`,
      undefined,
      type.split('_').join(' ').toLowerCase(),
    ),
    value: type,
  }));

  return (
    <Field orientation="horizontal" className="items-center">
      <FieldLabel className="min-w-28">
        {field?.label ?? group.field}
      </FieldLabel>
      <Select
        items={types}
        value={group.type}
        disabled={disabled}
        onValueChange={value => {
          if (typeof value === 'string')
            analysis.updateGroup(index, groupOfType(group, value, field));
        }}
      >
        <SelectTrigger
          aria-label={messages.label('label.analysis.grouping-of', {
            alias: group.alias,
          })}
          size="sm"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {types.map(type => (
              <SelectItem key={type.value} value={type.value}>
                {type.label}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <IconButton
        label={messages.label('label.analysis.remove-group', {
          alias: group.alias,
        })}
        variant="ghost"
        size="icon-sm"
        disabled={disabled}
        onClick={() => analysis.removeGroup(index)}
      >
        <XIcon />
      </IconButton>
    </Field>
  );
}

function MetricRow({
  analysis,
  metric,
  index,
  disabled,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const field = fieldOfMetric(metric);
  const option = analysis.fields.find(entry => entry.field === field);
  const functions = (option?.functions ?? []).map(name => ({
    label: messages.label(
      `label.metric.function.${name}`,
      undefined,
      name.toLowerCase(),
    ),
    value: name,
  }));

  return (
    <Field orientation="horizontal" className="items-center">
      <FieldLabel className="min-w-28">
        {metric.type === 'COUNT'
          ? messages.label('label.analysis.row-count')
          : (option?.label ?? field)}
      </FieldLabel>
      {metric.type === 'NUMERIC' && functions.length > 0 && (
        <Select
          items={functions}
          value={metric.function}
          disabled={disabled}
          onValueChange={value => {
            if (typeof value === 'string')
              analysis.updateMetric(index, {
                function: value,
              });
          }}
        >
          <SelectTrigger
            aria-label={messages.label('label.analysis.function-of', {
              alias: metric.alias,
            })}
            size="sm"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {functions.map(name => (
                <SelectItem key={name.value} value={name.value}>
                  {name.label}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      )}
      <IconButton
        label={messages.label('label.analysis.remove-metric', {
          alias: metric.alias,
        })}
        variant="ghost"
        size="icon-sm"
        disabled={disabled || analysis.metrics.length <= 1}
        onClick={() => analysis.removeMetric(index)}
      >
        <XIcon />
      </IconButton>
    </Field>
  );
}

/**
 * A name no group or metric is using.
 *
 * Numbering by the row count collided as soon as a row was removed: two rows
 * added after one deletion were both `amount_2`, which React saw as one key
 * and validation reported as a duplicate alias. The first free number cannot
 * collide however the rows were added and removed. Aliases are single-segment
 * in Wow, so a field path becomes one token.
 */
function freeAlias(base: string, taken: readonly string[]): string {
  const stem = base.split('.').join('_');
  const used = new Set(taken);
  for (let index = 1; ; index += 1) {
    const alias = `${stem}_${index}`;
    if (!used.has(alias)) return alias;
  }
}

/** Every alias in use, which is the set an addition must stay clear of. */
function aliasesOf(analysis: AnalysisEditorController): string[] {
  return [...analysis.aliases.groups, ...analysis.aliases.metrics];
}

function defaultGroup(
  field: AnalysisFieldOption,
  taken: readonly string[],
): AnalysisGroup {
  const alias = freeAlias(field.field, taken);
  const type = field.groups[0];
  if (type === 'DATE_HISTOGRAM')
    return {
      type: 'DATE_HISTOGRAM',
      field: field.field,
      alias,
      unit: field.dateUnits[0] ?? 'DAY',
    };
  if (type === 'HISTOGRAM')
    return { type: 'HISTOGRAM', field: field.field, alias, interval: 1 };
  return { type: 'TERMS', field: field.field, alias };
}

function defaultMetric(
  field: AnalysisFieldOption,
  taken: readonly string[],
): AnalysisMetric {
  const alias = freeAlias(field.field, taken);
  if (field.functions.length > 0)
    return {
      type: 'NUMERIC',
      alias,
      function: field.functions[0],
      expression: { type: 'FIELD', field: field.field },
    };
  if (field.distinctCount)
    return {
      type: 'DISTINCT_COUNT',
      alias,
      expression: { type: 'FIELD', field: field.field },
    };
  if (field.percentile)
    return {
      type: 'PERCENTILE',
      alias,
      expression: { type: 'FIELD', field: field.field },
      percentile: 95,
    };
  return { type: 'ANY', alias, field: field.field };
}

function groupOfType(
  group: AnalysisGroup,
  type: string,
  field: AnalysisFieldOption | undefined,
): Partial<AnalysisGroup> {
  if (type === 'DATE_HISTOGRAM')
    return { type: 'DATE_HISTOGRAM', unit: field?.dateUnits[0] ?? 'DAY' };
  if (type === 'HISTOGRAM') return { type: 'HISTOGRAM', interval: 1 };
  return { type: 'TERMS' };
}

function fieldOfMetric(metric: AnalysisMetric): string {
  if (metric.type === 'COUNT' || metric.type === 'DERIVED') return '';
  if (metric.type === 'ANY') return metric.field;
  return metric.expression.type === 'FIELD' ? metric.expression.field : '';
}
