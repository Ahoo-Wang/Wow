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

import { FunnelIcon, XIcon } from 'lucide-react';
import type { FilterSummaryItem } from '../../filter/index.js';
import type {
  AnalysisMetric,
  FieldDefinition,
  FieldOption,
  FilterTree,
  Issue,
  IssuePath,
} from '../../model/index.js';
import {
  treeController,
  type AnalysisEditorController,
} from '../../react/index.js';
import { cn } from 'cn';
import { Button } from '../components/button.js';
import { GroupBlock } from '../filter/GroupBlock.js';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { onlyWhereText } from '../summary.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/** The findings under one place in the config, addressed against the tree there. */
function issuesAt(issues: readonly Issue[], at: IssuePath): Issue[] {
  return issues.flatMap(found =>
    at.every((step, index) => found.path[index] === step)
      ? [{ ...found, path: found.path.slice(at.length) }]
      : [],
  );
}

/**
 * The way into a metric's own conditions (D20 屏 H): the funnel on the
 * card, pressed while the block under it is open and lit while the metric
 * carries a condition, so a number's scope is never hidden behind an icon
 * alone. A derived metric has none — the protocol gives it no filter.
 */
export function ConditionButton({
  label,
  open,
  held,
  disabled,
  onToggle,
}: {
  /** The button's name: whose conditions it opens. */
  label: string;
  open: boolean;
  /** Whether the metric carries a condition already. */
  held: boolean;
  disabled?: boolean;
  onToggle(): void;
}) {
  return (
    <IconButton
      label={label}
      variant={held ? 'secondary' : 'ghost'}
      size="icon-xs"
      data-slot="metric-condition-toggle"
      data-held={held || undefined}
      aria-pressed={open}
      disabled={disabled}
      onClick={onToggle}
    >
      <FunnelIcon />
    </IconButton>
  );
}

/**
 * The sentence a conditioned metric wears at rest — 「只算 状态 属于 已付款」
 * — so the reading of the number is on the card, not behind the funnel.
 *
 * `onName` is there when the condition has no one value to name the metric
 * by (D20 显示名): its name is 「金额的总和 · 有条件」 until the analyst
 * gives it one, and a name that says only "there is a condition" is asked to
 * be replaced right where the condition is said, not left to a menu.
 */
export function ConditionLine({
  items,
  onName,
  disabled,
}: {
  items: FilterSummaryItem[];
  onName?(): void;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  if (items.length === 0) return null;
  const line = (
    <span
      data-slot="metric-condition-line"
      className={cn(
        'text-muted-foreground min-w-0 truncate',
        !onName && 'basis-full',
        TEXT_UI,
      )}
    >
      {onlyWhereText(items, messages, display)}
    </span>
  );
  if (!onName) return line;
  return (
    <div className="flex min-w-0 basis-full items-center gap-2">
      {line}
      <Button
        variant="link"
        size="xs"
        data-slot="metric-name-it"
        disabled={disabled}
        onClick={onName}
      >
        {messages.label('label.analysis.name-it')}
      </Button>
    </div>
  );
}

/**
 * Conditions edited in place under a card — a metric's, or an expansion
 * level's: the same group block the range is built of, over the fields the
 * card may name, writing straight back into the config. A condition
 * belongs to its card, so it grows there rather than in a dialog; a
 * condition left unfinished keeps the query from running and says so, as
 * the range's do.
 */
export function ConditionsBlock({
  name,
  title,
  label,
  tree,
  fields,
  issues,
  at,
  analysis,
  disabled,
  optionsFor,
  onChange,
  onClear,
  onClose,
}: {
  /** What the conditions belong to, for the controls' accessible names. */
  name: string;
  /** The block's heading. */
  title: string;
  /** The block's accessible name. */
  label: string;
  tree: FilterTree;
  fields: readonly FieldDefinition[];
  /** The draft's findings; the ones under `at` address this tree. */
  issues: readonly Issue[];
  at: IssuePath;
  analysis: AnalysisEditorController;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
  onChange(tree: FilterTree): void;
  /** Takes the conditions away altogether. */
  onClear(): void;
  onClose(): void;
}) {
  const messages = useViewMessages();
  // Built on every render, as the nested predicate's is: a controller is a
  // plain object over the values in hand, and every action writes the next
  // tree back.
  const filter = treeController({
    tree,
    fields,
    fieldGroups: analysis.fieldGroups,
    kinds: analysis.kinds,
    issues: issuesAt(issues, at),
    onChange,
    ...(analysis.optionSource ? { optionSource: analysis.optionSource } : {}),
  });
  return (
    <div
      data-slot="card-conditions"
      role="group"
      aria-label={label}
      className={cn('flex basis-full flex-col gap-2', TEXT_UI)}
    >
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground font-semibold">{title}</span>
        <Button
          variant="ghost"
          size="xs"
          className="ml-auto"
          disabled={disabled}
          onClick={onClear}
        >
          {messages.label('label.analysis.condition-remove')}
        </Button>
        <IconButton
          label={messages.label('label.analysis.condition-close')}
          variant="ghost"
          size="icon-xs"
          onClick={onClose}
        >
          <XIcon />
        </IconButton>
      </div>
      <GroupBlock
        filter={filter}
        group={filter.tree}
        path={[]}
        disabled={disabled}
        optionsFor={optionsFor}
        scope={name}
      />
    </div>
  );
}

/** A tree with nothing in it: a group of no children. */
export function isEmptyTree(tree: FilterTree | undefined): boolean {
  return tree === undefined || tree.children.length === 0;
}

/** A metric's own conditions, over the scalar fields of the scope. */
export function ConditionBlock({
  analysis,
  metric,
  index,
  name,
  disabled,
  optionsFor,
  onClose,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  name: string;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
  onClose(): void;
}) {
  const messages = useViewMessages();
  if (metric.type === 'DERIVED') return null;
  return (
    <ConditionsBlock
      name={name}
      title={messages.label('label.analysis.condition-title')}
      label={messages.label('label.analysis.condition-of', { name })}
      tree={metric.filter ?? { op: 'and', children: [] }}
      fields={analysis.conditionFields}
      issues={analysis.issues}
      at={['metrics', index, 'filter']}
      analysis={analysis}
      disabled={disabled}
      optionsFor={optionsFor}
      // The last condition taken out leaves no condition rather than an
      // empty one, which is what opening the block left too: an empty
      // group is only ever written when Apply runs past it (`Tray`).
      onChange={next =>
        analysis.setMetricFilter(index, isEmptyTree(next) ? undefined : next)
      }
      onClear={() => {
        analysis.setMetricFilter(index, undefined);
        onClose();
      }}
      onClose={onClose}
    />
  );
}
