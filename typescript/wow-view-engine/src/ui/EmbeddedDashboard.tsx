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
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
} from 'react';
import { PencilIcon } from 'lucide-react';
import {
  audienceOf,
  type DashboardFilters,
  type FieldOption,
  type Issue,
  type ViewKind,
} from '../model/index.js';
import { admitFilters } from '../dashboard/index.js';
import type {
  AnyViewRuntime,
  DashboardRuntime,
  HeldFilters,
} from '../runtime/index.js';
import {
  useDashboard,
  useLeaveGuard,
  useOpenView,
  useSaveCommands,
  useViewRuntime,
} from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import type { PanelHeadingLevel } from './DashboardPanel.js';
import { DashboardBoard, type BoardReading } from './dashboard/Board.js';
import { useDashboardExtensions } from './dashboard/building.js';
import { DashboardTabs } from './dashboard/DashboardTabs.js';
import { DashboardEditExtensionsContext } from './dashboard/extensions.js';
import {
  heldFilters,
  heldOf,
  holdsGrouping,
  readersOf,
  type DashboardFilterMode,
} from './dashboard/filterModes.js';
import { EmbedFrame } from './embed/EmbedFrame.js';
import { EmbedHead } from './embed/EmbedHead.js';
import type {
  DashboardEmbedInteraction,
  EmbedBaseProps,
} from './embed/options.js';
import { useViewMessages } from './MessagesProvider.js';
import { ErrorStrip, WarningStrip } from './StatusStrip.js';
import { WriteOutcome } from './WriteOutcome.js';

export type {
  BoardFilterModes,
  DashboardFilterMode,
} from './dashboard/filterModes.js';

export interface EmbeddedDashboardProps extends EmbedBaseProps {
  /**
   * How far the reader may go (`DashboardEmbedInteraction`): `read-only`
   * by default — the board as its author laid it out, nothing on it
   * answering a press; `interactive` adds the follow-up menu, cross-
   * filtering and 在工作台中打开; `editable` adds 「编辑」 for whoever may
   * save the board.
   */
  interaction?: DashboardEmbedInteraction;
  /** Whether the panels' titles are drawn (on by default). */
  withPanelTitles?: boolean;
  /**
   * 「导出数据…」 in a record panel's 「⋯」 — the export window over its rows
   * (D14), as `EmbeddedView`'s `withExport` is for one view (off by
   * default). A switch rather than a tier (D24 Q24): on in the read-only
   * tier, the 「⋯」 holds this item alone.
   */
  withExport?: boolean;
  /**
   * How each of the board's filters is offered, by name
   * (`DashboardFilterMode`): `editable` — on the bar, the reader's — unless
   * named here; `locked` — on the bar as what it holds, fixed; `hidden` —
   * not on the bar, still narrowing what it is wired to. A locked or hidden
   * filter holds what `pageValues` gives it, or its default, whatever the
   * reader does. Not a security boundary: see the README's embedding
   * section.
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
  /**
   * An id filter's or a condition's candidates by the host's source key,
   * for what the editable tier builds (a new analysis in the board).
   */
  optionsFor?(remote: string): FieldOption[] | undefined;
}

/** Nothing refused, as one object. */
const NO_ISSUES: Issue[] = [];

/** The one kind this entry draws; a record or analysis is `EmbeddedView`'s. */
const DASHBOARD: readonly ViewKind[] = ['dashboard'];

