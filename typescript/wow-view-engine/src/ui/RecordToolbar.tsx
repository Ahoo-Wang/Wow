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

import {
  ChevronLeftIcon,
  ChevronRightIcon,
  Columns3Icon,
  RefreshCwIcon,
} from 'lucide-react';
import {
  isFieldlessKind,
  type FieldDefinition,
  type FieldGroupDefinition,
} from '../model/index.js';
import type { RecordTableController } from '../react/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { GroupedMenu } from './FieldMenu.js';
import { Separator } from './components/separator.js';
import { Spinner } from './components/spinner.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { DropdownMenuContent } from './popups.js';
import { useViewMessages } from './MessagesProvider.js';

export interface RecordToolbarProps {
  table: RecordTableController;
  /** Fields the definition offers, for the column picker. */
  fields: readonly FieldDefinition[];
  /** The picker groups of the definition the fields come from. */
  fieldGroups?: readonly FieldGroupDefinition[];
  children?: React.ReactNode;
}

/**
 * Layout, columns, paging and refresh.
 *
 * Column and layout changes are edits to the view: they make it dirty and,
 * once saved, come back with it. Paging and selection are not, which is why
 * they leave no trace in the saved config.
 */
export function RecordToolbar({
  table,
  fields,
  fieldGroups,
  children,
}: RecordToolbarProps) {
  const messages = useViewMessages();
  const visible = new Set(table.columnFields);

  return (
    <div
      data-slot="record-toolbar"
      className="flex flex-wrap items-center gap-2"
    >
      <ToggleGroup
        value={[table.layout]}
        onValueChange={value => {
          const next = value[0];
          if (next === 'table' || next === 'card') table.setLayout(next);
        }}
        variant="outline"
        size="sm"
        aria-label={messages.label('label.toolbar.layout')}
      >
        <ToggleGroupItem value="table">
          {messages.label('label.layout.table')}
        </ToggleGroupItem>
        <ToggleGroupItem value="card">
          {messages.label('label.layout.cards')}
        </ToggleGroupItem>
      </ToggleGroup>

      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="outline" size="sm" />}>
          <Columns3Icon data-icon="inline-start" />
          {messages.label('label.toolbar.columns')}
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <GroupedMenu
            items={fields.filter(field => !isFieldlessKind(field.kind))}
            groups={fieldGroups ?? []}
            itemKey={field => field.name}
            render={field => (
              <DropdownMenuCheckboxItem
                key={field.name}
                checked={visible.has(field.name)}
                onCheckedChange={() =>
                  table.setColumns(
                    visible.has(field.name)
                      ? table.columnFields.filter(name => name !== field.name)
                      : [...table.columnFields, field.name],
                  )
                }
              >
                {field.label}
              </DropdownMenuCheckboxItem>
            )}
          />
        </DropdownMenuContent>
      </DropdownMenu>

      <Button
        variant="outline"
        size="sm"
        onClick={table.refresh}
        disabled={table.loading}
      >
        {table.loading ? (
          <Spinner data-icon="inline-start" />
        ) : (
          <RefreshCwIcon data-icon="inline-start" />
        )}
        {messages.label('label.toolbar.refresh')}
      </Button>

      {table.selection.length > 0 && (
        <Badge variant="secondary">
          {messages.label('label.toolbar.selected', {
            count: table.selection.length,
          })}
        </Badge>
      )}

      <div className="flex-1" />

      {children}

      <Separator orientation="vertical" className="h-6" />

      <div className="flex items-center gap-1">
        {table.paging?.mode === 'paged' && (
          <span className="text-muted-foreground text-xs">
            {table.paging.total === undefined
              ? messages.label('label.toolbar.page', {
                  index: table.paging.index,
                })
              : messages.label('label.toolbar.page-of', {
                  index: table.paging.index,
                  pages: Math.max(
                    1,
                    Math.ceil(table.paging.total / table.pageSize),
                  ),
                })}
          </span>
        )}
        <Button
          variant="outline"
          size="icon-sm"
          aria-label={messages.label('label.toolbar.previous')}
          disabled={table.paging?.mode !== 'paged' || table.paging.index <= 1}
          onClick={table.previous}
        >
          <ChevronLeftIcon />
        </Button>
        <Button
          variant="outline"
          size="icon-sm"
          aria-label={messages.label('label.toolbar.next')}
          disabled={!table.hasNext}
          onClick={table.next}
        >
          <ChevronRightIcon />
        </Button>
      </div>
    </div>
  );
}
