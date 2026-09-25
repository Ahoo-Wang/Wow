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

import { useId, useState, type FormEvent } from 'react';
import {
  CLICK_GO_KINDS,
  clickDraftGaps,
  clickDraftOf,
  clickOf,
  crossFilterChoices,
  draftedClick,
  pressableGroups,
  withBoard,
  type ClickChoice,
  type ClickDraft,
  type PressableGroup,
} from '../../dashboard/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
import type { DashboardField } from '../../model/index.js';
import type { ViewEngine } from '../../runtime/index.js';
import { CompactSelect } from '../analysis/CompactSelect.js';
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
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from '../components/field.js';
import { Input } from '../components/input.js';
import { RadioGroup, RadioGroupItem } from '../components/radio-group.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DialogContent } from '../popups.js';
import type { FinalFocus } from './commands.js';
import {
  BoardDestination,
  useDestinationBoard,
  type BoardRead,
} from './BoardDestination.js';
import { useCatalogue, ViewPicker } from './ViewPicker.js';

export interface ClickSettingsProps {
  engine: ViewEngine;
  dashboard: DashboardController;
  /** The panel whose press is set, and its name; kept while it closes. */
  panel: DashboardPanelView | null;
  name: string;
  open: boolean;
  onClose(): void;
  finalFocus: FinalFocus;
  /**
   * Whether the page has a route to the workbench: without one the
   * follow-up menu opens nothing, and the default says so.
   */
  routed: boolean;
}

/**
 * 「点击时…」 (D22 I): what pressing one group of an analysis panel does —
 * the follow-up menu (the default, D22 H), setting a board filter the panel
 * is wired to through a field it groups by (cross-filtering), or going to
 * another saved view, another board (its filters mapped one by one, D23
 * Q17: `BoardDestination`) or a page of the host's, the group carried
 * along. One choice among three, so a `RadioGroup`; what each choice needs
 * sits under it. 「完成」 writes it into the draft (`setPanelClick`), as every
 * edit of a board is; the board's own 「保存」 saves it.
 */
