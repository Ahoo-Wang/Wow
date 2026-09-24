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

import { useState } from 'react';
import { PaletteIcon } from 'lucide-react';
import {
  PANEL_PRESENTATION_MEMBERS,
  sameJson,
  type AnalysisViewConfig,
  type PanelPresentation,
} from '../../model/index.js';
import { isOwnedPanel, presentationMembersOf } from '../../dashboard/index.js';
import {
  useAnalysisEditor,
  useAnalysisResult,
  useViewRuntime,
  type AnalysisEditorController,
  type DashboardPanelView,
} from '../../react/index.js';
import type { DashboardEditing, ViewRuntime } from '../../runtime/index.js';
import {
  ignore,
  useVisualizationFocus,
  visualizationPanel,
  type VisualizationLevel,
} from '../analysis/VisualizationPanel.js';
import { useViewMessages } from '../MessagesProvider.js';
import { Button } from '../components/button.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/dialog.js';
import {
  Empty,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import { DialogContent } from '../popups.js';
import { AnalysisPanel } from './PanelBodies.js';
import type { FinalFocus } from './commands.js';

export interface PresentationDialogProps {
  /** The panel whose look is changed; `null` while the dialog is shut. */
  panel: DashboardPanelView | null;
  /** What the panel is called on the board. */
  name: string;
  /** The board's edits: the look goes through `setPresentation`. */
  editing: Pick<DashboardEditing, 'setPresentation'>;
  onOpenChange(open: boolean): void;
  /**
   * Where the keyboard goes as it closes (`FinalFocus`): the control that
   * asked — a menu's trigger, since the item that was pressed went with the
   * menu. Base UI's own choice when left out.
   */
  finalFocus?: FinalFocus;
}

/**
 * 「改这里的展示」 (D22 D): the visualization panel of the analysis view —
 * the same chart types, the same options, the same totals row — for one
 * panel of a board, beside the panel as it will look.
 *
 * Every choice is the panel's own look at its view (`panel.presentation`,
 * through the board's `setPresentation`) and nothing else: the view it shows
 * keeps its own look, and what it asks does not change. A choice lands on
 * the board at once — the panel behind the dialog and the one beside the
 * options are one child, drawn from its draft over the rows it has, so a
 * change of chart asks the source nothing (D20); only the totals row, which
 * is a query of its own, runs. A look that comes back to the view's own is
 * no override at all, and the panel stops saying 「此处改为…」. Cancel puts
 * back the look the panel had when the dialog opened; nothing is saved
 * until the board is.
 */
export function PresentationDialog({
  panel,
  name,
  editing,
  onOpenChange,
  finalFocus,
}: PresentationDialogProps) {
  return (
    <Dialog open={panel !== null} onOpenChange={onOpenChange}>
      <DialogContent
        {...(finalFocus ? { finalFocus } : {})}
        data-slot="panel-presentation-dialog"
        className="flex max-h-[calc(100dvh-2rem)] flex-col sm:max-w-4xl"
      >
        {panel && (
          <PresentationForm
            key={panel.id}
            panel={panel}
            name={name}
            editing={editing}
            onClose={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function PresentationForm({
  panel,
  name,
  editing,
  onClose,
}: {
  panel: DashboardPanelView;
  name: string;
  editing: Pick<DashboardEditing, 'setPresentation'>;
  onClose(): void;
}) {
  const messages = useViewMessages();
  // What the panel looked like when the dialog opened: Cancel's answer.
  const [initial] = useState(() =>
    panel.panel.kind === 'view' ? (panel.panel.presentation ?? null) : null,
  );
  const runtime =
    panel.runtime?.kind === 'analysis'
      ? (panel.runtime as ViewRuntime<AnalysisViewConfig>)
      : null;
  const cancel = () => {
    editing.setPresentation(panel.id, initial);
    onClose();
  };

  return (
    <>
      <DialogHeader>
        <DialogTitle>
          {messages.label('label.panel.presentation.heading', { title: name })}
        </DialogTitle>
        <DialogDescription>
          {messages.label('label.panel.presentation.description')}
        </DialogDescription>
      </DialogHeader>
      {runtime ? (
        <LookEditor panel={panel} runtime={runtime} editing={editing} />
      ) : (
        <Empty data-slot="panel-presentation-nothing">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <PaletteIcon />
            </EmptyMedia>
            <EmptyTitle>
              {messages.label('label.panel.presentation.nothing')}
            </EmptyTitle>
          </EmptyHeader>
        </Empty>
      )}
      <DialogFooter className="sm:justify-between">
        <Button
          variant="ghost"
          data-slot="panel-presentation-reset"
          disabled={presentationMembersOf(panel.panel).length === 0}
          onClick={() => editing.setPresentation(panel.id, null)}
        >
          {messages.label('label.panel.presentation.reset')}
        </Button>
        <div className="flex gap-2">
          <DialogClose
            render={<Button variant="outline" />}
            onClick={event => {
              event.preventDefault();
              cancel();
            }}
          >
            {messages.label('label.dialog.cancel')}
          </DialogClose>
          <Button data-slot="panel-presentation-done" onClick={onClose}>
            {messages.label('label.panel.presentation.done')}
          </Button>
        </div>
      </DialogFooter>
    </>
  );
}

/**
 * The picker and the options over one panel's child, and the panel beside
 * them. The analysis editor's two presentation commands are the ones the
 * visualization panel calls; here they write the panel's look instead of
 * the view's, and the child is handed it by the board.
 */
function LookEditor({
  panel,
  runtime,
  editing,
}: {
  panel: DashboardPanelView;
  runtime: ViewRuntime<AnalysisViewConfig>;
  editing: Pick<DashboardEditing, 'setPresentation'>;
}) {
  const messages = useViewMessages();
  const state = useViewRuntime(runtime);
  const editor = useAnalysisEditor(runtime);
  // The view's own config, which a look equal to it does not override: the
  // saved view's, or the one the board owns.
  const base: AnalysisViewConfig | null =
    panel.panel.kind === 'view' && isOwnedPanel(panel.panel)
      ? panel.panel.owned.config
      : state?.saved?.config.kind === 'analysis'
        ? state.saved.config
        : null;
  const write = (patch: PanelPresentation) => {
    // Read at the moment of writing, so two writes in one press compose.
    const current = runtime.getSnapshot().draft;
    if (current.kind !== 'analysis') return;
    const next: PanelPresentation = {};
    for (const member of PANEL_PRESENTATION_MEMBERS) {
      const value = member in patch ? patch[member] : current[member];
      if (value !== undefined && !sameJson(value, base?.[member]))
        Object.assign(next, { [member]: value });
    }
    editing.setPresentation(
      panel.id,
      Object.keys(next).length > 0 ? next : null,
    );
  };
  // The editor, with its looks written to the panel.
  const analysis: AnalysisEditorController = {
    ...editor,
    setLayout: layout => write({ layout }),
    updateChart: patch => write({ chart: { ...editor.chart, ...patch } }),
    setTotals: totals => {
      const table = runtime.getSnapshot().draft;
      if (table.kind === 'analysis')
        write({ table: { ...table.table, totals } });
    },
    // A look runs nothing of its own accord; the board runs what must run.
    submit: ignore,
  };
  const result = useAnalysisResult(runtime, analysis, {
    state,
    canDrill: false,
    drill: ignore,
    follow: ignore,
  });
  const [level, setLevel] = useState<VisualizationLevel>('picker');
  // The keyboard follows the level, as in the workbench's panel (A1).
  const focus = useVisualizationFocus(level);

  return (
    <div className="-mx-4 flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 sm:flex-row">
      <section
        data-slot="panel-presentation-preview"
        aria-label={messages.label('label.panel.presentation.preview')}
        className="flex h-80 min-w-0 flex-1 flex-col rounded-lg border p-3"
      >
        <AnalysisPanel runtime={runtime} />
      </section>
      <aside className="flex-none sm:w-72">
        {visualizationPanel({
          // With no question to set options over, the types stay on screen.
          level: level === 'options' && !result.question ? 'picker' : level,
          focus,
          result,
          totals: analysis.totals,
          onTotals: on => analysis.setTotals(on),
          onChange: next => write({ chart: next }),
          onLevel: setLevel,
        })}
      </aside>
    </div>
  );
}
