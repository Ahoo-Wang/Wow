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

import { useCallback, useEffect, useMemo, useRef } from 'react';
import type { DashboardFilters, Issue, ViewKind } from '../model/index.js';
import { admitFilters } from '../dashboard/index.js';
import type { FilterSummaryItem } from '../filter/index.js';
import type {
  AnyViewRuntime,
  DashboardRuntime,
  HeldFilters,
} from '../runtime/index.js';
import { blocksBoard } from '../runtime/dashboard/panels.js';
import {
  useDashboard,
  useFilterEditor,
  type DashboardController,
  useOpenView,
  useViewRuntime,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import type { PanelHeadingLevel } from './DashboardPanel.js';
import { SurfaceAnnouncer, useAnnouncer } from './Announcer.js';
import { DashboardGrid, type DashboardGridProps } from './DashboardGrid.js';
import { useBoardFilters } from './dashboard/BoardFilters.js';
import { DashboardTabs } from './dashboard/DashboardTabs.js';
import { boardFindingNamer } from './dashboard/findings.js';
import {
  heldFilters,
  heldOf,
  holdsGrouping,
  readersOf,
  staticModes,
  type BoardFilterModes,
  type DashboardFilterMode,
} from './dashboard/filterModes.js';
import { EmbedFrame } from './embed/EmbedFrame.js';
import { EmbedExpand, EmbedHead } from './embed/EmbedHead.js';
import type { EmbedBaseProps, EmbedInteraction } from './embed/options.js';
import { useKindIssue, useKindWord } from './kinds.js';
import { useViewMessages } from './MessagesProvider.js';
import { ErrorStrip, WarningStrip } from './StatusStrip.js';

export type {
  BoardFilterModes,
  DashboardFilterMode,
} from './dashboard/filterModes.js';

export interface EmbeddedDashboardProps extends EmbedBaseProps {
  /**
   * How far the reader may go (`EmbedInteraction`): `static` by default —
   * the board as its author laid it out, its filters read as what they
   * hold, nothing on it answering a press; `interactive` lets the reader
   * change the filters and adds the follow-up menu, cross-filtering and
   * 在工作台中打开, for this viewing only. Neither builds nor saves the board
   * (D36): a page whose readers build boards embeds `DashboardWorkbench`.
   */
  interaction?: EmbedInteraction;
  /** Whether the panels' titles are drawn (on by default). */
  withPanelTitles?: boolean;
  /**
   * 「导出数据…」 in a record panel's 「⋯」 — the export window over its rows
   * (D14), as `EmbeddedView`'s `withExport` is for one view (off by
   * default). A switch rather than a tier (D24 Q24): on in the static
   * tier, the 「⋯」 holds this item alone.
   */
  withExport?: boolean;
  /**
   * How each of the board's filters is offered, by name
   * (`DashboardFilterMode`): `editable` — on the bar, the reader's — unless
   * named here; `locked` — on the bar as what it holds, fixed; `hidden` —
   * not on the bar, still narrowing what it is wired to. A locked or hidden
   * filter holds what `pageValues` gives it, or its default, whatever the
   * reader does. In the static tier the reader changes none of them: an
   * editable one reads as what it holds, as a locked one does. Not a
   * security boundary: see the README's embedding section.
   */
  filterModes?: Readonly<Record<string, DashboardFilterMode>>;
  /** The time grouping's mode, likewise; `editable` when left out. */
  groupingMode?: DashboardFilterMode;
  /**
   * What the page holds (D22): the value of each locked or hidden filter —
   * its default where this names none — and, with `groupingMode` locked or
   * hidden, the time grouping's unit. In force from the first query, and
   * followed as it changes: a customer page moving to the next customer. It
   * is the page's own, never the address's — a reader can edit an address.
   * An entry for an editable filter is ignored. What the board refuses of
   * it is said above the board, as a refused narrowing is.
   */
  pageValues?: DashboardFilters | null;
  /**
   * What the reader's filters open at, as the host's address has them (D22
   * F), read as the board opens — as `DashboardWorkbench` reads it: when it
   * names any of them it is the whole of what they hold; when it names none
   * they start at their defaults. An entry for a locked or hidden filter is
   * ignored: `pageValues` holds those.
   */
  initialFilters?: DashboardFilters | null;
  /**
   * Told what the reader's filters hold whenever that changes — the board
   * opening included — so a host can write them into its address. Only the
   * editable filters, and the time grouping unless the page holds it: a
   * locked or hidden value is the page's, and written into an address it
   * would come back as the reader's. The package never touches the address.
   */
  onFiltersChange?(filters: DashboardFilters): void;
  /** The tab the board opens on, as the host's route has it (D22 E). */
  initialTab?: string | null;
  /** Told which tab is on screen whenever that changes, opening included. */
  onTabChange?(tabId: string | null): void;
}

/** Pixel height of one grid row: the workbench's (`DashboardBoard`). */
const ROW_HEIGHT = 80;

/** Nothing refused, as one object. */
const NO_ISSUES: Issue[] = [];

/** The one kind this entry draws; a record or analysis is `EmbeddedView`'s. */
const DASHBOARD: readonly ViewKind[] = ['dashboard'];

/**
 * One saved dashboard inside a business page (D22): the board, its filter
 * bar with each filter in the mode the page gives it, and what the host
 * switched on — a tier (`interaction`), the title, the panel titles, a
 * panel's export, auto-refresh, filling the screen, 在工作台中打开. Split
 * from `EmbeddedView` by resource, as the workbenches are: a host that
 * embeds a board says so, and a record view named here is refused as one
 * this entry cannot show.
 *
 * It reads the board and never writes (D36): no 「编辑」, no save, no
 * 另存为, no preference — what the reader changes lives in this viewing
 * alone. Building a board is `DashboardWorkbench`'s.
 */
export function EmbeddedDashboard(props: EmbeddedDashboardProps) {
  const { engine, instanceId } = props;
  // Where the board opens and what its filters hold as it does — the
  // page's held filters among them, so the first query is already under
  // them — read as it opens; what the page holds is followed below.
  const latest = useRef(props);
  useEffect(() => {
    latest.current = props;
  });
  const opening = useCallback(() => {
    const {
      pageValues,
      initialFilters,
      filterModes: filters,
      groupingMode: grouping,
      initialTab: tab,
    } = latest.current;
    const modes = { filters, grouping };
    const held = heldOf(modes, pageValues);
    const reader = initialFilters ? readersOf(modes, initialFilters) : null;
    const named =
      reader !== null &&
      (Object.keys(reader.values).length > 0 || reader.unit !== undefined);
    return {
      ...(tab == null ? {} : { tab }),
      ...(named ? { filters: reader } : {}),
      ...(held ? { held } : {}),
    };
  }, []);
  const opened = useOpenView(engine, instanceId, null, opening);
  return (
    <EmbedFrame engine={engine} opened={opened} kinds={DASHBOARD} props={props}>
      {runtime => <EmbeddedBoard runtime={runtime} props={props} />}
    </EmbedFrame>
  );
}

function EmbeddedBoard({
  runtime: opened,
  props,
}: {
  runtime: AnyViewRuntime;
  props: EmbeddedDashboardProps;
}) {
  const {
    interaction = 'static',
    withTitle = false,
    headingLevel = 2,
    withPanelTitles = true,
    withExport = false,
    openInWorkbench = true,
    expandable = false,
    onNavigate,
    onRenderFailure,
    filterModes,
    groupingMode,
    pageValues,
    onFiltersChange,
    onTabChange,
  } = props;
  const runtime = opened as DashboardRuntime;
  const state = useViewRuntime(runtime);
  const dashboard = useDashboard(runtime);
  const messages = useViewMessages();
  // The board's fixed scope, read-only on the filter bar's row (D27): an
  // embedded board draws no 「正在显示」 band, as the workbench does not.
  const { fixed } = useFilterEditor(runtime);
  const interactive = interaction === 'interactive';

  // What the page holds, handed to the runtime, which keeps every command
  // of the reader's off it — followed as the page changes it (a customer
  // page moving to the next customer), keyed by what it says, so a host
  // writing its modes and values out in render does not hold them again
  // every time. What the board refuses of it is said, as a refused scope
  // is (D17-5): the page asked for one customer and must not quietly get
  // everyone's.
  const modes = { filters: filterModes, grouping: groupingMode };
  const heldKey = JSON.stringify(heldOf(modes, pageValues));
  const heldNames = JSON.stringify([heldFilters(modes), holdsGrouping(modes)]);
  useEffect(() => {
    runtime.holdFilters(JSON.parse(heldKey) as HeldFilters | null);
  }, [runtime, heldKey]);
  // The refusal is the kernel's answer to the page's values over the board
  // on screen, worked out here rather than kept from the command: the same
  // question, the same answer, whenever either side moves.
  const applied = state?.applied;
  const refused = useMemo(() => {
    const held = JSON.parse(heldKey) as HeldFilters | null;
    if (!held || !applied) return NO_ISSUES;
    const values = Object.fromEntries(
      Object.entries(held.values).filter(([, value]) => value !== null),
    ) as DashboardFilters['values'];
    return admitFilters(applied, { values }, runtime.kinds).refused;
  }, [applied, heldKey, runtime]);
  // What the board refused of the address it opened on, the reader's part
  // alone: a refusal of what the page holds is said above, as the page's.
  const addressRefused = useMemo(() => {
    const [names, grouping] = JSON.parse(heldNames) as [string[], boolean];
    return runtime.refusedFilters.filter(found =>
      found.path[0] === 'unit'
        ? !grouping
        : !names.includes(String(found.path[1])),
    );
  }, [runtime, heldNames]);

  // What the filters hold and the tab on screen, told to the host as they
  // change — the board opening included — for its address. The package
  // never touches the address, and an embed never writes where its reader
  // was (D36): the tab a workbench remembers is a preference, and an embed
  // keeps none. The reader's values alone: what the page holds is its own,
  // and would come back from the address as the reader's.
  const filtersNow = dashboard.filters;
  const readerValues = useMemo(() => {
    const [names, grouping] = JSON.parse(heldNames) as [string[], boolean];
    return readersOf(
      {
        filters: Object.fromEntries(names.map(name => [name, 'locked'])),
        grouping: grouping ? 'locked' : 'editable',
      },
      filtersNow,
    );
  }, [filtersNow, heldNames]);
  const tab = dashboard.tab;
  useEffect(() => {
    onTabChange?.(tab);
  }, [tab, onTabChange]);
  useEffect(() => {
    onFiltersChange?.(readerValues);
  }, [readerValues, onFiltersChange]);

  // The filter bar as the tier reads it: a static board's reader changes
  // nothing on it, so every filter the page left editable reads as what it
  // holds, as a locked one does. The page's own modes are still what the
  // runtime holds and what the address is told.
  const barModes = interactive
    ? modes
    : staticModes(dashboard.filterFields, {
        filters: filterModes,
        grouping: groupingMode,
      });

  const panelLevel = (
    withTitle ? Math.min(headingLevel + 1, 6) : headingLevel
  ) as PanelHeadingLevel;
  const title = state?.title ?? '';

  // What the board says above its panels, the one reading the workbench
  // shares (`DashboardController.issues`): a panel's own findings stay in
  // its frame, its errors as much as its warnings, and a filter is said by
  // its name on the bar, never its key (X-03). Only an error of the board's
  // own — "too many panels" among them — stops the whole board: one
  // panel's error drew no grid around a board where every other panel was
  // fine (R3). What the engine reports about the thing open says 仪表盘, as
  // the frame names it (`SurfaceKind`, Q34).
  const word = useKindWord();
  const ownWord = useKindIssue();
  const named = boardFindingNamer(dashboard, state?.draft, messages);
  const nameIssue = (found: Issue) => ownWord(named(found));
  const errors = dashboard.issues.filter(found => found.severity === 'error');
  const warnings = dashboard.issues.filter(found => found.severity !== 'error');
  const blocked = blocksBoard(errors);

  return (
    <>
      <EmbedHead
        title={withTitle ? title : undefined}
        headingLevel={headingLevel}
      >
        {interactive && expandable && <EmbedExpand />}
      </EmbedHead>
      {refused.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label(word('label.scope.refused'))}</AlertTitle>
          <AlertDescription>
            {messages.issues(refused.map(nameIssue))}
          </AlertDescription>
        </Alert>
      )}
      {errors.length > 0 && <ErrorStrip issues={errors.map(nameIssue)} />}
      <WarningStrip issues={warnings.map(nameIssue)} />
      {!blocked && (
        <ReadBoard
          dashboard={dashboard}
          modes={barModes}
          refused={addressRefused}
          fixed={fixed}
          grid={{
            headingLevel: panelLevel,
            panelTitles: withPanelTitles,
            readOnly: !interactive,
            openInWorkbench,
            panelExport: withExport,
            onRenderFailure,
            onNavigate: interactive ? onNavigate : undefined,
          }}
        />
      )}
    </>
  );
}

