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

import { useLayoutEffect, useState, type ReactNode, type Ref } from 'react';
import { cn } from 'cn';
import GridLayout, {
  getBreakpointFromWidth,
  useContainerWidth,
  type Layout,
  type ResizeHandleAxis,
} from 'react-grid-layout';
import { LayoutDashboardIcon } from 'lucide-react';
import {
  arrangePanel,
  placePanel,
  readingOrder,
  stackedLayout,
  type ArrangeStep,
  type OrderStep,
  type PlacedPanel,
} from '../dashboard/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../react/index.js';
import type { DashboardWidth } from '../model/index.js';
import type { ViewNavigation } from '../runtime/index.js';
import { useSurfaceAnnouncer } from './Announcer.js';
import { PanelGridItem, PanelResizeHandle } from './DashboardArrange.js';
import { gridBlocks } from './dashboard/gridBlocks.js';
import {
  DashboardPanel,
  panelNames,
  type PanelHeadingLevel,
} from './DashboardPanel.js';
import {
  panelCommands,
  readerCommands,
  useBoardBuilding,
} from './dashboard/commands.js';
import { useDashboardEditExtensions } from './dashboard/extensions.js';
import { tabTitle } from './dashboard/DashboardTabs.js';
import { useGridPlacement } from './gridPlacement.js';
import { FIXED_BOARD_WIDTH } from './layout.js';
import type { RenderFailureHandler } from './RenderBoundary.js';
import { useViewMessages, type MessageFormatters } from './MessagesProvider.js';
import type { MessageKey } from './messages.js';
import { PanelWiring, useFilterWiring } from './dashboard/FilterWiring.js';
import { panelPress } from './dashboard/press.js';
import type { RecordPanelHost } from './dashboard/PanelBodies.js';
import {
  filterModeOf,
  type BoardFilterModes,
} from './dashboard/filterModes.js';
import {
  Empty,
  EmptyContent,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  EmptyDescription,
} from './components/empty.js';

export interface DashboardGridProps {
  dashboard: DashboardController;
  /**
   * Whether the board is being built (D22 A): panels may be dragged,
   * resized and placed by keyboard, and each panel's menu offers 「改」.
   * Read-only by default — nothing on a board being read moves it.
   */
  editable?: boolean;
  /** Pixel height of one grid row. */
  rowHeight?: number;
  /** Told when one panel's body fails to draw; the others keep drawing. */
  onRenderFailure?: RenderFailureHandler;
  /**
   * The heading level of each panel's title: one under whatever names the
   * board. `3` under a workbench's own `h2`; an embed passes what its host's
   * page calls for, since only the host knows its outline.
   */
  headingLevel?: PanelHeadingLevel;
  /**
   * Above the panels, inside the grid's own column: the edit bar while the
   * board is built. Told whether the board is in its one-column reading,
   * where building is renaming, removing and reordering alone (D22 J).
   */
  header?(board: { narrow: boolean }): ReactNode;
  /**
   * Under the empty board's words: the first things to add (D22 A). Left
   * out, an empty board says it has no panels and offers nothing — a reader
   * who cannot build it has nothing to press.
   */
  emptyActions?: ReactNode;
  /**
   * The host's route (`ViewNavigation`): 在工作台中打开 in a panel's
   * menu, the follow-up menu on a group, a panel's custom destination. No
   * route, none of them — a panel that cross-filters still does.
   */
  onNavigate?(to: ViewNavigation): void;
  /**
   * Whether the board is only read (off by default): nothing on it answers
   * — no press on a group (no follow-up menu, no cross-filtering, no
   * destination), no header sort or width handle on a record panel, no
   * 「⋯」 on a panel, no retry on one that failed; the
   * board re-runs on its own timer. An embed's static tier (D22, D36), a
   * wall screen.
   */
  readOnly?: boolean;
  /**
   * Whether a panel's 「⋯」 offers 在工作台中打开 — the view behind it — when
   * there is a route (on by default).
   */
  openInWorkbench?: boolean;
  /**
   * Whether a record panel's 「⋯」 offers 导出数据… — the export window over
   * its rows (D14). On by default, and off by default on a board that is
   * only read, which offers nothing unless told to: an embed's static
   * tier with its export switched on has a 「⋯」 holding that item alone
   * (D24 Q24).
   */
  panelExport?: boolean;
  /** Whether panel titles are drawn (on by default; `DashboardPanel.titled`). */
  panelTitles?: boolean;
  /**
   * How an embedding page offers each filter: a panel says nothing of a
   * filter the page hid (「不受…影响」 would name what the reader cannot
   * see).
   */
  filterModes?: BoardFilterModes;
  /**
   * The host's commands on each record panel (D39) — its row and bulk
   * slots, as a record workbench takes them, and its bulk command — asked
   * per panel, so a host puts 「催发货」 on the orders and nothing on the
   * rest. The host's code runs them; the board writes nothing (D36).
   */
  recordPanel?(panel: DashboardPanelView): RecordPanelHost | undefined;
  className?: string;
}