/**
 * One saved dashboard inside a business page (D22): the board, its filter
 * bar with each filter in the mode the page gives it, and what the host
 * switched on — a tier (`interaction`), the title, the panel titles,
 * a record panel's export, auto-refresh, 在工作台中打开. Split from `EmbeddedView` by resource, as
 * the workbenches are: a host that embeds a board says so, and a record
 * view named here is refused as one this entry cannot show.
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
    engine,
    interaction = 'read-only',
    withTitle = false,
    headingLevel = 2,
    withPanelTitles = true,
    withExport = false,
    openInWorkbench = true,
    onNavigate,
    onRenderFailure,
    filterModes,
    groupingMode,
    pageValues,
    onFiltersChange,
    onTabChange,
    optionsFor,
  } = props;
  const runtime = opened as DashboardRuntime;
  const state = useViewRuntime(runtime);
  const dashboard = useDashboard(runtime);
  const messages = useViewMessages();
  const commands = useSaveCommands(engine, runtime);
  const reads = interaction !== 'read-only';

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

  // What the filters hold and the tab on screen, told to the host as they
  // change — the board opening included — for its address.
  // The reader's alone: what the page holds is its own, and would come back
  // from the address as the reader's.
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
  useEffect(() => {
    onFiltersChange?.(readerValues);
  }, [readerValues, onFiltersChange]);
  const shownTab = dashboard.tab;
  useEffect(() => {
    onTabChange?.(shownTab);
  }, [shownTab, onTabChange]);

  // Building (D22 A), in the editable tier and for whoever may save the
  // board: 「编辑」 in the embed's first row, the edit bar over the board,
  // 「完成」 saving through the same commands the workbench's title bar has.
  // The state is the runtime's, so the board's timer waits on it (D26 Q39).
  const { building, setBuilding } = dashboard;
  const canEdit = interaction === 'editable' && commands.can.save;
  const editing = canEdit && building;
  const editButton = useRef<HTMLButtonElement>(null);
  const wasEditing = useRef(editing);
  // The keyboard that pressed 完成 or 取消 goes back to 「编辑」, which comes
  // back as the bar goes — only when it was lost with the bar.
  useLayoutEffect(() => {
    const ended = wasEditing.current && !editing;
    wasEditing.current = editing;
    if (!ended) return;
    const active = document.activeElement;
    if (active === null || active === document.body)
      editButton.current?.focus();
  }, [editing]);
  // A board being built holds a draft nothing else keeps: closing the tab
  // on it is asked about, as in the workbench. The host's own navigation
  // does not come through here — it unmounts the embed.
  useLeaveGuard(
    editing && state ? { dirty: state.dirty, write: state.write } : null,
  );

  // The building's extensions — the new analysis, a panel's own look,
  // 另存为视图 and the tab bar — are the workbench's; only the tab bar is
  // read outside building.
  const tabBar = (
    <DashboardTabs dashboard={dashboard} editing={editing ? runtime : null} />
  );
  const { extensions, dialogs } = useDashboardExtensions({
    engine,
    board: runtime,
    dashboard,
    messages,
    optionsFor,
    tabBar,
  });

  const panelLevel = (
    withTitle ? Math.min(headingLevel + 1, 6) : headingLevel
  ) as PanelHeadingLevel;
  const reading: BoardReading = {
    headingLevel: panelLevel,
    panelTitles: withPanelTitles,
    readOnly: !reads,
    openInWorkbench,
    panelExport: withExport,
    filterModes: modes,
  };
  const title = state?.title ?? '';

  // A panel's findings are the panel's to say, each in its own frame — its
  // errors as much as its warnings: one panel's error stops that panel and
  // nothing else, and taking it for the board's drew no grid around a board
  // where every other panel was fine (R3). "Too many panels" sits at
  // `['panels']` itself and is no one panel's: it stops the whole board.
  const issues = (state?.issues ?? []).filter(
    found => found.path[0] !== 'panels' || typeof found.path[1] !== 'number',
  );
  const errors = issues.filter(found => found.severity === 'error');
  const warnings = issues.filter(found => found.severity !== 'error');

  return (
    <>
      <EmbedHead
        title={withTitle ? title : undefined}
        headingLevel={headingLevel}
      >
        {canEdit && !editing && (
          <Button
            ref={editButton}
            data-slot="dashboard-edit"
            variant="outline"
            size="sm"
            onClick={() => setBuilding(true)}
          >
            <PencilIcon data-icon="inline-start" />
            {messages.label('label.dashboard.edit')}
          </Button>
        )}
      </EmbedHead>
      {refused.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.scope.refused')}</AlertTitle>
          <AlertDescription>{messages.issues(refused)}</AlertDescription>
        </Alert>
      )}
      {canEdit && <WriteOutcome commands={commands} title={title} />}
      {errors.length > 0 && <ErrorStrip issues={errors} />}
      <WarningStrip issues={warnings} />
      {errors.length === 0 && (
        <DashboardEditExtensionsContext.Provider
          value={editing ? extensions : { tabBar }}
        >
          <DashboardBoard
            engine={engine}
            dashboard={dashboard}
            commands={commands}
            title={title}
            shared={state !== null && audienceOf(state.scope) === 'shared'}
            canEdit={canEdit}
            editing={editing}
            onEditingChange={setBuilding}
            onNavigate={reads ? onNavigate : undefined}
            onRenderFailure={onRenderFailure}
            reading={reading}
          />
          {canEdit && dialogs}
        </DashboardEditExtensionsContext.Provider>
      )}
    </>
  );
}
