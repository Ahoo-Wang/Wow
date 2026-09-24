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

import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  isContentPanel,
  referencedInstance,
  type NewContentPanel,
} from '../../dashboard/index.js';
import type { FilterSummaryItem } from '../../filter/index.js';
import type { Issue, ViewInstance } from '../../model/index.js';
import type { DashboardController, SaveCommands } from '../../react/index.js';
import type { ViewNavigation, ViewEngine } from '../../runtime/index.js';
import { SurfaceAnnouncer, useSurfaceAnnouncer } from '../Announcer.js';
import { DashboardGrid, type DashboardGridProps } from '../DashboardGrid.js';
import { panelNames } from '../DashboardPanel.js';
import { useViewMessages } from '../MessagesProvider.js';
import type { RenderFailureHandler } from '../RenderBoundary.js';
import { EmptyBoardActions, type AddChoice } from './AddMenu.js';
import { ClickSettings } from './ClickSettings.js';
import { BoardBuildingContext, type BoardBuilding } from './commands.js';
import {
  ContentEditor,
  type ContentTarget,
  type EditedKind,
} from './ContentEditor.js';
import { EditBar } from './EditBar.js';
import { useBoardHistory } from './history.js';
import { useDashboardEditExtensions } from './extensions.js';
import { useBoardFilters } from './BoardFilters.js';
import { ViewPicker, type PickerIntent } from './ViewPicker.js';

/** Pixel height of one grid row, and the gap the grid keeps between rows. */
const ROW_HEIGHT = 80;
const ROW_GAP = 10;

export interface DashboardBoardProps {
  engine: ViewEngine;
  dashboard: DashboardController;
  /** The board's save commands: 保存 is their save. */
  commands: SaveCommands;
  /** The board's title and whether others read it, for the questions asked. */
  title: string;
  shared: boolean;
  /** Whether this reader may build the board — the save permission. */
  canEdit: boolean;
  /** Whether the board is being built right now. */
  editing: boolean;
  onEditingChange(editing: boolean): void;
  onSaved?(instance: ViewInstance): void;
  onNavigate?(to: ViewNavigation): void;
  onRenderFailure?: RenderFailureHandler;
  /**
   * What the board refused of the filters it opened on — a host's address
   * gone partly stale (`DashboardRuntime.refusedFilters`) — said once over
   * the filter bar (`FiltersRefused`).
   */
  refusedFilters?: readonly Issue[];
  /**
   * The board's fixed scope in force (D26 Q31, `FilterEditorController.fixed`),
   * read-only on the filter bar's row as 「固定范围」 (D27).
   */
  fixed?: readonly FilterSummaryItem[];
  /**
   * How a page that embeds the board reads it (D22): the grid's switches —
   * the panels' heading level and titles, whether the board is only read,
   * whether 在工作台中打开 is offered — and each filter's mode. The
   * workbench's reading when left out.
   */
  reading?: BoardReading;
}

/** How a page that embeds a board reads it; see `DashboardBoardProps.reading`. */
export type BoardReading = Pick<
  DashboardGridProps,
  | 'headingLevel'
  | 'panelTitles'
  | 'readOnly'
  | 'openInWorkbench'
  | 'panelExport'
  | 'filterModes'
>;

/**
 * The board and its building (D22 A, B, D): the edit bar over the grid while
 * it is built, the first things to add on an empty one, and the two dialogs
 * a panel is chosen or written in. Every edit is one of the runtime's
 * `DashboardEditing` commands, written into the draft and onto the screen
 * at once — the panels run on it as the author works — and only 保存 saves.
 */
