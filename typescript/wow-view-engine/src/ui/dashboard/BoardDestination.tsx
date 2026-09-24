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

import { useEffect, useState } from 'react';
import {
  boardFilterChoices,
  boardValueChoices,
  filtersOf,
  type PressableGroup,
} from '../../dashboard/index.js';
import type {
  BoardValueSource,
  DashboardField,
  FieldDefinition,
} from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
import type { DestinationBoard } from '../../runtime/index.js';
import { AlertTitle } from '../components/alert.js';
import { Button } from '../components/button.js';
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from '../components/field.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { Skeleton } from '../components/skeleton.js';
import { LineAlert } from '../alerts.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SelectContent } from '../popups.js';

/** The board a click opens, as 「点击时…」 has read it. */
export type BoardRead =
  | { status: 'none' }
  | { status: 'loading' }
  | { status: 'unreadable' }
  | { status: 'ready'; board: DestinationBoard };

/**
 * The board `instanceId` names, read when 「点击时…」 opens on it or the
 * author picks it (D23 Q17) — never when the board holding the panel opens.
 * The read goes through the board's references, so it also re-judges the
 * click against it.
 */
export function useDestinationBoard(
  dashboard: DashboardController,
  instanceId: string,
): BoardRead {
  const [read, setRead] = useState<{
    id: string;
    board: DestinationBoard | null;
  } | null>(null);
  const { destinationBoard } = dashboard;
  useEffect(() => {
    if (instanceId === '') return;
    let live = true;
    void destinationBoard(instanceId).then(board => {
      if (live) setRead({ id: instanceId, board });
    });
    return () => {
      live = false;
    };
  }, [destinationBoard, instanceId]);
  if (instanceId === '') return { status: 'none' };
  if (read?.id !== instanceId) return { status: 'loading' };
  return read.board
    ? { status: 'ready', board: read.board }
    : { status: 'unreadable' };
}

/**
 * What a press takes to the board (D23 Q17), from what the author chose:
 * each of the board's filters mapped to a source that can still give it a
 * value — a dimension of this panel, or a filter of this board's, of a type
 * it takes. Whatever else was stored (a filter gone on either board, a
 * dimension gone) is left out. Never guessed by name: a filter the author
 * did not map is not carried.
 */
export function mappedValues(
  board: DestinationBoard,
  values: Readonly<Record<string, BoardValueSource>>,
  sources: MappingSources,
): { kept: Record<string, BoardValueSource>; stale: string[] } {
  const byName = new Map(
    filtersOf(board.config).map(filter => [filter.name, filter]),
  );
  const kept: Record<string, BoardValueSource> = {};
  const stale: string[] = [];
  for (const [name, source] of Object.entries(values)) {
    const filter = byName.get(name);
    const usable =
      filter &&
      choicesFor(filter, sources).some(
        choice => sourceKey(choice) === sourceKey(source),
      );
    if (usable) kept[name] = source;
    else stale.push(filter?.label ?? name);
  }
  return { kept, stale };
}

/** What a mapping can draw on: the panel's dimensions and this board's filters. */
export interface MappingSources {
  groups: readonly PressableGroup[];
  fields: readonly FieldDefinition[] | null;
  own: readonly DashboardField[];
}

/** Every source one of the board's filters can take, dimensions first. */
function choicesFor(
  filter: DashboardField,
  { groups, fields, own }: MappingSources,
): BoardValueSource[] {
  return [
    ...boardValueChoices(filter, groups, fields).map(group => ({
      dimension: group.field,
    })),
    ...boardFilterChoices(filter, own).map(field => ({ filter: field.name })),
  ];
}

/** One source as a select's value; `''` is 「不带」. */
function sourceKey(source: BoardValueSource): string {
  return 'dimension' in source
    ? `dimension:${source.dimension}`
    : `filter:${source.filter}`;
}

function sourceOfKey(key: string): BoardValueSource | null {
  const at = key.indexOf(':');
  if (at < 0) return null;
  const name = key.slice(at + 1);
  return key.startsWith('dimension:') ? { dimension: name } : { filter: name };
}

/**
 * 「另一块仪表盘」 under 「去另一个视图、仪表盘或页面」 (D23 Q17): the board
 * picked, and its filters one per row, each taking 「不带」, 「这一组的〈维度〉」
 * — a dimension of this panel whose field fits the filter — or 「这块板的
 * 〈筛选〉」 — a filter of this board's of the same type. A mapping that no
 * longer holds is said once above the rows and dropped on 完成. Each row is
 * a labelled select, so the keyboard and a screen reader reach every one of
 * them in order.
 */
export function BoardDestination({
  panel,
  groups,
  own,
  read,
  values,
  onValues,
  missing,
  onPick,
  ids,
}: {
  panel: DashboardPanelView;
  groups: readonly PressableGroup[];
  /** This board's filters, a source a mapping may take. */
  own: readonly DashboardField[];
  read: BoardRead;
  values: Readonly<Record<string, BoardValueSource>>;
  onValues(values: Record<string, BoardValueSource>): void;
  /** Whether 完成 was pressed without a board to open. */
  missing: boolean;
  onPick(): void;
  ids: string;
}) {
  const messages = useViewMessages();
  const fields = panel.runtime?.fields ?? null;
  const picked = read.status !== 'none';
  return (
    <FieldGroup className="gap-3" data-slot="click-board">
      <Field data-invalid={missing || undefined}>
        {read.status === 'ready' && (
          <span data-slot="click-board-title" className="text-sm">
            {read.board.title}
          </span>
        )}
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="self-start"
          aria-invalid={missing || undefined}
          onClick={onPick}
        >
          {messages.label(
            picked ? 'label.click.board-change' : 'label.click.board-pick',
          )}
        </Button>
        {missing && (
          <FieldError>{messages.label('label.click.board-missing')}</FieldError>
        )}
      </Field>
      {read.status === 'loading' && (
        <div
          role="status"
          aria-label={messages.label('label.click.board-loading')}
          className="flex flex-col gap-2"
        >
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      )}
      {read.status === 'unreadable' && (
        <LineAlert tone="error" frame="bare" data-slot="click-board-unreadable">
          <AlertTitle>
            {messages.label('label.click.board-unreadable')}
          </AlertTitle>
        </LineAlert>
      )}
      {read.status === 'ready' && (
        <BoardFilters
          board={read.board}
          sources={{ groups, fields, own }}
          values={values}
          onValues={onValues}
          ids={ids}
        />
      )}
    </FieldGroup>
  );
}