export type { PanelHeadingLevel } from './DashboardPanel.js';

/**
 * The panels, placed.
 *
 * The grid library draws the gesture and nothing else: where a moved or
 * resized panel lands, and what it pushes out of the way, is the kernel's
 * (`useGridPlacement`), the drop reaches `dashboard.place`, which applies that
 * placement alone, and everything inside a panel is the same component a
 * full view would use.
 *
 * Only a gesture writes geometry back. The library also reports a layout it
 * computed itself — on mount, and whenever the props change — and applying
 * that would dirty a dashboard nobody touched, so those reports are not
 * listened to. Panels sit where the config put them, not where a compactor
 * would move them: the kernel admits `layout` as authored, and the screen
 * must show what was admitted.
 */
export function DashboardGrid({
  dashboard,
  editable = false,
  rowHeight = 80,
  className,
  onRenderFailure,
  headingLevel = 3,
  header,
  emptyActions,
  onNavigate,
  readOnly = false,
  openInWorkbench = true,
  panelExport = !readOnly,
  panelTitles = true,
  filterModes,
  recordPanel,
}: DashboardGridProps) {
  // The grid needs a pixel width and the container only knows it once it is
  // on screen; `useGridWidth` measures it before the first paint.
  const { containerRef, width, measured } = useGridWidth(dashboard.width);
  const messages = useViewMessages();
  // What the last keyboard command did, said once. A pointer sees the panel
  // move under it; a keyboard has only the layout, which is not on screen,
  // so the new place is read out. A press on a panel's group says what it
  // did here too — the board's filter set or cleared, a destination that
  // cannot be opened (D22 I). It starts empty, so opening a dashboard
  // announces nothing. The board's voice where the grid is drawn in one
  // (`SurfaceAnnouncer`), a region of its own otherwise.
  const { say, region } = useSurfaceAnnouncer('dashboard-grid-announcement');
  // The last width the container could be drawn at. A hidden container
  // measures 0, and that is not a phone: the grid keeps the panels at their
  // last width then, and the breakpoint stays where it was with them.
  const [drawnWidth, setDrawnWidth] = useState(width);
  if (width > 0 && width !== drawnWidth) setDrawnWidth(width);
  // A column too narrow to hold a panel gives up its grid for one column —
  // chosen by the library's own breakpoint reading, over the width the grid
  // is laid out at, not by a media query on the panels.
  const narrow = getBreakpointFromWidth(BREAKPOINTS, drawnWidth) === 'narrow';
  // One column is a reading of the layout, not a layout: nothing placed in
  // it could be written back, so neither the gestures nor the keyboard
  // commands are on offer there.
  const arranging = editable && !narrow;
  const placement = useGridPlacement(dashboard.place);
  const building = useBoardBuilding();
  const extensions = useDashboardEditExtensions();
  const wiring = useFilterWiring();

  // The tab on screen (D22 E) is the grid: its panels alone are drawn, and
  // every placement is judged among them — another tab is another grid. It
  // is the runtime's, since only that tab runs (`DashboardRuntime.showTab`);
  // a board without tabs has every panel on screen.
  const onScreen = dashboard.panels.filter(
    panel => panel.tab === dashboard.tab,
  );
  const at = dashboard.tabs.findIndex(entry => entry.id === dashboard.tab);
  const shownTab =
    !editable && dashboard.tabs.length > 1 && at >= 0
      ? tabTitle(dashboard.tabs[at], at, messages)
      : null;

  // Reading order — rows top to bottom, each left to right — over the layout
  // as stored. The panels are rendered in it too, so the tab order follows
  // what is on screen rather than the order the config happens to list them.
  const panels = readingOrder(onScreen);
  const byId = new Map(onScreen.map(panel => [panel.id, panel]));
  const names = panelNames(dashboard.panels, messages);

  // The board as the kernel places it: a keyboard command is judged against
  // every panel, since on a board that floats panels up "down" means past
  // the panel below, and the bottom of a column has nowhere down to go.
  const placed: PlacedPanel[] = onScreen.map(panel => ({
    id: panel.id,
    ...panel.layout,
  }));
  /**
   * One keyboard command: the same placement a gesture lands. Answers
   * whether the panel went anywhere — against an edge, or at the bottom of
   * its column on a board that floats panels up, it does not.
   */
  const arrange = (panelId: string, step: ArrangeStep): boolean => {
    // The one-column reading is never a placement to write back.
    if (!byId.has(panelId) || !arranging) return false;
    const target = arrangePanel(placed, panelId, step, dashboard.columns);
    if (!target) return false;
    dashboard.place(panelId, target);
    // Where it came to rest, which is what the reader is told — the target
    // is only where it was put down before its tab floated up.
    const next =
      placePanel(placed, panelId, target, dashboard.columns)?.find(
        box => box.id === panelId,
      ) ?? target;
    say(
      messages.label('label.panel.placed', {
        title: names.get(panelId) ?? '',
        // Said as a reader counts them, from one.
        column: next.x + 1,
        row: next.y + 1,
        width: count(messages, next.w, COLUMNS),
        height: count(messages, next.h, ROWS),
      }),
    );
    return true;
  };

  /** Escape on a handle: every step of that arranging taken back. */
  const cancelArrange = (steps: number) => {
    for (let step = 0; step < steps; step += 1) dashboard.edit?.undo();
  };

  /**
   * One step along the one-column reading (D22 J), written back onto the
   * grid by the runtime (`reorderPanel`), and where the panel came to said:
   * the column is the order, so its place in it is the whole answer.
   */
  const reorder = (panelId: string, step: OrderStep) => {
    const at = panels.findIndex(panel => panel.id === panelId);
    const to = step === 'up' ? at - 1 : at + 1;
    if (!editable || !narrow || at < 0 || to < 0 || to >= panels.length) return;
    dashboard.edit?.reorderPanel(panelId, step);
    say(
      messages.label('label.panel.reordered', {
        title: names.get(panelId) ?? '',
        index: to + 1,
        total: panels.length,
      }),
    );
  };

  // Below `md`, the kernel's one-column reading of the stored layout; the
  // stored layout itself everywhere else.
  const boxes = panels.map(panel => ({ id: panel.id, ...panel.layout }));
  const layout: Layout = (narrow ? stackedLayout(boxes) : boxes).map(
    ({ id, ...box }) => ({ i: id, ...box }),
  );

  // A fixed-width board (D31) is held to one width and centred: its filters,
  // its edit bar and its tabs with its panels, since they are one board.
  const fixed = dashboard.width === 'fixed';
  // The cells, drawn while the board is built and only where a panel can be
  // placed on them: not in the one-column reading, not over an empty tab.
  const blocks =
    arranging && measured && panels.length > 0
      ? gridBlocks({ width: drawnWidth, cols: dashboard.columns, rowHeight })
      : undefined;

  return (
    <div
      ref={containerRef}
      data-slot="dashboard-grid"
      data-narrow={narrow || undefined}
      data-editing={editable || undefined}
      data-width={dashboard.width}
      className={cn(
        'flex w-full flex-col gap-3',
        fixed && 'mx-auto',
        className,
      )}
      style={fixed ? { maxWidth: FIXED_BOARD_WIDTH } : undefined}
    >
      {header?.({ narrow })}
      {/* The tab on screen's panel, while a tab bar is read above it: the
          bar is a `tablist`, and what it switches is named after the tab it
          shows. Being built, the bar is a list to arrange and this a grid. */}
      <div
        data-slot="dashboard-tab-panel"
        // It holds the grid alone, at the grid's width and height, so the
        // blocks are one layer inside it, under the panels.
        className={cn('flex min-w-0 flex-col', blocks && 'relative isolate')}
        {...(shownTab === null
          ? {}
          : { role: 'tabpanel', 'aria-label': shownTab })}
      >
        {blocks && (
          // One drawing of every cell (`styles.css`,
          // `dashboard-grid-blocks`): never pressed, never read out.
          <div
            data-slot="dashboard-grid-blocks"
            aria-hidden="true"
            style={blocks}
          />
        )}
        {!measured ? null : panels.length === 0 ? (
          // A tab with nothing on it, on a board with panels elsewhere, says
          // so of the tab: 「这个仪表盘还没有面板」 would be untrue one tab away.
          <DashboardEmpty tab={dashboard.panels.length > 0}>
            {emptyActions}
          </DashboardEmpty>
        ) : (
          <GridLayout
            // A new grid on either side of the breakpoint: the library holds the
            // layout in state of its own and catches up with a new one an effect
            // later, so for one frame it drew the wide layout's six-column panels
            // in a one-column grid — six times the width of the screen.
            key={narrow ? 'narrow' : 'wide'}
            // The last width it could be drawn at: a container measured 0
            // before it was ever drawn is laid out at the starting width.
            width={drawnWidth}
            layout={layout}
            gridConfig={{ cols: narrow ? 1 : dashboard.columns, rowHeight }}
            // Dragging by the header alone leaves the panel body clickable.
            dragConfig={{
              enabled: arranging,
              handle: '[data-slot="panel-grip"]',
            }}
            resizeConfig={{
              enabled: arranging,
              // The corner is a named, focusable control while the layout may
              // be edited, and nothing at all while it may not — upstream's
              // bare `span` has no name to give and no key to answer.
              handleComponent: (
                axis: ResizeHandleAxis,
                ref: Ref<HTMLElement>,
              ) =>
                arranging ? (
                  <PanelResizeHandle axis={axis} ref={ref} onStep={arrange} />
                ) : (
                  <span
                    ref={ref}
                    aria-hidden="true"
                    className={`react-resizable-handle react-resizable-handle-${axis}`}
                  />
                ),
            }}
            compactor={placement.compactor}
            // The gestures are off in the one-column reading, and so are the
            // callbacks that would hand a placement to `place`: nothing drawn in
            // that column is a layout the config could hold.
            {...(arranging
              ? {
                  onDragStart: placement.onDragStart,
                  onDragStop: placement.onDragStop,
                  onResizeStart: placement.onResizeStart,
                  onResizeStop: placement.onResizeStop,
                }
              : {})}
          >
            {panels.map(panel => (
              // The library appends the resize corner to this item, after the
              // panel; the item says which panel it holds to whatever lands in
              // it, so the corner is named after its own panel.
              <PanelGridItem
                key={panel.id}
                panelId={panel.id}
                name={names.get(panel.id) ?? ''}
                say={say}
                className="min-h-0"
              >
                <DashboardPanel
                  panel={panel}
                  name={names.get(panel.id)}
                  headingLevel={headingLevel}
                  titled={panelTitles}
                  editable={arranging}
                  onArrange={step => arrange(panel.id, step)}
                  onArrangeCancel={cancelArrange}
                  order={
                    editable && narrow && panels.length > 1
                      ? {
                          index: panels.indexOf(panel),
                          total: panels.length,
                          onMove: step => reorder(panel.id, step),
                        }
                      : undefined
                  }
                  onRetry={
                    readOnly
                      ? undefined
                      : () => dashboard.refreshPanel(panel.id)
                  }
                  readOnly={readOnly}
                  press={
                    readOnly
                      ? undefined
                      : panelPress(panel, dashboard, onNavigate, say)
                  }
                  pressesFilter={
                    readOnly ? undefined : pressedFilter(panel, dashboard)
                  }
                  commands={
                    readOnly
                      ? readerCommands(panel, panelExport)
                      : panelCommands({
                          panel,
                          name: names.get(panel.id) ?? '',
                          dashboard,
                          building,
                          editing: editable,
                          narrow,
                          extensions,
                          onNavigate: openInWorkbench ? onNavigate : undefined,
                          exports: panelExport,
                          messages,
                        })
                  }
                  unreached={unreachedBy(panel, dashboard, filterModes)}
                  record={recordPanel?.(panel)}
                  footer={
                    wiring &&
                    editable && (
                      <PanelWiring
                        panel={panel}
                        name={names.get(panel.id) ?? ''}
                        wiring={wiring}
                      />
                    )
                  }
                  onRenderFailure={onRenderFailure}
                />
              </PanelGridItem>
            ))}
          </GridLayout>
        )}
      </div>
      {/* One region for the whole grid rather than one per panel: only one
          panel is ever being placed, and the rest would be a dozen empty
          regions for a reader to walk past. */}
      {region}
    </div>
  );
}