export function DashboardBoard({
  engine,
  dashboard,
  commands,
  title,
  shared,
  canEdit,
  editing,
  onEditingChange,
  onSaved,
  onNavigate,
  onRenderFailure,
  refusedFilters,
  fixed,
  reading,
}: DashboardBoardProps) {
  const messages = useViewMessages();
  const extensions = useDashboardEditExtensions();
  const edit = dashboard.edit;
  // The board's commands as they stand now, for a gesture that finishes
  // later than it started — a view picked and still loading (Q-02): 取消
  // pressed meanwhile ended the building, and another board may be open.
  const editNow = useRef(editing ? edit : null);
  useLayoutEffect(() => {
    editNow.current = editing ? edit : null;
  }, [editing, edit]);
  // What each dialog was last opened for, kept while it closes so its
  // words and its way back stay put through the closing.
  const [picker, setPicker] = useState<Opened<PickerIntent> | null>(null);
  const [content, setContent] = useState<Opened<ContentTarget> | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [clicking, setClicking] = useState<Opened<string> | null>(null);
  // What the last edit did, said once: a panel that appears or goes is seen
  // by a pointer and heard by nobody else. The board's one voice, handed
  // to its grid, tabs and filters (`SurfaceAnnouncer`) — the workbench's
  // when the board is drawn in one.
  const { say, region } = useSurfaceAnnouncer('dashboard-announcement');
  const gridRef = useRef<HTMLDivElement>(null);
  const landingRef = useRef<HTMLParagraphElement>(null);
  const addRef = useRef<HTMLButtonElement>(null);
  const undoRef = useRef<HTMLButtonElement>(null);
  const redoRef = useRef<HTMLButtonElement>(null);

  // 「编辑」 goes from the title bar as the building starts, and the keyboard
  // that pressed it with it: the bar that took its place is where it lands.
  // Not when something else already took it — an empty board's first step
  // opens a dialog, or a heading's name in place.
  useLayoutEffect(() => {
    if (!editing) return;
    const active = document.activeElement;
    if (active === null || active === document.body)
      landingRef.current?.focus();
  }, [editing]);

  const filters = useBoardFilters({
    dashboard,
    editing,
    say,
    modes: reading?.filterModes,
    refused: refusedFilters,
    fixed,
  });
  const names = panelNames(dashboard.panels, messages);
  const history = useBoardHistory({
    dashboard,
    names,
    messages,
    editing,
    say,
    board: gridRef,
    undoRef,
    redoRef,
    landing: landingRef,
  });
  const onBoard = useMemo(
    () =>
      new Set(
        dashboard.panels.flatMap(
          panel => referencedInstance(panel.panel) ?? [],
        ),
      ),
    [dashboard.panels],
  );

  /**
   * Where a new panel goes (D22 A): the first free place on the tab on
   * screen, from the first grid row the reader can see — asked as the add
   * happens, since the page scrolls in between.
   */
  const spot = () => ({
    fromRow: firstVisibleRow(gridRef.current),
    ...(dashboard.tab === null ? {} : { tab: dashboard.tab }),
  });
  const added = (id: string | null, fallback: string) => {
    if (id === null) return;
    say(messages.label('label.dashboard.added', { title: fallback }));
  };

  const add = (choice: AddChoice) => {
    if (!edit) return;
    // An empty board's first steps start the building with them.
    if (!editing) onEditingChange(true);
    switch (choice) {
      case 'saved-view':
        setPicker({ what: { mode: 'add' }, open: true });
        return;
      case 'new-analysis':
        extensions.onAddOwnedAnalysis?.(spot());
        return;
      case 'heading': {
        const words = messages.label('label.dashboard.new-heading');
        const id = edit.addPanel({ kind: 'heading', content: words }, spot());
        added(id, words);
        // Named in place at once: a new section is there to be named.
        if (id !== null) setRenaming(id);
        return;
      }
      default:
        setContent({ what: { mode: 'add', kind: choice }, open: true });
    }
  };
  const canCreate = extensions.onAddOwnedAnalysis !== undefined;

  /**
   * The control a dialog hands the keyboard back to (`FinalFocus`): the
   * panel's 「⋯」 when a panel asked, the edit bar's 「＋ 添加」 otherwise.
   */
  const returnTo = (panelId: string | null): HTMLElement | boolean => {
    const menu =
      panelId === null
        ? null
        : [
            ...(gridRef.current?.querySelectorAll<HTMLElement>(
              '[data-panel-id]',
            ) ?? []),
          ]
            .find(item => item.dataset.panelId === panelId)
            ?.querySelector<HTMLElement>('[data-slot="panel-menu"]');
    return menu ?? addRef.current ?? true;
  };

  const building: BoardBuilding = {
    renaming,
    rename: setRenaming,
    replace: panelId =>
      setPicker({
        what: { mode: 'replace', panelId, title: names.get(panelId) ?? '' },
        open: true,
      }),
    editContent: panelId => {
      const panel = dashboard.panels.find(entry => entry.id === panelId)?.panel;
      if (panel && isContentPanel(panel) && panel.kind !== 'heading')
        setContent({ what: { mode: 'edit', panelId, panel }, open: true });
    },
    // Gone at once, and 「撤销」 brings it back: the keyboard, whose control
    // went with the panel, lands there.
    removed: name => {
      say(messages.label('label.dashboard.removed', { title: name }));
      history.land();
    },
    duplicated: name =>
      say(messages.label('label.dashboard.duplicated', { title: name })),
    moved: (name, tab) =>
      say(messages.label('label.panel.moved-to-tab', { title: name, tab })),
    click: panelId => setClicking({ what: panelId, open: true }),
  };

  return filters.wrap(
    <SurfaceAnnouncer say={say}>
      <BoardBuildingContext.Provider value={building}>
        {/* The board's own undo keys: a key press inside a dialog the board
          opened bubbles here through React, but is not on the board. */}
        <div
          ref={gridRef}
          className="flex flex-col gap-3"
          onKeyDown={history.onKeyDown}
        >
          <DashboardGrid
            {...reading}
            dashboard={dashboard}
            editable={editing}
            rowHeight={ROW_HEIGHT}
            onRenderFailure={onRenderFailure}
            onNavigate={onNavigate}
            header={({ narrow }) => (
              <>
                {/* The filters over everything else: they are what the whole
                  board is read under, every tab alike (D22 E, F). */}
                {filters.bar(narrow)}
                {editing && (
                  <EditBar
                    commands={commands}
                    title={title}
                    narrow={narrow}
                    onLeave={() => {
                      setRenaming(null);
                      onEditingChange(false);
                    }}
                    onSaved={onSaved}
                    landingRef={landingRef}
                    addRef={addRef}
                    add={add}
                    canCreate={canCreate}
                    addFilter={filters.add}
                    history={history}
                    undoRef={undoRef}
                    redoRef={redoRef}
                  />
                )}
                {filters.wiring}
                {/* Under the edit bar and over the panels: the tabs belong to
                  what is read, the bar to how it is built. */}
                {extensions.tabBar}
              </>
            )}
            emptyActions={
              canEdit && edit ? (
                <EmptyBoardActions add={add} canCreate={canCreate} />
              ) : undefined
            }
          />
        </div>
        {region}
        <ViewPicker
          engine={engine}
          intent={picker?.what ?? null}
          open={picker?.open ?? false}
          onClose={() => setPicker(closing)}
          finalFocus={() =>
            returnTo(
              picker?.what.mode === 'replace' ? picker.what.panelId : null,
            )
          }
          onBoard={onBoard}
          shared={shared}
          onPick={view => {
            const intent = picker?.what;
            if (!edit || !intent) return;
            if (intent.mode === 'replace') {
              edit.replacePanelView(intent.panelId, view.id);
              return;
            }
            // Loaded first, so the panel starts at the size of what it shows
            // — a metric card a quarter, a table the full width — and the
            // place is asked once it is known how big it is — of the board
            // as it is by then, which may no longer be built.
            void dashboard.preload(view.id).then(() => {
              const current = editNow.current;
              if (!current) return;
              added(
                current.addPanel({ kind: 'view', instanceId: view.id }, spot()),
                view.title,
              );
            });
          }}
        />
        <ClickSettings
          engine={engine}
          dashboard={dashboard}
          panel={
            dashboard.panels.find(panel => panel.id === clicking?.what) ?? null
          }
          name={names.get(clicking?.what ?? '') ?? ''}
          open={clicking?.open ?? false}
          onClose={() => setClicking(closing)}
          finalFocus={() => returnTo(clicking?.what ?? null)}
          routed={onNavigate !== undefined}
        />
        <ContentEditor
          target={content?.what ?? null}
          open={content?.open ?? false}
          onClose={() => setContent(closing)}
          finalFocus={() =>
            returnTo(
              content?.what.mode === 'edit' ? content.what.panelId : null,
            )
          }
          onSubmit={next => {
            const target = content?.what;
            if (!edit || !target) return;
            if (target.mode === 'add') {
              added(
                edit.addPanel(next, spot()),
                next.title ?? messages.label(KIND_NAMES[next.kind]),
              );
              return;
            }
            // One edit, title and all: one step for 「撤销」 to take back.
            const { title: renamed, ...held } = next;
            edit.editPanelContent(target.panelId, {
              ...changed(target.panel, held),
              title: renamed ?? '',
            });
          }}
        />
      </BoardBuildingContext.Provider>
    </SurfaceAnnouncer>,
  );
}

