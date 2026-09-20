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
import type {
  FieldDefinition,
  FieldGroupDefinition,
  RecordLayout,
} from '../model/index.js';
import type {
  RecordBulkActionContext,
  RecordTableController,
  RefreshController,
} from '../react/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import { ButtonGroup } from './components/button-group.js';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { ColumnSettings } from './ColumnSettings.js';
import { RefreshControl } from './RefreshControl.js';
import { SortSettings } from './SortSettings.js';
import { SEGMENTED, SPACE } from './layout.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';

export interface ResultToolbarProps {
  table: RecordTableController;
  /**
   * How this view is renewed: the one-shot refresh, and the interval it
   * keeps itself up to date by. It comes from the workbench rather than off
   * the table controller because `refresh.interval` belongs to every kind of
   * view (`ViewConfigBase`), not to a table.
   */
  refresh: RefreshController;
  /** Fields the definition offers, for the column picker. */
  fields: readonly FieldDefinition[];
  /** The picker groups of the definition the fields come from. */
  fieldGroups?: readonly FieldGroupDefinition[];
  /**
   * The field holding each row's identity. The column settings hold it on
   * the left, where the table shows it, and let nothing past it.
   */
  rowKey?: string;
  /**
   * Whether the table carries the host's action column, so the settings can
   * show where it sits — pinned right and not the user's to move.
   */
  hasRowActions?: boolean;
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
 *
 * The right is three groups by responsibility, 8px apart and seamless
 * inside: the layout switch, then how the table shows what it has, then how
 * fresh it is. Every control here is `ghost` — the toolbar sits above the
 * result and must not compete with it — except the layout switch, which
 * wears one outline because that outline is what makes it read as one
 * control with two positions rather than two buttons. The host's bulk
 * actions are the only `outline` in the row, and the one primary button on
 * screen stays the filter's Apply.
 */
export function ResultToolbar({
  table,
  refresh,
  fields,
  fieldGroups,
  rowKey,
  hasRowActions = false,
  bulkActions,
  runtime,
}: ResultToolbarProps) {
  const messages = useViewMessages();
  const selected = table.selection.length > 0;

  return (
    <div
      data-slot="result-toolbar"
      className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
    >
      {/* Kept at a button's height whether or not anything is selected, so
          picking the first row does not push the result down a line. */}
      <div className={`flex min-h-8 items-center ${SPACE.GROUPS}`}>
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
          when there is no choice to make, unless the view is saved in a
          layout the definition has since dropped: `validateRecord` refuses
          that config, and a switcher that hides itself exactly then leaves
          the user reading an error with no way to answer it. Nothing is
          pressed in that state, which is the truth — the layout in force is
          not one of these. `SEGMENTED` is what makes it one
          control with two positions rather than two bordered buttons that
          happen to sit together; it is the house rule's one spelling of
          that, applied here because `ui/components` is upstream's. */}
      {(table.layouts.length >= 2 || !table.layouts.includes(table.layout)) && (
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

      {/* How the table shows what it has: one responsibility, one group. */}
      <ButtonGroup aria-label={messages.label('label.toolbar.arrange')}>
        <ColumnSettings
          table={table}
          fields={fields}
          {...(rowKey === undefined ? {} : { rowKey })}
          actions={hasRowActions}
        />
        <SortSettings
          table={table}
          fields={fields}
          {...(fieldGroups ? { fieldGroups } : {})}
        />
      </ButtonGroup>

      {/* Freshness: the press that refreshes now, and the interval that
          keeps doing it. One group, because they are one question. */}
      <RefreshControl refresh={refresh} busy={table.loading} />
    </div>
  );
}