/**
 * The grid's width, known before the first paint.
 *
 * The measuring is `react-grid-layout`'s own `useContainerWidth` rather than
 * a second `ResizeObserver` written here: it is the same observer the grid
 * would have used, it survives an environment without `ResizeObserver`, and
 * it coalesces a burst of resizes into one frame. But it starts at 1280px
 * and first measures in a passive effect, so the grid was first handed the
 * wide 24-column layout at 1280px — on a phone too — and the library drew it
 * (in percentages, until it has mounted) until that effect ran. React runs
 * passive effects after the commit, and the browser may paint in between
 * when the commit's task ran long, as a big board's does on a slow phone.
 *
 * So the grid is measured in a layout effect, whose update React applies
 * before it paints, and the panels are not drawn until it has been: no
 * layout is ever handed to the library at a width it does not have, and
 * the first frame is the right one whatever the scheduling. On the server,
 * where no effect runs, the panels are left out rather than drawn at a
 * guessed width (the client's first render matches, so hydration does
 * too); where the container measures 0 — hidden, or a DOM without layout —
 * the library's starting width stands, as it always did.
 *
 * The same goes for the board's own width switched (D31, `boardWidth`): the
 * container is narrower or wider at once, and is measured before that paints
 * rather than a frame after, with the panels spilling out of it meanwhile.
 */