/** A dialog's reason for opening, and whether it is open still. */
interface Opened<T> {
  what: T;
  open: boolean;
}

/** The same dialog, closing: what it was for stays until it has gone. */
function closing<T>(opened: Opened<T> | null): Opened<T> | null {
  return opened && { ...opened, open: false };
}

const KIND_NAMES = {
  markdown: 'label.panel.kind.markdown',
  image: 'label.panel.kind.image',
  links: 'label.panel.kind.links',
} as const satisfies Record<EditedKind, string>;

/**
 * What an edit changes of a panel: the members the form left set, and a
 * member it cleared as `undefined` — only where the panel had one, so an
 * untouched optional member does not turn up as a key with nothing in it.
 */
function changed(
  panel: object,
  next: Omit<NewContentPanel, 'title'>,
): Partial<NewContentPanel> {
  return Object.fromEntries(
    Object.entries(next).filter(
      ([key, value]) => value !== undefined || key in panel,
    ),
  );
}

/**
 * The first grid row the reader can see: how far the grid's top has gone
 * above the viewport, in rows. Zero while the grid starts on screen, and
 * wherever the grid cannot be measured.
 */
function firstVisibleRow(board: HTMLElement | null): number {
  const grid = board?.querySelector(
    '[data-slot="dashboard-grid"] .react-grid-layout',
  );
  if (!grid) return 0;
  const above = -grid.getBoundingClientRect().top;
  return above > 0 ? Math.floor(above / (ROW_HEIGHT + ROW_GAP)) : 0;
}
