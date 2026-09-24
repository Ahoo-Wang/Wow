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
  useEffect,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react';
import { DownloadIcon } from 'lucide-react';
import type { FilterSummaryItem } from '../filter/index.js';
import type {
  RecordExportController,
  RecordExportScope,
} from '../react/index.js';
import { Button } from './components/button.js';
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from './components/dialog.js';
import { DialogContent } from './popups.js';
import { IconTooltip } from './IconButton.js';
import { useViewMessages } from './MessagesProvider.js';
import { ToolbarItem } from './toolbar.js';
import type { FinalFocus } from './dashboard/commands.js';
import {
  ChooseStep,
  DoneStep,
  ExportActions,
  phaseOf,
  RunningStep,
  said,
  type ExportPhase,
} from './ExportSteps.js';

/**
 * What a surface hands over to offer an export of its result — the run
 * itself, plus the two things only the surface can say about the file.
 */
export interface ExportOffer {
  /** The two scopes, the one run and what it produced; see `useRecordExport`. */
  control: RecordExportController;
  /**
   * The conditions the rows came back under, as the applied band names
   * them. A host's own scope is in force too and belongs here: the export
   * runs under the applied config with that scope merged in, exactly like
   * the rows on screen.
   */
  conditions: readonly FilterSummaryItem[];
  /**
   * What the file will be called: `<view name>-<yyyy-MM-dd>.csv`.
   *
   * Asked **once, as the window opens**, rather than read off every render.
   * The name carries a day in it, so a window left open across midnight
   * would otherwise promise one name and hand over another — one journey,
   * one shell, one promise (D14).
   */
  nameFile(): string;
  /**
   * What the file holds, in the surface's own unit, where its rows are not
   * records: an analysis's groups and its totals row (D25 Q28). `rows` is
   * the summary's first line and `done` the outcome's. Left out, the window
   * counts records by the scope picked.
   */
  holds?: { rows: string; done: string };
}

export interface ExportWindowProps extends ExportOffer {
  /**
   * The columns the file will hold, in the order the table draws them —
   * the very list the serialiser writes into the header row. Only what each
   * is called is read.
   */
  columns: readonly { label: string }[];
  /** The ceiling one export carries — `limits.exportMax`. */
  max: number;
}

export interface ExportDialogProps extends ExportWindowProps {
  /**
   * Whether the window is open. Whatever offers the export opens it — the
   * toolbar's own button (`ExportButton`), or 「导出数据…」 in a dashboard
   * panel's 「⋯」 — and the window closes itself.
   */
  open: boolean;
  /**
   * Told as the window opens from its own trigger and as it closes itself —
   * Close, Cancel, Escape, the backdrop. While the pages are coming in,
   * closing is stopping: the run is cancelled first.
   */
  onOpenChange(open: boolean): void;
  /**
   * Where the keyboard goes as it closes (`FinalFocus`): the control that
   * opened it — a menu's trigger, since the item that was pressed went with
   * the menu. Base UI's own choice (the trigger) when left out.
   */
  finalFocus?: FinalFocus;
  /** The control that opens it, where the window has one of its own. */
  trigger?: ReactNode;
}

/**
 * The export's own button and its window: one bordered icon button at the
 * end of the toolbar's right-hand block (D12 Ⅳ), because exporting is a
 * display facility like the columns and the sort — it changes nothing about
 * the view and nothing about the records. It opens `ExportDialog`.
 */
export function ExportButton(props: ExportWindowProps) {
  const messages = useViewMessages();
  const [open, setOpen] = useState(false);
  return (
    <ExportDialog
      {...props}
      open={open}
      onOpenChange={setOpen}
      trigger={
        <IconTooltip
          label={messages.label('label.export.title')}
          render={
            // A toolbar item where a toolbar is around it, an ordinary button
            // anywhere else: the bar owns the roving focus order and this is
            // one of the stops in it.
            <ToolbarItem
              render={
                <DialogTrigger
                  data-control="export"
                  render={<Button variant="outline" size="icon-sm" />}
                />
              }
            />
          }
        >
          <DownloadIcon />
        </IconTooltip>
      }
    />
  );
}