function BoardFilters({
  board,
  sources,
  values,
  onValues,
  ids,
}: {
  board: DestinationBoard;
  sources: MappingSources;
  values: Readonly<Record<string, BoardValueSource>>;
  onValues(values: Record<string, BoardValueSource>): void;
  ids: string;
}) {
  const messages = useViewMessages();
  const filters = filtersOf(board.config);
  const { kept, stale } = mappedValues(board, values, sources);
  if (filters.length === 0)
    return (
      <p className="text-muted-foreground text-sm" data-slot="click-board-none">
        {messages.label('label.click.board-no-filters')}
      </p>
    );
  const said = (source: BoardValueSource) =>
    'dimension' in source
      ? messages.label('label.click.board-value', {
          dimension:
            sources.fields?.find(entry => entry.name === source.dimension)
              ?.label ?? source.dimension,
        })
      : messages.label('label.click.board-filter-value', {
          filter:
            sources.own.find(entry => entry.name === source.filter)?.label ??
            source.filter,
        });
  return (
    <FieldSet data-slot="click-board-values">
      <FieldLegend variant="label">
        {messages.label('label.click.board-values')}
      </FieldLegend>
      <FieldDescription>
        {messages.label('label.click.board-values-hint')}
      </FieldDescription>
      {stale.length > 0 && (
        <LineAlert tone="warning" frame="bare" data-slot="click-board-stale">
          <AlertTitle>
            {messages.label('label.click.board-stale', {
              filters: stale
                .map(name =>
                  messages.label('label.click.board-stale-name', {
                    filter: name,
                  }),
                )
                .join(messages.label('label.filter.join')),
            })}
          </AlertTitle>
        </LineAlert>
      )}
      <FieldGroup className="gap-2">
        {filters.map(filter => {
          const kept1 = kept[filter.name];
          return (
            <BoardFilterRow
              key={filter.name}
              id={`${ids}-board-${filter.name}`}
              filter={filter}
              choices={choicesFor(filter, sources)}
              value={kept1 ? sourceKey(kept1) : ''}
              said={said}
              onChange={key => {
                const next = { ...kept };
                const source = sourceOfKey(key);
                if (source) next[filter.name] = source;
                else delete next[filter.name];
                onValues(next);
              }}
            />
          );
        })}
      </FieldGroup>
    </FieldSet>
  );
}

/**
 * One of the board's filters: its name, and what a press gives it — one
 * select of three parts. 「不带」 comes first, alone: it is where every row
 * starts and the way back, so it stays at the top however many sources
 * follow. Then the group pressed, then this board's filters, each under its
 * own heading: what the press is about before what it happens under — the
 * group is why the author set a click at all, the board's filters the
 * context carried along.
 */
function BoardFilterRow({
  id,
  filter,
  choices,
  value,
  said,
  onChange,
}: {
  id: string;
  filter: DashboardField;
  choices: readonly BoardValueSource[];
  value: string;
  said(source: BoardValueSource): string;
  onChange(key: string): void;
}) {
  const messages = useViewMessages();
  const skip = { value: '', label: messages.label('label.click.board-skip') };
  const dimensions = choices.filter(choice => 'dimension' in choice);
  const filters = choices.filter(choice => 'filter' in choice);
  const items = [
    skip,
    ...choices.map(choice => ({
      value: sourceKey(choice),
      label: said(choice),
    })),
  ];
  const part = (heading: string, part: readonly BoardValueSource[]) =>
    part.length > 0 && (
      <SelectGroup>
        <SelectLabel>{heading}</SelectLabel>
        {part.map(choice => (
          <SelectItem key={sourceKey(choice)} value={sourceKey(choice)}>
            {said(choice)}
          </SelectItem>
        ))}
      </SelectGroup>
    );
  return (
    <Field
      orientation="horizontal"
      data-slot="click-board-filter"
      data-filter={filter.name}
    >
      {/* No `data-disabled` on the row: it fades the name below contrast,
          and the name is still worth reading — only the select is off. */}
      <FieldContent className="min-w-0">
        <FieldLabel id={id}>{filter.label}</FieldLabel>
        {choices.length === 0 && (
          <FieldDescription>
            {messages.label('label.click.board-no-source')}
          </FieldDescription>
        )}
      </FieldContent>
      <Select
        items={items}
        value={value}
        disabled={choices.length === 0}
        onValueChange={next => {
          if (typeof next === 'string') onChange(next);
        }}
      >
        <SelectTrigger aria-label={filter.label} size="sm">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectItem value="">{skip.label}</SelectItem>
          </SelectGroup>
          {part(messages.label('label.click.board-from-group'), dimensions)}
          {part(messages.label('label.click.board-from-board'), filters)}
        </SelectContent>
      </Select>
    </Field>
  );
}