function useGridWidth(boardWidth: DashboardWidth) {
  // `mounted` is the library's word for "measured at least once".
  const { containerRef, width, mounted, measureWidth } = useContainerWidth({
    measureBeforeMount: true,
  });
  useLayoutEffect(() => measureWidth(), [measureWidth, boardWidth]);
  return { containerRef, width, measured: mounted };
}

/**
 * Where the grid gives up its columns: a container narrower than Tailwind's
 * `md` (768px) lays the panels out in one. `getBreakpointFromWidth` picks the
 * widest breakpoint the width is *above*, so `wide` starts at 767 for 768 to
 * be the first width that has it.
 */
const BREAKPOINTS = { narrow: 0, wide: 767 } as const;

/**
 * A count said with its noun: 「1 column」, never 「1 columns」. The package
 * spells the singular as a key of its own rather than inflecting a word.
 */
function count(
  messages: MessageFormatters,
  value: number,
  [many, one]: readonly [MessageKey, MessageKey],
): string {
  return value === 1
    ? messages.label(one)
    : messages.label(many, { count: value });
}

const COLUMNS = ['label.panel.columns', 'label.panel.columns-one'] as const;
const ROWS = ['label.panel.rows', 'label.panel.rows-one'] as const;

/**
 * A dashboard with nothing on it: what a dashboard is, that this one holds
 * no panel yet, and — for whoever may build it — the first things to add,
 * as an `EmptyContent` under the header (D22 A). A reader who may not build
 * it is offered nothing: an entry they cannot use is a door that is not
 * there (U1).
 */
