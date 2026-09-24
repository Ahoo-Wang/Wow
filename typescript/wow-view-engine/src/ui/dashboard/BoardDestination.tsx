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
  boardValueChoices,
  filtersOf,
  type PressableGroup,
} from '../../dashboard/index.js';
import type { DashboardField, FieldDefinition } from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
import type { DestinationBoard } from '../../runtime/index.js';
import { CompactSelect } from '../analysis/CompactSelect.js';
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
import { Skeleton } from '../components/skeleton.js';
import { LineAlert } from '../alerts.js';
import { useViewMessages } from '../MessagesProvider.js';

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
 * each of the board's filters mapped to a dimension that can still give it
 * a value — whatever else was stored (a filter gone, a dimension gone) is
 * left out. Never guessed by name: a filter the author did not map is not
 * carried.
 */
export function mappedValues(
  board: DestinationBoard,
  values: Readonly<Record<string, string>>,
  groups: readonly PressableGroup[],
  fields: readonly FieldDefinition[] | null,
): { kept: Record<string, string>; stale: string[] } {
  const byName = new Map(
    filtersOf(board.config).map(filter => [filter.name, filter]),
  );
  const kept: Record<string, string> = {};
  const stale: string[] = [];
  for (const [name, field] of Object.entries(values)) {
    const filter = byName.get(name);
    const usable =
      filter &&
      boardValueChoices(filter, groups, fields).some(
        group => group.field === field,
      );
    if (usable) kept[name] = field;
    else stale.push(filter?.label ?? name);
  }
  return { kept, stale };
}

/**
 * 「另一块仪表盘」 under 「去另一个视图、仪表盘或页面」 (D23 Q17): the board
 * picked, and its filters one per row, each taking 「这一组的〈维度〉」 — a
 * dimension of this panel whose field fits the filter — or 「不带」. A
 * mapping that no longer holds is said once above the rows and dropped on
 * 完成. Each row is a labelled select, so the keyboard and a screen reader
 * reach every one of them in order.
 */
export function BoardDestination({
  panel,
  groups,
  read,
  values,
  onValues,
  missing,
  onPick,
  ids,
}: {
  panel: DashboardPanelView;
  groups: readonly PressableGroup[];
  read: BoardRead;
  values: Readonly<Record<string, string>>;
  onValues(values: Record<string, string>): void;
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
          groups={groups}
          fields={fields}
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
  groups,
  fields,
  values,
  onValues,
  ids,
}: {
  board: DestinationBoard;
  groups: readonly PressableGroup[];
  fields: readonly FieldDefinition[] | null;
  values: Readonly<Record<string, string>>;
  onValues(values: Record<string, string>): void;
  ids: string;
}) {
  const messages = useViewMessages();
  const filters = filtersOf(board.config);
  const { kept, stale } = mappedValues(board, values, groups, fields);
  if (filters.length === 0)
    return (
      <p className="text-muted-foreground text-sm" data-slot="click-board-none">
        {messages.label('label.click.board-no-filters')}
      </p>
    );
  const dimension = (field: string) =>
    messages.label('label.click.board-value', {
      dimension: fields?.find(entry => entry.name === field)?.label ?? field,
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
        {filters.map(filter => (
          <BoardFilterRow
            key={filter.name}
            id={`${ids}-board-${filter.name}`}
            filter={filter}
            choices={boardValueChoices(filter, groups, fields)}
            value={kept[filter.name] ?? ''}
            dimension={dimension}
            onChange={field => {
              const next = { ...kept };
              if (field === '') delete next[filter.name];
              else next[filter.name] = field;
              onValues(next);
            }}
          />
        ))}
      </FieldGroup>
    </FieldSet>
  );
}

/** One of the board's filters: its name, and what a press gives it. */
function BoardFilterRow({
  id,
  filter,
  choices,
  value,
  dimension,
  onChange,
}: {
  id: string;
  filter: DashboardField;
  choices: readonly PressableGroup[];
  value: string;
  dimension(field: string): string;
  onChange(field: string): void;
}) {
  const messages = useViewMessages();
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
            {messages.label('label.click.board-no-dimension')}
          </FieldDescription>
        )}
      </FieldContent>
      <CompactSelect
        label={filter.label}
        items={[
          { value: '', label: messages.label('label.click.board-skip') },
          ...choices.map(group => ({
            value: group.field,
            label: dimension(group.field),
          })),
        ]}
        value={value}
        disabled={choices.length === 0}
        onChange={onChange}
      />
    </Field>
  );
}
