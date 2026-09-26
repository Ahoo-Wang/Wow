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

import { useMemo, useState, type ReactNode } from 'react';
import { TriangleAlertIcon, XIcon } from 'lucide-react';
import type { FilterSummaryItem } from '../../filter/index.js';
import {
  filterTypeOf,
  type DashboardField,
  type Issue,
} from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from '../components/alert.js';
import { createToastManager, ToastProvider } from '../components/toast.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { FilterBar } from './FilterBar.js';
import { filterNamer } from './findings.js';
import type { BoardFilterModes } from './filterModes.js';
import { AddFilterMenu, FilterSettings } from './FilterSettings.js';
import {
  addFilterOf,
  boardOf,
  chipOf,
  chipsOf,
  focusIn,
  useLanding,
  valueOf,
} from './landing.js';
import {
  BoardToasts,
  FilterWiringContext,
  WiringBar,
  wireAndSay,
  type FilterWiring,
} from './FilterWiring.js';

/** What the board draws of its filters, and where. */
export interface BoardFilterParts {
  /**
   * The filter bar, over everything else on the board (D22 F); in the
   * one-column reading, one button and a sheet (D26 Q38).
   */
  bar(narrow: boolean): ReactNode;
  /** 「添加筛选」, for the edit bar while the board is built (D22 G). */
  add: ReactNode;
  /** The line saying which filter is being wired, while one is. */
  wiring: ReactNode;
  /** Around the board: the wiring its panels read, and its toasts. */
  wrap(children: ReactNode): ReactNode;
}

/**
 * A board's filters as the board draws them (D22 F, G): the bar; while the
 * board is built, 「添加筛选」, each filter's settings, and wiring — every
 * panel's strip, and after a pick on one panel a toast saying how many more
 * auto-connect wired, with 「只接刚选的面板」. Wiring ends with the building.
 */
export function useBoardFilters({
  dashboard,
  editing,
  say,
  modes,
  refused = NONE_REFUSED,
  fixed,
}: {
  dashboard: DashboardController;
  editing: boolean;
  /** What the board's live region says. */
  say(words: string): void;
  /** How an embedding page offers each filter (`FilterBar.modes`). */
  modes?: BoardFilterModes | undefined;
  /** What the board refused of the filters it opened on (`FiltersRefused`). */
  refused?: readonly Issue[] | undefined;
  /** The board's fixed scope in force, read-only beside the filters (D27). */
  fixed?: readonly FilterSummaryItem[] | undefined;
}): BoardFilterParts {
  const messages = useViewMessages();
  const [settingsOf, setSettingsOf] = useState<string | null>(null);
  const [wiringOf, setWiringOf] = useState<string | null>(null);
  const [toasts] = useState(() => createToastManager());
  // Where the keyboard goes when wiring ends or a filter goes, taking the
  // control pressed with them (U-02).
  const land = useLanding();
  // Said by each filter's name on the bar, never its key (X-03); held
  // while the refusal is, so putting it away holds too.
  const filterFields = dashboard.filterFields;
  const namedRefused = useMemo(
    () => refused.map(filterNamer(filterFields)),
    [refused, filterFields],
  );
  const edit = dashboard.edit;
  const building = editing && edit !== null;
  const filter = building
    ? (dashboard.filterFields.find(field => field.name === wiringOf) ?? null)
    : null;

  const wiring: FilterWiring | null = filter && {
    filter,
    wire: (panelId, field) =>
      wireAndSay(dashboard, filter, panelId, field, (connected, wiredBy) => {
        const panel = dashboard.panels.find(entry => entry.id === panelId);
        const label =
          panel?.runtime?.definition.fields.find(
            entry => entry.name === wiredBy,
          )?.label ?? wiredBy;
        // A search reaches each record view's search box whatever it is
        // called (`bindPanel`), so the toast names no field.
        const search = filterTypeOf(filter.kind) === 'search';
        toasts.add({
          title: messages.label(
            search
              ? 'label.filters.auto-wired-search'
              : 'label.filters.auto-wired',
            { count: connected.length, field: label },
          ),
          timeout: 10_000,
          actionProps: {
            children: messages.label('label.filters.only-picked'),
            onClick: () => edit?.unbindPanels(filter.name, connected),
          },
        });
      }),
  };

  const settings = (field: DashboardField) =>
    building ? (
      <FilterSettings
        field={field}
        dashboard={dashboard}
        open={settingsOf === field.name}
        onOpenChange={open => setSettingsOf(open ? field.name : null)}
        onWire={() => setWiringOf(field.name)}
        onRemoved={(label, from) => {
          // The chip goes, its settings with it: on to the chip that takes
          // its place, or 「添加筛选」 after the last.
          const board = boardOf(from);
          const at = chipsOf(board).findIndex(
            chip => chip.dataset.filter === field.name,
          );
          land(() => valueOf(chipsOf(board)[at]) ?? addFilterOf(board));
          setSettingsOf(null);
          if (wiringOf === field.name) setWiringOf(null);
          say(messages.label('label.filters.removed', { filter: label }));
        }}
      />
    ) : null;

  return {
    bar: narrow => (
      <>
        <FiltersRefused issues={namedRefused} />
        <FilterBar
          dashboard={dashboard}
          narrow={narrow}
          fixed={fixed}
          settings={building ? settings : undefined}
          onRemoveGrouping={
            building ? () => edit.setTimeGrouping(null) : undefined
          }
          onRemoveFixed={
            building
              ? () => {
                  edit.removeFixedScope();
                  say(messages.label('label.filters.fixed-removed'));
                }
              : undefined
          }
          modes={modes}
          order={
            building
              ? { move: (name, to) => edit.moveFilter(name, to), say }
              : undefined
          }
        />
      </>
    ),
    add: building && (
      <AddFilterMenu dashboard={dashboard} onAdded={setSettingsOf} />
    ),
    wiring: filter && (
      <WiringBar
        filter={filter}
        onDone={from => {
          // The bar goes with its button: back to the settings the wiring
          // was started from.
          const board = boardOf(from);
          const name = filter.name;
          land(() =>
            chipOf(board, name)?.querySelector(
              '[data-slot="dashboard-filter-settings"]',
            ),
          );
          setWiringOf(null);
        }}
      />
    ),
    wrap: children => (
      <ToastProvider toastManager={toasts}>
        <FilterWiringContext.Provider value={wiring}>
          {children}
        </FilterWiringContext.Provider>
        {/* Only while the board is built, the one time a toast can come
            (auto-connect's undo): the viewport is a landmark and a live
            region of its own, and listens for F6 on the whole window — a
            board only read, an embed on a wall screen among them, has no
            use for any of the three (U-08). */}
        {building && <BoardToasts />}
      </ToastProvider>
    ),
  };
}