/**
 * Taking the result away: what is picked, or everything the applied
 * conditions match — chosen, waited for and read in **one window** (D14).
 *
 * It used to be a menu, and a menu is a surface for choosing rather than a
 * surface for waiting — holding it open with a progress line in it fought
 * its own conventions (Escape and a click outside had to be refused), it was
 * too narrow to say what the file would hold, and the over-limit question
 * already had a dialog of its own: two shells for one journey. A window is
 * allowed to wait, wide enough to say what is being agreed to, and is the
 * one place the cancel lives — so Escape and the backdrop **stop the run**
 * here rather than being ignored.
 *
 * It is controlled, so whatever offers the export opens it: the toolbar's
 * button (`ExportButton`), or a dashboard panel's menu, whose item goes
 * with the menu and so cannot be the window's trigger.
 */
export function ExportDialog({
  open,
  onOpenChange,
  finalFocus,
  trigger,
  ...props
}: ExportDialogProps) {
  const { control } = props;
  const phase = phaseOf(control);
  // The control the phase is about, focused as the phase changes: the button
  // that was focused a moment ago is not in the document any more, and a
  // window whose focus fell back to its own container leaves a keyboard user
  // nowhere they arrived at.
  const primary = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (open) primary.current?.focus();
  }, [open, phase]);
  // Closing forgets what the last run produced, so the next opening asks
  // again rather than reporting an export already read.
  const close = () => {
    control.reset();
    onOpenChange(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={next => {
        // Escape, the backdrop and Cancel are one answer while the pages are
        // coming in: stop. Anywhere else there is nothing to stop.
        if (next) onOpenChange(true);
        else if (phase === 'running') {
          control.cancel();
          onOpenChange(false);
        } else close();
      }}
    >
      {trigger}
      <DialogContent
        data-slot="export-dialog"
        initialFocus={primary}
        {...(finalFocus ? { finalFocus } : {})}
      >
        {/* Mounted afresh on each opening: the scope picked and the file's
            name belong to one journey, and the next opening is another. */}
        <ExportJourney
          key={open ? 'open' : 'closed'}
          {...props}
          phase={phase}
          primary={primary}
          onClose={close}
        />
      </DialogContent>
    </Dialog>
  );
}

/** One opening of the window: its scope, its file's name and its steps. */
function ExportJourney({
  phase,
  primary,
  onClose,
  ...props
}: ExportWindowProps & {
  phase: ExportPhase;
  primary: RefObject<HTMLButtonElement | null>;
  onClose(): void;
}) {
  const messages = useViewMessages();
  const { control } = props;
  // Which scope was picked, and `null` for "not picked yet" — the default
  // depends on whether anything is selected, and that can change under an
  // open window as well as between two openings.
  const [scope, setScope] = useState<RecordExportScope | null>(null);
  const picked =
    control.scopes.selected === undefined ? 'all' : (scope ?? 'selected');
  // The name the file will carry, fixed as this opening begins and used by
  // every step of the one journey — the line that promises it, the line
  // that reports it, and the run that hands the file over (D14). Asked once,
  // as the journey mounts, and never on a later render.
  const [named] = useState(() => props.nameFile());
  return (
    <>
      <DialogHeader>
        <DialogTitle>{messages.label('label.export.title')}</DialogTitle>
        <DialogDescription>
          {said(phase, control, messages, props.holds)}
        </DialogDescription>
      </DialogHeader>
      {phase === 'choose' && (
        <ChooseStep
          {...props}
          fileName={named}
          scope={picked}
          onScope={setScope}
        />
      )}
      {phase === 'running' && <RunningStep control={control} />}
      {phase === 'done' && (
        <DoneStep control={control} fileName={named} max={props.max} />
      )}
      <ExportActions
        phase={phase}
        control={control}
        scope={picked}
        fileName={named}
        primary={primary}
        onClose={onClose}
      />
    </>
  );
}
