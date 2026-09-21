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
} from '../react/index.js';
import type { RecordViewRuntime } from '../runtime/index.js';
import { Badge } from './components/badge.js';
import { Button } from './components/button.js';
import { ButtonGroup } from './components/button-group.js';
import { LayoutGridIcon, Rows3Icon } from 'lucide-react';
import { ToggleGroup, ToggleGroupItem } from './components/toggle-group.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { TooltipContent } from './popups.js';
import { ColumnSettings } from './ColumnSettings.js';
import type { ReleasedPins } from './record/pinCap.js';
import { ExportDialog, type ExportOffer } from './ExportDialog.js';
import { SortSettings } from './SortSettings.js';
import { SPACE, TEXT_UI } from './layout.js';
import { Toolbar, ToolbarItem } from './toolbar.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';
import { cn } from 'cn';

export interface ResultToolbarProps {
  table: RecordTableController;
  /**
   * How this view is renewed: the one-shot refresh, and the interval it
   * keeps itself up to date by. It comes from the workbench rather than off
   * the table controller because `refresh.interval` belongs to every kind of
   * view (`ViewConfigBase`), not to a table.
   */
  /** Fields the definition offers, for the column picker. */
  fields: readonly FieldDefinition[];
  /** The picker groups of the definition the fields come from. */
  fieldGroups?: readonly FieldGroupDefinition[];
  /**
   * The field holding each row's identity. The column settings hold it on
   * the left, where the table shows it, and let nothing past it.
   */
  rowKey?: string;
  /** Pins the table's cap is not drawing right now (D17-4). */
  released?: ReleasedPins;
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
   * Taking the result away, when the surface offers it: the controller plus
   * the two things only the workbench can say — the conditions the rows came
   * back under, and what the file will be called. They are here because the
   * export window says what the file will hold **before** it is made (D14),
   * and neither of those is readable off a table controller.
   *
   * An embedded view that offers no export simply passes none, and the
   * button is not there.
   */
  exporter?: ExportOffer;
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

const LAYOUT_ICON: Record<RecordLayout, typeof Rows3Icon> = {
  table: Rows3Icon,
  card: LayoutGridIcon,
};

/**
 * The bar above the result: what is selected on the left, how the result is
 * shown on the right.
 *
 * **It is a `Toolbar`, not a row of buttons that looks like one.** Base UI's
 * primitive (`ui/toolbar.tsx`; the registry carries no `toolbar`) makes the
 * whole bar one tab stop with the arrows moving inside it, and writes the
 * `role` and `aria-orientation` that used to be missing. On a wide record
 * view this was eight or nine stops between the rows and everything above
 * them. The layout switch needs nothing said about it: Base UI's own
 * `ToggleGroup` is toolbar-aware — inside one it draws a plain `role=group`
 * and its segments register with the bar's roving order rather than opening
 * a second one. The two grouped functions keep `ButtonGroup`, which is what
 * draws their shared seam; `Toolbar.Group`'s only behaviour beyond the
 * `role="group"` both give is disabling a whole group at once, which nothing
 * here does.
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
 *
 * **The three right-hand groups wrap together, as one block.** They used to
 * be siblings of a `flex-1` spacer, and a spacer is the worst thing to wrap
 * around: it took a full line of its own width, pushed the layout switch to
 * the far right of the first line by itself, dropped the arrange group to
 * the left of the second and the refresh split button to a third — three
 * rows of 104px at a phone's width, and still three at 768px. Sitting in one
 * `ml-auto ... justify-end` box, they stay a block that ends where the bar
 * ends, and the selection keeps the left. 768px is one line now; 375px with
 * a selection is still three, because 406px of controls does not go into a
 * 317px bar however it wraps — but they are three grouped lines rather than
 * three scattered ones.
 */
export function ResultToolbar({
  table,
  fields,
  fieldGroups,
  rowKey,
  released,
  hasRowActions = false,
  bulkActions,
  exporter,
  runtime,
}: ResultToolbarProps) {
  const messages = useViewMessages();
  const selected = table.selection.length > 0;

  return (
    <Toolbar
      data-slot="result-toolbar"
      aria-label={messages.label('label.toolbar.title')}
      className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
    >
      {/* Nothing at all when nothing is selected: the empty box that used to
          stand here held a button's height so that picking the first row did
          not shove the result down a line, but the groups on the right are
          buttons too and hold the same 32px whatever the selection is — so
          it was 32px of nothing, and at a phone's width it was 32px of
          nothing that could take a line to itself. Its own contents wrap:
          a count, a way to drop it, and however many bulk actions the host
          brought are more than one narrow line holds. */}
      {/* Nothing selected but something to select for: the sentence that
          says how the bulk actions are reached, in the place they will
          appear. A host that brought no bulk action has nothing to explain,
          and the left of the bar stays empty (D12 Ⅳ). */}
      {!selected && bulkActions && (
        <span
          data-slot="toolbar-hint"
          className={cn('text-muted-foreground', TEXT_UI)}
        >
          {messages.label('label.toolbar.hint')}
        </span>
      )}

      {selected && (
        <div
          data-slot="toolbar-selection"
          className={`flex flex-wrap items-center ${SPACE.GROUPS}`}
        >
          <Badge variant="secondary" role="status">
            {messages.label('label.toolbar.selected', {
              count: table.selection.length,
            })}
          </Badge>
          <ToolbarItem
            render={
              <Button
                variant="ghost"
                size="sm"
                onClick={table.clearSelection}
              />
            }
          >
            {messages.label('label.toolbar.clear-selection')}
          </ToolbarItem>
          {/* The host's own controls stay as they came: a bulk slot holds
              arbitrary nodes, and an item can only be made of an element
              this file renders. They are ordinary tab stops between the two
              ends of the bar, which is the honest reading — the toolbar
              does not own them. */}
          {bulkActions?.({
            rows: table.selectedRows,
            keys: table.selection,
            runtime,
            clearSelection: table.clearSelection,
            refresh: table.refresh,
          })}
        </div>
      )}

      {/* How the result is shown: one block of three groups, ending where
          the bar ends whether it took one line or two. */}
      <div
        data-slot="toolbar-arrangement"
        className={`ml-auto flex flex-wrap items-center justify-end ${SPACE.GROUPS}`}
      >
        {/* Only the definition's layouts, in its order — and nothing at all
          when there is no choice to make, unless the view is saved in a
          layout the definition has since dropped: `validateRecord` refuses
          that config, and a switcher that hides itself exactly then leaves
          the user reading an error with no way to answer it. Nothing is
          pressed in that state, which is the truth — the layout in force is
          not one of these. `spacing={0}` is what makes it one control with
          two positions rather than two bordered buttons that happen to sit
          together: the registry's own joined group — no gap, square inner
          corners, one shared seam — asked for by the prop it is on. */}
        {(table.layouts.length >= 2 ||
          !table.layouts.includes(table.layout)) && (
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
            spacing={0}
            aria-label={messages.label('label.toolbar.layout')}
          >
            {table.layouts.map(layout => {
              // An icon with the word in its name and its tooltip (D12): the
              // switch reports which layout is on by which segment is
              // pressed, so the word adds nothing a glance does not have.
              const Icon = LAYOUT_ICON[layout];
              return (
                <Tooltip key={layout}>
                  <TooltipTrigger
                    render={
                      <ToggleGroupItem
                        value={layout}
                        aria-label={messages.label(LAYOUT_LABEL[layout])}
                      />
                    }
                  >
                    <Icon />
                  </TooltipTrigger>
                  <TooltipContent>
                    {messages.label(LAYOUT_LABEL[layout])}
                  </TooltipContent>
                </Tooltip>
              );
            })}
          </ToggleGroup>
        )}

        {/* How the table shows what it has: one responsibility, one group. */}
        <ButtonGroup aria-label={messages.label('label.toolbar.arrange')}>
          <ColumnSettings
            table={table}
            fields={fields}
            {...(rowKey === undefined ? {} : { rowKey })}
            {...(released ? { released } : {})}
            actions={hasRowActions}
          />
          <SortSettings
            table={table}
            fields={fields}
            {...(fieldGroups ? { fieldGroups } : {})}
          />
        </ButtonGroup>

        {/* Taking the rows away is its own responsibility, so it is its own
            group at the end of the block (D12 Ⅳ): the two above change how
            the result is drawn, this one changes nothing at all. */}
        {exporter && (
          <ExportDialog
            {...exporter}
            columns={table.columns}
            max={runtime.limits.exportMax}
          />
        )}
      </div>
    </Toolbar>
  );
}
