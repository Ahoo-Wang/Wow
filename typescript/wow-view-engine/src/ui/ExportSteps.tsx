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

/**
 * The steps of the export window (D14), each what the window says in one
 * phase: which rows and what the file will hold, how far the run has got,
 * what the file came to — and the buttons each phase ends in.
 */

import { useId, type RefObject } from 'react';
import type {
  RecordExportController,
  RecordExportScope,
} from '../react/index.js';
import { LineAlert } from './alerts.js';
import { AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import { DialogClose, DialogFooter } from './components/dialog.js';
import {
  Field,
  FieldContent,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from './components/field.js';
import { Progress } from './components/progress.js';
import { RadioGroup, RadioGroupItem } from './components/radio-group.js';
import type { ExportWindowProps } from './ExportDialog.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import { summaryText } from './summary.js';
import { useSurfaceDisplay } from './ViewSurface.js';

/** Which of the four things the one window is saying at this moment. */
export type ExportPhase = 'choose' | 'running' | 'done' | 'failed';

/** What the window is saying, read off the controller rather than stored. */
export function phaseOf(control: RecordExportController): ExportPhase {
  if (control.error) return 'failed';
  if (control.outcome) return 'done';
  // Only the whole-result export takes time. The picked rows are already in
  // hand, so that scope goes from the button straight to the outcome rather
  // than flashing a progress bar for one frame.
  return control.running === 'all' ? 'running' : 'choose';
}

/** The window's one-line description, which is a different line per phase. */
export function said(
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
export function ChooseStep({
  control,
  columns,
  conditions,
  fileName,
  max,
  scope,
  onScope,
}: ExportWindowProps & {
  /** The name this opening settled on; see `ExportOffer.nameFile`. */
  fileName: string;
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
          press is the consent, so what is consented to is on screen — and
          it is the one thing in this window that is a callout rather than a
          detail of the file, so it is drawn as one. */}
      {overLimit && (
        <LineAlert tone="warning" data-slot="export-over-limit">
          <AlertTitle>
            {messages.label('label.export.over-limit', { max })}
          </AlertTitle>
        </LineAlert>
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
export function RunningStep({ control }: { control: RecordExportController }) {
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
export function DoneStep({
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
export function ExportActions({
  phase,
  control,
  scope,
  fileName,
  primary,
  onClose,
}: {
  phase: ExportPhase;
  control: RecordExportController;
  scope: RecordExportScope;
  /** The name this opening settled on; see `ExportOffer.nameFile`. */
  fileName: string;
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
          onClick={() => control.run(scope, fileName)}
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
            // The same name the first attempt was offered under: a retry is
            // this journey continuing, not a second one.
            control.run(scope, fileName);
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