function DashboardEmpty({
  tab = false,
  children,
}: {
  /** Whether it is one tab that is empty, rather than the whole board. */
  tab?: boolean;
  children?: ReactNode;
}) {
  const messages = useViewMessages();
  return (
    <Empty data-slot="dashboard-empty" data-tab={tab || undefined}>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <LayoutDashboardIcon />
        </EmptyMedia>
        <EmptyTitle>
          {messages.label(tab ? 'label.tabs.empty' : 'label.dashboard.empty')}
        </EmptyTitle>
        <EmptyDescription>
          {messages.label(
            tab ? 'label.tabs.empty-hint' : 'label.dashboard.empty-hint',
          )}
        </EmptyDescription>
      </EmptyHeader>
      {children != null && <EmptyContent>{children}</EmptyContent>}
    </Empty>
  );
}

/**
 * The names of the board's filters that hold a value and do not reach one
 * panel (D22 F「不受此筛选影响」). A filter holding nothing narrows no panel,
 * so it is not said; a panel whose view is not known yet has no answer.
 */
function unreachedBy(
  panel: DashboardPanelView,
  dashboard: DashboardController,
  modes: BoardFilterModes | undefined,
): string[] {
  if (panel.panel.kind !== 'view' || panel.broken) return [];
  return dashboard.filterFields.flatMap(field =>
    dashboard.filters.values[field.name] !== undefined &&
    filterModeOf(modes, field.name) !== 'hidden' &&
    panel.reach[field.name]?.wired === false
      ? [field.label]
      : [],
  );
}

/**
 * The name of the filter a press on this panel sets (D22 I, 「点击筛选「仓库」」),
 * or `undefined` for a panel whose press does something else.
 */
function pressedFilter(
  panel: DashboardPanelView,
  dashboard: DashboardController,
): string | undefined {
  const click = panel.click;
  if (click?.kind !== 'filter') return undefined;
  return dashboard.filterFields.find(field => field.name === click.filter)
    ?.label;
}