const NONE_REFUSED: readonly Issue[] = [];

/**
 * What the board left out of the filters it opened on — a host's address
 * naming a filter the board no longer has, or a value its filter refuses
 * (`DashboardRuntime.refusedFilters`) — said once, over the filter bar, the
 * way a refused narrowing is said over an embed: what was refused and why,
 * in the catalogue's words. The rest of the address is in force.
 *
 * **It can be put away**, where the refused narrowing cannot: that one is
 * the page's condition, refused on every render until the page changes it,
 * and the rows under it are wider than the page asked for as long as it
 * stands. This one is about the moment the board opened — the bar under it
 * shows what is in force, and the reader may set the filter again — so
 * once read it is only in the way of the board. A board opened again, with
 * a new refusal, says it again.
 */
export function FiltersRefused({ issues }: { issues: readonly Issue[] }) {
  const messages = useViewMessages();
  const [dismissed, setDismissed] = useState<readonly Issue[] | null>(null);
  const land = useLanding();
  if (issues.length === 0 || dismissed === issues) return null;
  return (
    <Alert data-slot="dashboard-filters-refused">
      <TriangleAlertIcon aria-hidden="true" />
      <AlertTitle>{messages.label('label.filters.refused')}</AlertTitle>
      <AlertDescription>{messages.issues(issues)}</AlertDescription>
      <AlertAction>
        <IconButton
          data-slot="dashboard-filters-refused-dismiss"
          label={messages.label('label.filters.dismiss')}
          variant="ghost"
          size="icon-xs"
          onClick={event => {
            // The notice goes with its ✕: on to the bar it was about.
            const board = boardOf(event.currentTarget);
            land(() =>
              focusIn(
                board?.querySelector('[data-slot="dashboard-filter-bar"]'),
              ),
            );
            setDismissed(issues);
          }}
        >
          <XIcon />
        </IconButton>
      </AlertAction>
    </Alert>
  );
}
