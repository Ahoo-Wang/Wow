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

import { PlayIcon, PlusIcon, XIcon } from 'lucide-react';
import type { AnalysisGroup, AnalysisMetric } from '../model/index.js';
import { CHART_TYPES } from '../model/index.js';
import type {
  AnalysisEditorController,
  AnalysisFieldOption,
} from '../react/index.js';
import { Button } from './components/button.js';
import { Checkbox } from './components/checkbox.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Field, FieldGroup, FieldLabel } from './components/field.js';
import { Input } from './components/input.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';

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
      aria-label="Analysis"
      className="flex flex-col gap-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <AddMenu
          label="Add group"
          disabled={disabled}
          fields={groupable}
          onPick={field =>
            analysis.addGroup(defaultGroup(field, analysis.groups.length))
          }
        />
        <AddMenu
          label="Add metric"
          disabled={disabled}
          fields={measurable}
          countable={analysis.countable}
          onPick={field =>
            analysis.addMetric(defaultMetric(field, analysis.metrics.length))
          }
          onCount={() =>
            analysis.addMetric({
              type: 'COUNT',
              alias: nextAlias('count', analysis.metrics.length),
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
          aria-label="Analysis layout"
        >
          <ToggleGroupItem value="table">Table</ToggleGroupItem>
          <ToggleGroupItem value="chart">Chart</ToggleGroupItem>
        </ToggleGroup>

        <ChartTypeSelect analysis={analysis} disabled={disabled} />

        <label className="flex items-center gap-2 text-sm">
          <Checkbox
            checked={analysis.totals}
            disabled={disabled}
            aria-label="Show totals"
            onCheckedChange={checked => analysis.setTotals(checked === true)}
          />
          Totals
        </label>

        <Input
          type="number"
          aria-label="Row limit"
          className="w-24"
          disabled={disabled}
          value={String(analysis.limit)}
          onChange={event => analysis.setLimit(Number(event.target.value))}
        />

        <Button size="sm" disabled={disabled} onClick={analysis.submit}>
          <PlayIcon data-icon="inline-start" />
          Run
        </Button>
      </div>

      {(analysis.groups.length > 0 || analysis.metrics.length > 0) && (
        <FieldGroup>
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
  disabled,
  countable,
  onPick,
  onCount,
}: {
  label: string;
  fields: AnalysisFieldOption[];
  disabled?: boolean;
  countable?: boolean;
  onPick(field: AnalysisFieldOption): void;
  onCount?(): void;
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
          {countable === true && onCount && (
            <DropdownMenuItem onClick={onCount}>Row count</DropdownMenuItem>
          )}
          {fields.map(field => (
            <DropdownMenuItem key={field.field} onClick={() => onPick(field)}>
              {field.label}
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
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
  const items = CHART_TYPES.map(type => ({ label: type, value: type }));
  return (
    <Select
      items={items}
      value={analysis.chart.type}
      disabled={disabled}
      onValueChange={value => {
        if (typeof value === 'string') analysis.setChartType(value);
      }}
    >
      <SelectTrigger aria-label="Chart type" size="sm">
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
  const field = analysis.fields.find(entry => entry.field === group.field);
  const types = (field?.groups ?? []).map(type => ({
    label: type.split('_').join(' ').toLowerCase(),
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
        <SelectTrigger aria-label={`${group.alias} grouping`} size="sm">
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
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Remove group ${group.alias}`}
        disabled={disabled}
        onClick={() => analysis.removeGroup(index)}
      >
        <XIcon />
      </Button>
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
  const field = fieldOfMetric(metric);
  const option = analysis.fields.find(entry => entry.field === field);
  const functions = (option?.functions ?? []).map(name => ({
    label: name.toLowerCase(),
    value: name,
  }));

  return (
    <Field orientation="horizontal" className="items-center">
      <FieldLabel className="min-w-28">
        {metric.type === 'COUNT' ? 'Row count' : (option?.label ?? field)}
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
          <SelectTrigger aria-label={`${metric.alias} function`} size="sm">
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
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={`Remove metric ${metric.alias}`}
        disabled={disabled || analysis.metrics.length <= 1}
        onClick={() => analysis.removeMetric(index)}
      >
        <XIcon />
      </Button>
    </Field>
  );
}

/** Aliases are single-segment in Wow, so a field path becomes one token. */
function nextAlias(base: string, index: number): string {
  return `${base.split('.').join('_')}_${index + 1}`;
}

function defaultGroup(
  field: AnalysisFieldOption,
  index: number,
): AnalysisGroup {
  const alias = nextAlias(field.field, index);
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
  index: number,
): AnalysisMetric {
  const alias = nextAlias(field.field, index);
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
