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

import { useEffect, useId, useRef, useState, type RefObject } from 'react';
import { DownloadIcon } from 'lucide-react';
import type { FilterSummaryItem } from '../filter/index.js';
import type { RecordColumnView } from '../record/index.js';
import type {
  RecordExportController,
  RecordExportScope,
} from '../react/index.js';
import { Button } from './components/button.js';
import {
  Dialog,
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from './components/dialog.js';
import {
  Field,
  FieldContent,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from './components/field.js';
import { Progress } from './components/progress.js';
import { RadioGroup, RadioGroupItem } from './components/radio-group.js';
import { DialogContent } from './popups.js';
import { summaryText } from './display.js';
import { IconTooltip } from './IconButton.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { useSurfaceDisplay } from './ViewSurface.js';

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
  /** What the file will be called: `<view name>-<yyyy-MM-dd>.csv`. */
  fileName: string;
}

export interface ExportDialogProps extends ExportOffer {
  /**
   * The columns the file will hold, in the order the table draws them —
   * the very list the serialiser writes into the header row.
   */
  columns: readonly RecordColumnView[];
  /** The ceiling one export carries — `limits.exportMax`. */
  max: number;
}

/** Which of the four things the one window is saying at this moment. */
type ExportPhase = 'choose' | 'running' | 'done' | 'failed';

/**
 * Taking the result away: what is picked, or everything the applied
 * conditions match.
 *
 * One bordered icon button at the end of the toolbar's right-hand block
 * (D12 Ⅳ), because exporting is a display facility like the columns and the
 * sort — it changes nothing about the view and nothing about the records.
 * The button opens **one window**, and the whole journey happens in it
 * (D14): choose, wait, and read how it ended.
 *
 * It used to be a menu, and a menu is a surface for choosing rather than a
 * surface for waiting — holding it open with a progress line in it fought
 * its own conventions (Escape and a click outside had to be refused), it was
 * too narrow to say what the file would hold, and the over-limit question
 * already had a dialog of its own: two shells for one journey. A window is
 * allowed to wait, wide enough to say what is being agreed to, and is the
 * one place the cancel lives — so Escape and the backdrop **stop the run**
 * here rather than being ignored.
 */
export function ExportDialog(props: ExportDialogProps) {
  const messages = useViewMessages();
  const { control } = props;
  const [open, setOpen] = useState(false);
  // Which scope was picked, and `null` for "not picked yet" — the default
  // depends on whether anything is selected, and that can change under an
  // open window as well as between two openings.
  const [scope, setScope] = useState<RecordExportScope | null>(null);
  const picked =
    control.scopes.selected === undefined ? 'all' : (scope ?? 'selected');
  const phase = phaseOf(control);
  // The control the phase is about, focused as the phase changes: the button
  // that was focused a moment ago is not in the document any more, and a
  // window whose focus fell back to its own container leaves a keyboard user
  // nowhere they arrived at.
  const primary = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (open) primary.current?.focus();
  }, [open, phase]);

  const close = () => {
    control.reset();
    setScope(null);
    setOpen(false);
  };

  return (
    <Dialog
      open={open}
      onOpenChange={next => {
        // Escape, the backdrop and Cancel are one answer while the pages are
        // coming in: stop. Anywhere else there is nothing to stop, and
        // closing forgets what the last run produced, so the next opening
        // asks again rather than reporting an export already read.
        if (next) setOpen(true);
        else if (phase === 'running') {
          control.cancel();
          setOpen(false);
        } else close();
      }}
    >
      <IconTooltip
        label={messages.label('label.export.title')}
        render={
          <DialogTrigger
            data-control="export"
            render={<Button variant="outline" size="icon-sm" />}
          />
        }
      >
        <DownloadIcon />
      </IconTooltip>

      <DialogContent data-slot="export-dialog" initialFocus={primary}>
        <DialogHeader>
          <DialogTitle>{messages.label('label.export.title')}</DialogTitle>
          <DialogDescription>
            {said(phase, control, messages)}
          </DialogDescription>
        </DialogHeader>
        {phase === 'choose' && (
          <ChooseStep {...props} scope={picked} onScope={setScope} />
        )}
        {phase === 'running' && <RunningStep control={control} />}
        {phase === 'done' && (
          <DoneStep
            control={control}
            fileName={props.fileName}
            max={props.max}
          />
        )}
        <ExportActions
          phase={phase}
          control={control}
          scope={picked}
          primary={primary}
          onClose={close}
        />
      </DialogContent>
    </Dialog>
  );
}

