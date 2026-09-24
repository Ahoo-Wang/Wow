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

import { useEffect, useId, useState } from 'react';
import type { FinalFocus } from './commands.js';
import { SearchIcon } from 'lucide-react';
import { cn } from 'cn';
import {
  audienceOf,
  isSystemScope,
  type Issue,
  type ViewInstanceSummary,
} from '../../model/index.js';
import type { ViewEngine } from '../../runtime/index.js';
import { AlertTitle } from '../components/alert.js';
import { Badge } from '../components/badge.js';
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '../components/dialog.js';
import { Field, FieldGroup, FieldLabel } from '../components/field.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
} from '../components/input-group.js';
import {
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemMedia,
  ItemTitle,
} from '../components/item.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/select.js';
import { Skeleton } from '../components/skeleton.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { LineAlert } from '../alerts.js';
import { KIND_ICON } from '../kinds.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { DialogContent, SelectContent } from '../popups.js';
import { RowItem } from '../RowItem.js';
import { SystemMark } from '../SystemMark.js';

/** What the picker is for: a new panel, or another view for one panel. */
export type PickerIntent =
  | { mode: 'add' }
  | { mode: 'replace'; panelId: string; title: string }
  /** Where a press on a panel goes (D22 I, 「去另一个视图」). */
  | { mode: 'destination'; title: string };

export interface ViewPickerProps {
  engine: ViewEngine;
  /** What the last opening was for; kept while it closes. */
  intent: PickerIntent | null;
  open: boolean;
  onClose(): void;
  /** Where the keyboard goes once it closes — the menu that asked is gone. */
  finalFocus: FinalFocus;
  /** The saved views the board already shows, marked 「已在板上」. */
  onBoard: ReadonlySet<string>;
  /**
   * Whether the board is shared: a personal view on it is then marked
   * 「只有你看得到」 — it may go on (D22 B), and its panel says the same.
   */
  shared: boolean;
  onPick(view: ViewInstanceSummary): void;
}

/**
 * Choosing a saved view for a board (D22 B): every record and analysis view
 * of every data definition, grouped as the view switcher groups them — the
 * ones the definitions ship, the shared ones, the reader's own — each with
 * its kind's icon; searched by name, narrowed by kind and by data.
 *
 * A view already on the board can go on again: two panels over one view,
 * set to look different, is ordinary. It says so rather than being hidden.
 */