export function ClickSettings({
  engine,
  dashboard,
  panel,
  name,
  open,
  onClose,
  finalFocus,
  routed,
}: ClickSettingsProps) {
  return (
    <Dialog open={open} onOpenChange={next => !next && onClose()}>
      <DialogContent
        data-slot="panel-click-settings"
        className="sm:max-w-lg"
        finalFocus={finalFocus}
      >
        {panel && (
          <ClickForm
            key={panel.id}
            engine={engine}
            dashboard={dashboard}
            panel={panel}
            name={name}
            routed={routed}
            onDone={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function ClickForm({
  engine,
  dashboard,
  panel,
  name,
  routed,
  onDone,
}: {
  engine: ViewEngine;
  dashboard: DashboardController;
  panel: DashboardPanelView;
  name: string;
  routed: boolean;
  onDone(): void;
}) {
  const messages = useViewMessages();
  const ids = useId();
  // What the panel groups by — its view's dimensions, as it runs here —
  // and the board filters a press can set through them.
  const { groups } = pressableGroups(panel.runtime?.getSnapshot().applied);
  const choices =
    panel.panel.kind === 'view'
      ? crossFilterChoices(dashboard.filterFields, panel.panel, groups)
      : [];

  // Every choice the form holds, the ones not picked kept (`ClickDraft`).
  const [draft, setDraft] = useState(() =>
    clickDraftOf(
      clickOf(panel.panel),
      choices.map(entry => entry.filter.name),
    ),
  );
  const change = (patch: Partial<ClickDraft>) =>
    setDraft(current => ({ ...current, ...patch }));
  const { choice } = draft;
  // The board a press opens, read, and what each of its filters takes
  // (D23 Q17).
  const read = useDestinationBoard(dashboard, draft.board);
  // Which picker is open; what it was for is kept while it closes.
  const [picker, setPicker] = useState<{ open: boolean; for: PickFor }>({
    open: false,
    for: 'destination',
  });
  // Whether 完成 was pressed with something missing: only then is it said.
  const [tried, setTried] = useState(false);
  const { views } = useCatalogue(engine);
  const viewTitle = views.find(entry => entry.id === draft.view)?.title;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const click = draftedClick(
      draft,
      read.status === 'ready' ? read.board.config : undefined,
      {
        groups,
        fields: panel.runtime?.fields ?? null,
        own: dashboard.filterFields,
      },
    );
    if (click === undefined) {
      setTried(true);
      return;
    }
    dashboard.edit?.setPanelClick(panel.id, click);
    onDone();
  };
  const gaps = clickDraftGaps(draft);

  return (
    <>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader>
          <DialogTitle>
            {messages.label('label.click.title', { panel: name })}
          </DialogTitle>
          <DialogDescription>
            {messages.label('label.click.description')}
          </DialogDescription>
        </DialogHeader>
        <FieldSet>
          <FieldLegend variant="label" className="sr-only">
            {messages.label('label.click.choice')}
          </FieldLegend>
          <RadioGroup
            data-slot="click-choice"
            value={choice}
            onValueChange={next => change({ choice: next as ClickChoice })}
          >
            <ChoiceRow
              id={`${ids}-menu`}
              value="menu"
              label={messages.label('label.click.menu')}
              hint={messages.label(
                routed ? 'label.click.menu-hint' : 'label.click.menu-no-route',
              )}
            />
            <ChoiceRow
              id={`${ids}-filter`}
              value="filter"
              label={messages.label('label.click.filter')}
              hint={messages.label(
                choices.length > 0
                  ? 'label.click.filter-hint'
                  : 'label.click.filter-none',
              )}
              disabled={choices.length === 0}
            />
            {choice === 'filter' && choices.length > 0 && (
              <Field className="pl-6">
                <FieldLabel>
                  {messages.label('label.click.filter-pick')}
                </FieldLabel>
                <CompactSelect
                  label={messages.label('label.click.filter-pick')}
                  items={choices.map(entry => ({
                    value: entry.filter.name,
                    label: entry.filter.label,
                  }))}
                  value={draft.filter}
                  onChange={filter => change({ filter })}
                />
              </Field>
            )}
            <ChoiceRow
              id={`${ids}-go`}
              value="go"
              label={messages.label('label.click.go')}
              hint={messages.label('label.click.go-hint')}
            />
          </RadioGroup>
        </FieldSet>
        {choice === 'go' && (
          <GoFields
            draft={draft}
            change={change}
            panel={panel}
            groups={groups}
            own={dashboard.filterFields}
            read={read}
            gaps={tried ? gaps : NO_GAPS}
            viewTitle={viewTitle}
            onPick={what => setPicker({ open: true, for: what })}
            ids={ids}
          />
        )}
        <DialogFooter>
          <DialogClose render={<Button type="button" variant="outline" />}>
            {messages.label('label.dialog.cancel')}
          </DialogClose>
          <Button type="submit">{messages.label('label.click.save')}</Button>
        </DialogFooter>
      </form>
      {/* Over this dialog, and outside the form: a view picked is the
          destination, and the keyboard comes back to the button. */}
      <ViewPicker
        engine={engine}
        intent={{ mode: picker.for, title: name }}
        open={picker.open}
        onClose={() => setPicker(current => ({ ...current, open: false }))}
        finalFocus={() => true}
        onBoard={NO_VIEWS}
        shared={false}
        onPick={picked =>
          // Another board, other filters: nothing carries over by name.
          setDraft(current =>
            picker.for === 'board'
              ? withBoard(current, picked.id)
              : { ...current, view: picked.id },
          )
        }
      />
    </>
  );
}

/** What a press that goes somewhere needs: where, and the one thing it goes to. */
function GoFields({
  draft,
  change,
  panel,
  groups,
  own,
  read,
  gaps,
  viewTitle,
  onPick,
  ids,
}: {
  draft: ClickDraft;
  change(patch: Partial<ClickDraft>): void;
  panel: DashboardPanelView;
  groups: readonly PressableGroup[];
  own: readonly DashboardField[];
  read: BoardRead;
  /** What is missing, once 完成 was pressed without it. */
  gaps: ReturnType<typeof clickDraftGaps>;
  viewTitle: string | undefined;
  onPick(what: PickFor): void;
  ids: string;
}) {
  const messages = useViewMessages();
  const { goKind, view } = draft;
  const fields = [...new Set(groups.map(group => group.field))];
  return (
    <FieldGroup className="gap-3 pl-6" data-slot="click-go">
      <Field>
        <FieldLabel id={`${ids}-go-kind`}>
          {messages.label('label.click.go-kind')}
        </FieldLabel>
        <ToggleGroup
          aria-labelledby={`${ids}-go-kind`}
          value={[goKind]}
          onValueChange={next => {
            const picked = CLICK_GO_KINDS.find(kind => kind === next[0]);
            if (picked) change({ goKind: picked });
          }}
          variant="outline"
          size="sm"
        >
          <ToggleGroupItem value="view">
            {messages.label('label.click.go-view')}
          </ToggleGroupItem>
          <ToggleGroupItem value="dashboard">
            {messages.label('label.click.go-board')}
          </ToggleGroupItem>
          <ToggleGroupItem value="url">
            {messages.label('label.click.go-url')}
          </ToggleGroupItem>
        </ToggleGroup>
      </Field>
      {goKind === 'dashboard' ? (
        <BoardDestination
          panel={panel}
          groups={groups}
          own={own}
          read={read}
          values={draft.values}
          onValues={values => change({ values })}
          tab={draft.tab}
          onTab={tab => change({ tab })}
          missing={gaps.board}
          onPick={() => onPick('board')}
          ids={ids}
        />
      ) : goKind === 'view' ? (
        <Field data-invalid={gaps.view || undefined}>
          {view && (
            <span data-slot="click-view" className="text-sm">
              {viewTitle ?? view}
            </span>
          )}
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="self-start"
            aria-invalid={gaps.view || undefined}
            onClick={() => onPick('destination')}
          >
            {messages.label(
              view ? 'label.click.view-change' : 'label.click.view-pick',
            )}
          </Button>
          {gaps.view && (
            <FieldError>
              {messages.label('label.click.view-missing')}
            </FieldError>
          )}
        </Field>
      ) : (
        <Field data-invalid={gaps.url || undefined}>
          <FieldLabel htmlFor={`${ids}-url`}>
            {messages.label('label.click.url')}
          </FieldLabel>
          <Input
            id={`${ids}-url`}
            value={draft.url}
            onChange={event => change({ url: event.target.value })}
            aria-invalid={gaps.url || undefined}
            spellCheck={false}
          />
          <FieldDescription>
            {fields.length > 0
              ? messages.label('label.click.url-hint', {
                  fields: fields
                    .map(field => `{{${field}}}`)
                    .join(messages.label('label.filter.join')),
                })
              : messages.label('label.click.url-hint-none')}
          </FieldDescription>
          {gaps.url && (
            <FieldError>{messages.label('label.click.url-invalid')}</FieldError>
          )}
        </Field>
      )}
    </FieldGroup>
  );
}

const NO_GAPS: ReturnType<typeof clickDraftGaps> = {
  view: false,
  url: false,
  board: false,
};

const NO_VIEWS: ReadonlySet<string> = new Set();

/** What the view picker over the form is picking: a view, or a board. */
type PickFor = 'destination' | 'board';

/** One of the three, its label pointing at the radio and a line under it. */
function ChoiceRow({
  id,
  value,
  label,
  hint,
  disabled,
}: {
  id: string;
  value: ClickChoice;
  label: string;
  hint: string;
  disabled?: boolean;
}) {
  return (
    <Field
      orientation="horizontal"
      data-disabled={disabled || undefined}
      data-choice={value}
    >
      <RadioGroupItem
        id={id}
        value={value}
        disabled={disabled}
        aria-labelledby={`${id}-name`}
        aria-describedby={`${id}-hint`}
      />
      <FieldContent>
        <FieldLabel id={`${id}-name`} htmlFor={id}>
          {label}
        </FieldLabel>
        <FieldDescription id={`${id}-hint`}>{hint}</FieldDescription>
      </FieldContent>
    </Field>
  );
}
