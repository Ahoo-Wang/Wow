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
  clickOf,
  crossFilterChoices,
  fillUrl,
  pressableGroups,
  urlPlaceholders,
} from '../../dashboard/index.js';
import type { PanelClick } from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
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
 * another saved view or a page of the host's, the group carried along.
 * One choice among three, so a `RadioGroup`; what each choice needs sits
 * under it. 「完成」 writes it into the draft (`setPanelClick`), as every
 * edit of a board is; the board's own 「完成」 saves it.
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

type Choice = 'menu' | 'filter' | 'go';
type GoKind = 'view' | 'url';

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
  const stored = clickOf(panel.panel);
  // What the panel groups by — its view's dimensions, as it runs here —
  // and the board filters a press can set through them.
  const { groups } = pressableGroups(panel.runtime?.getSnapshot().applied);
  const choices =
    panel.panel.kind === 'view'
      ? crossFilterChoices(dashboard.filterFields, panel.panel, groups)
      : [];
  const fields = [...new Set(groups.map(group => group.field))];

  const [choice, setChoice] = useState<Choice>(
    stored === null ? 'menu' : stored.kind === 'filter' ? 'filter' : 'go',
  );
  const [filter, setFilter] = useState(
    stored?.kind === 'filter' &&
      choices.some(entry => entry.filter.name === stored.filter)
      ? stored.filter
      : (choices[0]?.filter.name ?? ''),
  );
  const [goKind, setGoKind] = useState<GoKind>(
    stored?.kind === 'url' ? 'url' : 'view',
  );
  const [view, setView] = useState(
    stored?.kind === 'view' ? stored.instanceId : '',
  );
  const [url, setUrl] = useState(stored?.kind === 'url' ? stored.url : '');
  const [picking, setPicking] = useState(false);
  const [tried, setTried] = useState(false);
  const { views } = useCatalogue(engine);
  const viewTitle = views.find(entry => entry.id === view)?.title;

  const urlValid =
    fillUrl(
      url,
      Object.fromEntries(urlPlaceholders(url).map(key => [key, 'x'])),
    ) !== null;
  const wanted = (): PanelClick | null | undefined => {
    if (choice === 'menu') return null;
    if (choice === 'filter')
      return filter ? { kind: 'filter', filter } : undefined;
    if (goKind === 'view')
      return view ? { kind: 'view', instanceId: view } : undefined;
    return urlValid ? { kind: 'url', url: url.trim() } : undefined;
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const click = wanted();
    if (click === undefined) {
      setTried(true);
      return;
    }
    dashboard.edit?.setPanelClick(panel.id, click);
    onDone();
  };
  const viewMissing = tried && choice === 'go' && goKind === 'view' && !view;
  const urlInvalid = tried && choice === 'go' && goKind === 'url' && !urlValid;

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
            onValueChange={next => setChoice(next as Choice)}
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
                  value={filter}
                  onChange={setFilter}
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
          <FieldGroup className="gap-3 pl-6" data-slot="click-go">
            <Field>
              <FieldLabel id={`${ids}-go-kind`}>
                {messages.label('label.click.go-kind')}
              </FieldLabel>
              <ToggleGroup
                aria-labelledby={`${ids}-go-kind`}
                value={[goKind]}
                onValueChange={next => {
                  const [picked] = next;
                  if (picked === 'view' || picked === 'url') setGoKind(picked);
                }}
                variant="outline"
                size="sm"
              >
                <ToggleGroupItem value="view">
                  {messages.label('label.click.go-view')}
                </ToggleGroupItem>
                <ToggleGroupItem value="url">
                  {messages.label('label.click.go-url')}
                </ToggleGroupItem>
              </ToggleGroup>
            </Field>
            {goKind === 'view' ? (
              <Field data-invalid={viewMissing || undefined}>
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
                  aria-invalid={viewMissing || undefined}
                  onClick={() => setPicking(true)}
                >
                  {messages.label(
                    view ? 'label.click.view-change' : 'label.click.view-pick',
                  )}
                </Button>
                {viewMissing && (
                  <FieldError>
                    {messages.label('label.click.view-missing')}
                  </FieldError>
                )}
              </Field>
            ) : (
              <Field data-invalid={urlInvalid || undefined}>
                <FieldLabel htmlFor={`${ids}-url`}>
                  {messages.label('label.click.url')}
                </FieldLabel>
                <Input
                  id={`${ids}-url`}
                  value={url}
                  onChange={event => setUrl(event.target.value)}
                  aria-invalid={urlInvalid || undefined}
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
                {urlInvalid && (
                  <FieldError>
                    {messages.label('label.click.url-invalid')}
                  </FieldError>
                )}
              </Field>
            )}
          </FieldGroup>
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
        intent={{ mode: 'destination', title: name }}
        open={picking}
        onClose={() => setPicking(false)}
        finalFocus={() => true}
        onBoard={NO_VIEWS}
        shared={false}
        onPick={picked => setView(picked.id)}
      />
    </>
  );
}

const NO_VIEWS: ReadonlySet<string> = new Set();

/** One of the three, its label pointing at the radio and a line under it. */
function ChoiceRow({
  id,
  value,
  label,
  hint,
  disabled,
}: {
  id: string;
  value: Choice;
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