/** What the window is saying, read off the controller rather than stored. */
function phaseOf(control: RecordExportController): ExportPhase {
  if (control.error) return 'failed';
  if (control.outcome) return 'done';
  // Only the whole-result export takes time. The picked rows are already in
  // hand, so that scope goes from the button straight to the outcome rather
  // than flashing a progress bar for one frame.
  return control.running === 'all' ? 'running' : 'choose';
}

/** The window's one-line description, which is a different line per phase. */
function said(
  phase: ExportPhase,
  control: RecordExportController,
  messages: MessageFormatters,
): string {
  if (phase === 'failed' && control.error) return messages.issue(control.error);
  if (phase === 'running') return messages.label('label.export.running');
  if (phase === 'done')
    return messages.label('label.export.done', {
      count: control.outcome?.rows ?? 0,
    });
  return messages.label('label.export.description');
}

/** Step one: which rows, and what the file that holds them will look like. */
function ChooseStep({
  control,
  columns,
  conditions,
  fileName,
  max,
  scope,
  onScope,
}: ExportDialogProps & {
  scope: RecordExportScope;
  onScope(scope: RecordExportScope): void;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const fieldId = useId();
  const { scopes } = control;
  const count = scope === 'selected' ? (scopes.selected ?? 0) : scopes.all;
  const overLimit = scope === 'all' && scopes.all !== null && scopes.all > max;

  return (
    <>
      {/* No radio at all with nothing selected: one choice is not a choice,
          and the count below still says what the file will hold (D4). */}
      {scopes.selected !== undefined && (
        /* The question the two radios answer is on screen rather than only
           in an `aria-label`: a group named by an attribute is named for
           screen readers alone, and everyone else reads two options with
           nothing above them saying what is being chosen. `FieldSet` +
           `FieldLegend` is the shadcn shape for a set of radios, and it is
           what `SaveAsDialog` says with `FieldTitle` one dialog over. */
        <FieldSet>
          <FieldLegend variant="label">
            {messages.label('label.export.scope')}
          </FieldLegend>
          <RadioGroup
            data-slot="export-scope"
            value={scope}
            onValueChange={value =>
              onScope(value === 'all' ? 'all' : 'selected')
            }
          >
            <ScopeChoice
              id={`${fieldId}-selected`}
              value="selected"
              label={messages.label('label.export.selected', {
                count: scopes.selected,
              })}
            />
            <ScopeChoice
              id={`${fieldId}-all`}
              value="all"
              label={allLabel(scopes.all, messages)}
            />
          </RadioGroup>
        </FieldSet>
      )}

      {/* What the file will hold, in the order somebody would check it:
          how many rows, under what, which columns, and what it is called. */}
      <div
        data-slot="export-summary"
        className="text-muted-foreground flex flex-col gap-1 text-sm"
      >
        <span data-slot="export-rows" className="text-foreground">
          {count === null
            ? messages.label('label.export.rows-unknown')
            : messages.label('label.export.rows', { count })}
        </span>
        <span data-slot="export-conditions">
          {messages.label('label.export.conditions', {
            conditions:
              conditions.length === 0
                ? messages.label('label.applied.all')
                : conditions
                    .map(item => summaryText(item, messages, display))
                    .join(' · '),
          })}
        </span>
        <span data-slot="export-columns">
          {messages.label('label.export.columns', {
            count: columns.length,
            // The catalogue's one list separator, which is `、` in Chinese
            // and `, ` in English — `Intl.ListFormat` has no shape for a
            // bare enumeration in Chinese, and every other read-out list in
            // this package is joined by this key.
            names: columns
              .map(column => column.label)
              .join(messages.label('label.filter.join')),
          })}
        </span>
        <span data-slot="export-file">
          {messages.label('label.export.file', { name: fileName })}
        </span>
      </div>

      {/* The ceiling, before the button rather than after the download: the
          press is the consent, so what is consented to is on screen. */}
      {overLimit && (
        <p
          data-slot="export-over-limit"
          role="status"
          className="text-warning text-sm"
        >
          {messages.label('label.export.over-limit', { max })}
        </p>
      )}
    </>
  );
}

/** One scope on offer. The radio renders as a span, so the label points. */
function ScopeChoice({
  id,
  value,
  label,
}: {
  id: string;
  value: RecordExportScope;
  label: string;
}) {
  return (
    <Field orientation="horizontal">
      <RadioGroupItem
        id={id}
        value={value}
        data-scope={value}
        aria-labelledby={`${id}-name`}
      />
      <FieldContent>
        <FieldLabel id={`${id}-name`} htmlFor={id}>
          {label}
        </FieldLabel>
      </FieldContent>
    </Field>
  );
}

/** Step two: how far it has got. The way to stop it is the footer's button. */
function RunningStep({ control }: { control: RecordExportController }) {
  const messages = useViewMessages();
  const fetched = control.progress?.fetched ?? 0;
  const total = control.progress?.total;
  const said =
    total === undefined
      ? messages.label('label.export.progress-unknown', { fetched })
      : messages.label('label.export.progress', { fetched, total });
  return (
    <div data-slot="export-progress" className="flex flex-col gap-2">
      {/* Indeterminate where no total was reported: a bar that filled
          against a number nobody has would be inventing the number.
          `aria-valuetext` is the line printed beside it rather than the
          registry's default percentage — a bar and its caption must not read
          as two different answers. The track is raised from the registry's
          1px at the call site, because a 1px rule the length of the window
          reads as a divider rather than as something filling; the vendored
          file stays as it ships. */}
      <Progress
        aria-label={messages.label('label.export.running')}
        aria-valuetext={said}
        value={total === undefined ? null : fetched}
        max={total ?? 100}
        className="[&_[data-slot=progress-track]]:h-2"
      />
      <span data-slot="export-count" role="status" className="text-sm">
        {said}
      </span>
    </div>
  );
}

/** Step three: what the file holds, and what it had to leave out. */
function DoneStep({
  control,
  fileName,
  max,
}: {
  control: RecordExportController;
  fileName: string;
  max: number;
}) {
  const messages = useViewMessages();
  const outcome = control.outcome;
  if (!outcome) return null;
  return (
    <div data-slot="export-done" className="flex flex-col gap-1 text-sm">
      <span data-slot="export-file" className="text-muted-foreground">
        {messages.label('label.export.file', { name: fileName })}
      </span>
      {/* A file the ceiling cut short is the file that was agreed to, so it
          is said as part of the outcome rather than as a warning of its own. */}
      {outcome.capped && (
        <span data-slot="export-capped" className="text-warning">
          {outcome.total === undefined
            ? messages.label('label.export.done-capped-unknown', { max })
            : messages.label('label.export.done-capped', {
                max,
                total: outcome.total,
              })}
        </span>
      )}
    </div>
  );
}

/** The one or two buttons this phase ends in. */
function ExportActions({
  phase,
  control,
  scope,
  primary,
  onClose,
}: {
  phase: ExportPhase;
  control: RecordExportController;
  scope: RecordExportScope;
  primary: RefObject<HTMLButtonElement | null>;
  onClose(): void;
}) {
  const messages = useViewMessages();
  if (phase === 'running')
    return (
      <DialogFooter>
        <Button
          ref={primary}
          variant="outline"
          data-slot="export-cancel"
          onClick={control.cancel}
        >
          {messages.label('label.dialog.cancel')}
        </Button>
      </DialogFooter>
    );

  if (phase === 'choose')
    return (
      <DialogFooter>
        <DialogClose render={<Button variant="outline" />}>
          {messages.label('label.dialog.cancel')}
        </DialogClose>
        <Button
          ref={primary}
          data-slot="export-confirm"
          disabled={control.running !== null}
          onClick={() => control.run(scope)}
        >
          {messages.label('label.export.confirm')}
        </Button>
      </DialogFooter>
    );

  return (
    <DialogFooter>
      {phase === 'failed' && (
        <Button
          variant="outline"
          data-slot="export-retry"
          onClick={() => {
            control.reset();
            control.run(scope);
          }}
        >
          {messages.label('label.export.retry')}
        </Button>
      )}
      <Button ref={primary} data-slot="export-close" onClick={onClose}>
        {messages.label('label.dialog.close')}
      </Button>
    </DialogFooter>
  );
}

/** "All", with its count where the source reports one. */
function allLabel(all: number | null, messages: MessageFormatters): string {
  return all === null
    ? messages.label('label.export.all-unknown')
    : messages.label('label.export.all', { count: all });
}