export function ViewPicker({
  engine,
  intent,
  open,
  onClose,
  finalFocus,
  onBoard,
  shared,
  onPick,
}: ViewPickerProps) {
  const messages = useViewMessages();
  return (
    <Dialog open={open} onOpenChange={next => !next && onClose()}>
      <DialogContent
        data-slot="view-picker"
        className="sm:max-w-xl"
        finalFocus={finalFocus}
      >
        <DialogHeader>
          <DialogTitle>
            {intent?.mode === 'replace'
              ? messages.label('label.picker.replace-heading', {
                  title: intent.title,
                })
              : intent?.mode === 'destination'
                ? messages.label('label.click.view-heading', {
                    panel: intent.title,
                  })
                : messages.label('label.picker.add-heading')}
          </DialogTitle>
          <DialogDescription>
            {messages.label('label.picker.description')}
          </DialogDescription>
        </DialogHeader>
        {/* Read afresh at every opening: a view saved a moment ago in
            another tab is one the author expects to find. */}
        {open && (
          <Catalogue
            engine={engine}
            onBoard={onBoard}
            shared={shared}
            onPick={view => {
              onPick(view);
              onClose();
            }}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

/** The three groups, in the switcher's order: the definitions', shared, mine. */
const GROUPS = ['system', 'shared', 'personal'] as const;
type Group = (typeof GROUPS)[number];

function groupOf(view: ViewInstanceSummary): Group {
  return isSystemScope(view.scope) ? 'system' : audienceOf(view.scope);
}

type KindFilter = 'all' | 'record' | 'analysis';

/** Every data definition's record and analysis views, as the engine lists them. */
export interface Listing {
  views: ViewInstanceSummary[];
  failed: Issue[];
  loading: boolean;
}

export function useCatalogue(engine: ViewEngine): Listing {
  const [listing, setListing] = useState<Listing>({
    views: [],
    failed: [],
    loading: true,
  });
  useEffect(() => {
    let live = true;
    const data = [...engine.definitions.values()].filter(
      definition => definition.kind === 'data',
    );
    void Promise.all(data.map(definition => engine.list(definition.id))).then(
      listings => {
        if (!live) return;
        setListing({
          views: listings.flatMap(({ items }) =>
            items.filter(item => item.kind !== 'dashboard'),
          ),
          failed: listings.flatMap(({ failed }) => failed ?? []),
          loading: false,
        });
      },
    );
    return () => {
      live = false;
    };
  }, [engine]);
  return listing;
}

function Catalogue({
  engine,
  onBoard,
  shared,
  onPick,
}: Pick<ViewPickerProps, 'engine' | 'onBoard' | 'shared' | 'onPick'>) {
  const messages = useViewMessages();
  const ids = useId();
  const { views, failed, loading } = useCatalogue(engine);
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<KindFilter>('all');
  const [data, setData] = useState('');
  const definitions = [...engine.definitions.values()].filter(
    definition =>
      definition.kind === 'data' &&
      views.some(view => view.definitionId === definition.id),
  );
  const titleOf = (id: string) => engine.definitions.get(id)?.title ?? id;
  const wanted = query.trim().toLocaleLowerCase();
  const shown = views.filter(
    view =>
      (kind === 'all' || view.kind === kind) &&
      (data === '' || view.definitionId === data) &&
      (wanted === '' || view.title.toLocaleLowerCase().includes(wanted)),
  );
  const definitionItems = [
    { value: '', label: messages.label('label.picker.definition.all') },
    ...definitions.map(definition => ({
      value: definition.id,
      label: definition.title,
    })),
  ];

  return (
    <div className="flex min-h-0 flex-col gap-3">
      <FieldGroup className="gap-3">
        <InputGroup>
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
          <InputGroupInput
            data-slot="picker-search"
            type="search"
            value={query}
            placeholder={messages.label('label.picker.search')}
            aria-label={messages.label('label.picker.search')}
            onChange={event => setQuery(event.target.value)}
          />
        </InputGroup>
        <div className="flex flex-wrap items-end gap-3">
          <Field className="w-auto">
            <FieldLabel id={`${ids}-kind`}>
              {messages.label('label.picker.kind')}
            </FieldLabel>
            <ToggleGroup
              value={[kind]}
              onValueChange={value => {
                const next = KINDS.find(choice => choice === value[0]);
                if (next) setKind(next);
              }}
              variant="outline"
              size="sm"
              spacing={0}
              aria-labelledby={`${ids}-kind`}
            >
              {KINDS.map(choice => (
                <ToggleGroupItem
                  key={choice}
                  value={choice}
                  data-slot={`picker-kind-${choice}`}
                >
                  {messages.label(`label.picker.kind.${choice}`)}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          </Field>
          {definitions.length > 1 && (
            <Field className="w-auto min-w-40">
              <FieldLabel id={`${ids}-data`}>
                {messages.label('label.picker.definition')}
              </FieldLabel>
              <Select
                items={definitionItems}
                value={data}
                onValueChange={value =>
                  setData(typeof value === 'string' ? value : '')
                }
              >
                <SelectTrigger size="sm" aria-labelledby={`${ids}-data`}>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {definitionItems.map(item => (
                      <SelectItem key={item.value} value={item.value}>
                        {item.label}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>
          )}
        </div>
      </FieldGroup>

      {failed.map(found => (
        <LineAlert key={found.code} tone="warning" frame="bare">
          <AlertTitle>{messages.issue(found)}</AlertTitle>
        </LineAlert>
      ))}

      <div
        data-slot="picker-list"
        className="-mx-1 flex max-h-[min(24rem,50vh)] flex-col gap-3 overflow-y-auto px-1"
      >
        {loading ? (
          <div
            role="status"
            aria-label={messages.label('label.picker.loading')}
            className="flex flex-col gap-2"
          >
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : shown.length === 0 ? (
          <p role="status" className={cn('text-muted-foreground', TEXT_UI)}>
            {messages.label(
              views.length === 0 ? 'label.picker.empty' : 'label.picker.none',
            )}
          </p>
        ) : (
          GROUPS.map(group => {
            const items = shown.filter(view => groupOf(view) === group);
            if (items.length === 0) return null;
            const heading = `${ids}-${group}`;
            return (
              <section key={group} aria-labelledby={heading}>
                <h3
                  id={heading}
                  className={cn(
                    'text-muted-foreground px-2 pb-1 font-medium',
                    TEXT_UI,
                  )}
                >
                  {messages.label(`label.scope.group.${group}`)}
                </h3>
                <ul className="flex flex-col">
                  {items.map(view => (
                    <li key={view.id}>
                      <PickerRow
                        view={view}
                        data={titleOf(view.definitionId)}
                        onBoard={onBoard.has(view.id)}
                        private={shared && group === 'personal'}
                        onPick={() => onPick(view)}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            );
          })
        )}
      </div>
    </div>
  );
}

const KINDS: readonly KindFilter[] = ['all', 'record', 'analysis'];

/**
 * One view to pick: its kind as the switcher draws it, its name, the data
 * it is of, and what is true of it here — already on the board, or seen by
 * its author alone on a board others read.
 */
function PickerRow({
  view,
  data,
  onBoard,
  private: personal,
  onPick,
}: {
  view: ViewInstanceSummary;
  data: string;
  onBoard: boolean;
  private: boolean;
  onPick(): void;
}) {
  const messages = useViewMessages();
  const Kind = KIND_ICON[view.kind];
  return (
    <RowItem
      density="dense"
      pressable
      data-slot="picker-view"
      data-view={view.id}
      render={<button type="button" onClick={onPick} />}
    >
      <ItemMedia>
        <Kind aria-hidden />
      </ItemMedia>
      <ItemContent className="min-w-0">
        <ItemTitle className="max-w-full">
          <span className="truncate">{view.title}</span>
          {isSystemScope(view.scope) && <SystemMark />}
        </ItemTitle>
        <ItemDescription>
          {messages.label(`label.kind.${view.kind}`)} · {data}
        </ItemDescription>
      </ItemContent>
      {(onBoard || personal) && (
        <ItemActions>
          {onBoard && (
            <Badge variant="secondary" data-slot="picker-on-board">
              {messages.label('label.picker.on-board')}
            </Badge>
          )}
          {personal && (
            <Badge variant="outline" data-slot="picker-private">
              {messages.label('label.picker.private')}
            </Badge>
          )}
        </ItemActions>
      )}
    </RowItem>
  );
}