/**
 * The board as an embed reads it: the filter bar and the tabs over the
 * grid, and one voice for all three (Q-03). Nothing here builds — the edit
 * bar, the pickers and the dialogs are `DashboardBoard`'s, the workbench's.
 */
function ReadBoard({
  dashboard,
  modes,
  refused,
  fixed,
  grid,
}: {
  dashboard: DashboardController;
  modes: BoardFilterModes;
  refused: readonly Issue[];
  fixed: readonly FilterSummaryItem[];
  grid: Pick<
    DashboardGridProps,
    | 'headingLevel'
    | 'panelTitles'
    | 'readOnly'
    | 'openInWorkbench'
    | 'panelExport'
    | 'onRenderFailure'
    | 'onNavigate'
  >;
}) {
  const { say, region } = useAnnouncer('dashboard-announcement');
  const filters = useBoardFilters({
    dashboard,
    editing: false,
    say,
    modes,
    refused,
    fixed,
  });
  return filters.wrap(
    <SurfaceAnnouncer say={say}>
      <DashboardGrid
        {...grid}
        dashboard={dashboard}
        rowHeight={ROW_HEIGHT}
        filterModes={modes}
        header={({ narrow }) => (
          <>
            {/* The filters over everything else: they are what the whole
              board is read under, every tab alike (D22 E, F). */}
            {filters.bar(narrow)}
            <DashboardTabs dashboard={dashboard} />
          </>
        )}
      />
      {region}
    </SurfaceAnnouncer>,
  );
}
