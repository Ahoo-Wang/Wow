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

import type * as React from 'react';
import { Columns3Icon, RefreshCwIcon } from 'lucide-react';
import {
  isFieldlessKind,
  type FieldDefinition,
  type FieldGroupDefinition,
  type RecordLayout,
} from '../model/index.js';
import type {
  RecordBulkActionContext,
  RecordTableController,
} from '../react/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuTrigger,
} from './components/dropdown-menu.js';
import { Spinner } from './components/spinner.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { SEGMENTED } from './layout.js';
import { GroupedMenu } from './FieldMenu.js';
import { DropdownMenuContent } from './popups.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';

export interface ResultToolbarProps {
  table: RecordTableController;
  /** Fields the definition offers, for the column picker. */
  fields: readonly FieldDefinition[];
  /** The picker groups of the definition the fields come from. */
  fieldGroups?: readonly FieldGroupDefinition[];
  /**
   * What the host offers for the rows that are selected. It is a render
   * function rather than a node, because it acts on the selection and the
   * toolbar is what knows the selection.
   */
  bulkActions?(context: RecordBulkActionContext): React.ReactNode;
  /**
   * The runtime behind the controller. The toolbar reads nothing off it; it
   * only hands it to `bulkActions`, whose actions are commands against the
   * view they act in.
   */
  runtime: RecordViewRuntime;
}

/** Wording per layout, so an unhandled one cannot be silently unlabelled. */
const LAYOUT_LABEL: Record<RecordLayout, MessageKey> = {
  table: 'label.layout.table',
  card: 'label.layout.cards',
};

/**
 * The bar above the result: what is selected on the left, how the result is
 * shown on the right.
 *
 * Layout and column changes are edits to the view — they make it dirty and,
 * once saved, come back with it. The selection is not: it lives for one
 * opening, which is why nothing here reaches a saved config. Paging sits
 * below the result in `RecordPagination`, where the rows it pages are.
 */
export function ResultToolbar({
  table,
  fields,
  fieldGroups,
  bulkActions,
  runtime,
}: ResultToolbarProps) {
  const messages = useViewMessages();
  const visible = new Set(table.columnFields);
  const selected = table.selection.length > 0;

  return (
    <div
      data-slot="result-toolbar"
      className="flex flex-wrap items-center gap-2"
    >
      {/* Kept at a button's height whether or not anything is selected, so
          picking the first row does not push the result down a line. */}
      <div className="flex min-h-8 items-center gap-2">
        {selected && (
          <>
            <Badge variant="secondary" role="status">
              {messages.label('label.toolbar.selected', {
                count: table.selection.length,
              })}
            </Badge>
            <Button variant="ghost" size="sm" onClick={table.clearSelection}>
              {messages.label('label.toolbar.clear-selection')}
            </Button>
            {bulkActions?.({
              rows: table.selectedRows,
              keys: table.selection,
              runtime,
              clearSelection: table.clearSelection,
              refresh: table.refresh,
            })}
          </>
        )}
      </div>

      <div className="flex-1" />

      {/* Only the definition's layouts, in its order — and nothing at all
          when there is no choice to make. */}
      {table.layouts.length >= 2 && (
        <ToggleGroup
          value={[table.layout]}
          onValueChange={value => {
            // Matched against the allowed layouts rather than cast: the
            // group is built from them, so anything else is not a layout.
            const next = table.layouts.find(layout => layout === value[0]);
            if (next) table.setLayout(next);
          }}
          variant="outline"
          size="sm"
          aria-label={messages.label('label.toolbar.layout')}
          className={SEGMENTED}
        >
          {table.layouts.map(layout => (
            <ToggleGroupItem key={layout} value={layout}>
              {messages.label(LAYOUT_LABEL[layout])}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      )}

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
    </div>
  );
}
