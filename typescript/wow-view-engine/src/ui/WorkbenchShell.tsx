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
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { cn } from 'cn';
import type { Issue } from '../model/index.js';
// Aliased: `hasResult` here is the prop a dashboard overrides it with.
import {
  hasAsked as viewHasAsked,
  hasResult as viewHasResult,
} from '../runtime/index.js';
import type { WorkbenchController } from '../react/index.js';
import { AppliedBar } from './AppliedBar.js';
import { EditorFold } from './EditorBand.js';
import { SPACE } from './layout.js';
import { LeaveDialog } from './LeaveGuard.js';
import { QueryStrip } from './StatusStrip.js';
import type { RenderFailureHandler } from './RenderBoundary.js';
import type { ViewMessages } from './messages.js';
import { ViewManager } from './ViewManager.js';
import { ViewSurface } from './ViewSurface.js';
import { ConditionBlock } from './workbench/ConditionBlock.js';
import type { NewViewCommand } from './workbench/NewView.js';
import { NoViews } from './workbench/NoViews.js';
import { OriginBar } from './workbench/OriginBar.js';
import { OpeningSkeleton } from './workbench/OpeningSkeleton.js';
import { resultBlockShown, ShellResult } from './workbench/ResultBlock.js';
import { FoldedSidebar, SidebarColumn } from './workbench/Sidebar.js';
import { focusIn } from './analysis/listFocus.js';
import { StatusLine } from './workbench/StatusLine.js';
import { TitleBar } from './workbench/TitleBar.js';
import { Unopenable } from './workbench/Unopenable.js';
import { filled, useEditorFold } from './workbench/useEditorFold.js';
import { useWorkbenchFolds } from './workbench/useWorkbenchFolds.js';

export interface WorkbenchShellProps {
  workbench: WorkbenchController;
  /** The definition's title, which names the sidebar and the header. */
  title?: string;
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  /**
   * The zone times show in — the engine's, so a row's time reads on the same
   * clock a relative condition was filtered by.
   */
  timeZone?: string;
  /** The host's own global actions, at the end of the right-hand group. */
  actions?: ReactNode;
  /**
   * How this view is renewed, as the last of the view-level controls. Left
   * out, the shell draws the refresh control from `workbench.refresh`, busy
   * while the view's own query runs; a dashboard passes its own, because
   * its panels are what run and what the note beside the control names.
   */
  freshness?: ReactNode;
  /**
   * The way into building the view, beside its save commands on the title
   * bar — a dashboard's 「编辑」 (D22 A).
   */
  build?: ReactNode;
  /**
   * The view is saved and reverted elsewhere: the title bar leaves off its
   * save commands and the "edited" mark's ↺ — see `ViewHeader`.
   */
  commitElsewhere?: boolean;
  /** The view's own editor, between the title bar and the strips. */
  editor?: ReactNode;
  /**
   * The view's search box (`SearchBox`), at the end of the applied band: the
   * one condition kept on hand rather than in the fold, beside the
   * conditions the rows were fetched under.
   */
  search?: ReactNode;
  /**
   * What the editor is called. Given one, the editor lives in a fold whose
   * toggle sits in the title bar; left out, the editor is drawn open on its
   * own block. It is opt-in because only a view whose editor *has* a settled
   * shape can fold it — `docs/design/decisions.md` Q2 leaves the analysis
   * editor's form open, so that workbench passes none.
   */
  editorLabel?: string;
  /**
   * Menu items for a chevron beside the editor's toggle: the ways of
   * editing this editor offers. Left out, the toggle stands alone.
   */
  editorModes?: ReactNode;
  /** Controlled fold state; the shell holds its own when left out. */
  editorOpen?: boolean;
  /**
   * The fold a view opens on. A view that was never saved opens out — there
   * is nothing to look at until it has been told what to ask for — and a
   * saved one opens folded, because its author already decided.
   */
  defaultEditorOpen?: boolean;
  onEditorOpenChange?(open: boolean): void;
  /**
   * How many nodes of the editor say something other than what ran. The
   * toggle carries the dot and the count, so the one credential for
   * "edited, not applied" survives the editor being folded away.
   */
  editorPending?: number;
  /**
   * The result block's first row (D12 Ⅳ): what may be done to the result
   * and how it is shown. It is its own slot rather than the head of
   * `result` because the failure strip goes between the two — the toolbar
   * is the block's first row whatever else the block holds, and a red line
   * above it read as a banner over the whole view rather than as something
   * the rows below had to say.
   */
  toolbar?: ReactNode;
  /**
   * The strip between the toolbar and the rows. Left out, the shell draws
   * the query strip from the view's own query — failed, stale or not, retry
   * — which is what a record and an analysis view both say there; a
   * dashboard, whose panels fail one by one, passes `null`.
   */
  strips?: ReactNode;
  /** The rows, the chart, the panels — what the page is for. */
  result?: ReactNode;
  /**
   * The way out of an error the status line reports, at the end of its
   * line. A record view offers its column settings, an analysis view its
   * tray or its chart options. Given as a function, it is asked whether the
   * editor is open: a way out that only opens the editor is no way out
   * while the editor is already open under it (2026-09-23 audit).
   */
  errorAction?: ReactNode | ((editorOpen: boolean) => ReactNode);
  /**
   * The codes of the findings a result makes about itself that the kind
   * draws beside that result — an analysis's 「只显示了前 N 组」 over its
   * table — and that the status line therefore leaves out, so a sentence is
   * said once and where it is about.
   */
  besideResult?: readonly string[];
  /**
   * The sidebar a workbench opens on. One boolean governs it, so collapsing
   * is a change in one place rather than in the layout of every part beside
   * it. It is view state and nothing else: never saved, never asked about by
   * the leave guard.
   *
   * Left out, the shell decides from the room it was actually given and
   * keeps deciding as that room changes (`useSidebarFold`): a column
   * narrower than `md` folds, because below that width the list is not
   * beside the view but stacked on top of it, and 204px of navigation above
   * the first row is the worst trade a phone can make. Once the user has
   * pressed the fold themselves, their answer stands. A host that says
   * `true` or `false` is obeyed at every width and never measured — it knows
   * something about its page that a measurement does not.
   */
  defaultSidebarOpen?: boolean;
  /** Told whenever the sidebar opens or closes, for a host that mirrors it. */
  onSidebarOpenChange?(open: boolean): void;
  /**
   * Whether the workbench offers to fill the screen. It is the same kind of
   * state as the sidebar's fold — this screen at this moment, never saved,
   * never asked about by the leave guard — so the shell holds it; the host
   * only says whether the control is there at all. A page that is already a
   * full-screen view of one thing has nothing to gain from it.
   */
  expandable?: boolean;
  /**
   * Whether the view manager is on offer at all (D18 XI). On by default;
   * off, neither the sidebar's gear nor the switcher's item exists. What
   * the user may do inside it is the store's permissions, read separately.
   */
  manage?: boolean;
  /**
   * Whether the result below was ever asked for; the applied bar renders
   * nothing until it was. The open view's own result when left out — a
   * dashboard has none of its own and answers from its panels instead.
   */
  hasResult?: boolean;
  /**
   * Whether the applied-conditions band is drawn. On by default; a
   * dashboard says what its panels run under in its own filter bar, and
   * draws the band only for a standing condition the bar does not hold.
   */
  applied?: boolean;
  /**
   * Whether a request is on its way, which is the other reason a result
   * block exists before there is a result: the rows are drawn loading in
   * it. The open view's own query when left out.
   */
  resultPending?: boolean;
  /**
   * The warnings to show. Left out, every one the view reports, plus what
   * this result says about itself (`resultIssues`): a summary row that fell
   * back to the page, a grouping that filled its limit, are facts about the
   * numbers below, and they outlive the next keystroke because they ride
   * with the result rather than with the draft's admission.
   */
  warnings?: readonly Issue[];
  /**
   * How a finding is said in the status line, where it stands away from the
   * part it is about. A dashboard names the panel a finding belongs to —
   * 「这个面板」 means something inside a panel and nothing above the grid.
   * Every finding is said as it is when left out.
   */
  nameIssue?(issue: Issue): Issue;
  /**
   * Whether the result is drawn inside one frame — toolbar on top, rows to
   * the edge, pagination at the bottom (D12). A dashboard opts out: its
   * result is already a grid of cards, and a frame round cards is a frame
   * round frames.
   */
  resultFramed?: boolean;
  /**
   * What this kind's own parts wear inside that frame, from `resultSlots()`
   * (`ui/variants.tsx`). The shell dresses the two slots it fills itself —
   * the toolbar as the frame's first row, a strip's margin — and carries
   * this through untouched: which of a kind's parts is the caption row and
   * which keeps the frame's padding is the kind's to say, not the shell's
   * (D18-1 — one shell, no `if` per kind).
   */
  resultSlots?: string;
  /**
   * Where a render failure caught by one of the shell's boundaries goes —
   * the host's own action slot, the editor or the result. The part shows a
   * recoverable error state in place either way; this is how the host
   * learns of it.
   */
  onRenderFailure?: RenderFailureHandler;
  /**
   * What stands in the sidebar column in place of the view list while it is
   * given: the visualization panel (D20 屏 I). The list is navigation, and
   * navigation is not needed while a chart is being configured, so the one
   * column serves both — the panel carries its own way back. It shows
   * whether or not the list is folded.
   *
   * Read as React reads a child, so `open && <Panel />` is the way a part
   * says "not now": every other slot is filled that way, and a column held
   * open by a `false` would take the list off the screen for good.
   */
  panel?: ReactNode;
  /** Extra classes for the main column. */
  className?: string;
}

/**
 * The frame the three default workbenches share: the view list beside it, the
 * title bar over it, the findings under that, and the two slots that make one
 * kind different from another.
 *
 * The main column is **blocks**, not a stack of rows: the title bar as a
 * banner ruled off from what follows, the status line, the conditions in a
 * tray, the band of applied conditions, and the result — which sits on no
 * card at all (D12), because a table draws its own header layer, its own
 * hairlines and its own held edges, and a border round that is a frame
 * round a frame. The blocks are what make grouping visible — when the space
 * between two blocks is the space between two buttons, nothing reads as
 * belonging to anything — which is why every distance comes from one scale
 * (`layout.ts`) rather than from whatever looked right at each call site.
 *
 * Within that, the order is by how close each part stands to the result:
 * which view this is, the editor folded out of the way, anything the view has
 * to say in a line, what the result was fetched under, then the result.
 *
 * The title bar reads as two groups and nothing crosses between them: on the
 * left, everything about *which view this is* — the way back to the list, the
 * view's name and the commands that save it; on the right, everything about
 * *how it is being looked at* — the editor's fold and the host's own actions.
 *
 * Almost everything the shell draws it reads from one `WorkbenchController`.
 * The two exceptions are the two things that are neither the view nor the
 * list: whether the sidebar is folded away, and whether the editor is. Both
 * belong to this screen at this moment — nothing about them is worth saving,
 * and the leave guard has nothing to ask about them — so the shell holds them
 * rather than handing every workbench the same two `useState` calls.
 *
 * The shell composes its columns and blocks rather than drawing them: the
 * sidebar in its two forms, the title bar, the status line, the condition
 * tray and the filled result block are `workbench/`'s, and what stays here
 * is the state they share and the order they stand in.
 */
export function WorkbenchShell({
  workbench,
  title,
  theme,
  messages: wording,
  locale,
  timeZone,
  actions,
  freshness,
  build,
  commitElsewhere,
  editor,
  editorLabel,
  search,
  editorModes,
  editorOpen,
  defaultEditorOpen,
  onEditorOpenChange,
  editorPending = 0,
  toolbar,
  strips,
  result,
  errorAction,
  besideResult,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable = true,
  manage = true,
  hasResult,
  applied = true,
  resultPending,
  warnings,
  nameIssue,
  onRenderFailure,
  resultFramed = true,
  resultSlots,
  panel,
  className,
}: WorkbenchShellProps) {
  const titleId = useId();
  const editorId = useId();
  const { filter, leave, list, manager, opened, runtime, state, unopenable } =
    workbench;
  // A view that opened and is this page's to draw. Anything else is reported
  // instead of being dressed up as a title bar over an empty body.
  const open = state !== null && runtime !== null && !unopenable;
  // The kind on screen names the title bar's icon and the switcher's face.
  // Before anything is open, a workbench of one kind still has a face; one
  // of several shows none until a view says which it is.
  const kind =
    runtime?.kind ??
    (workbench.kinds.length === 1 ? workbench.kinds[0] : undefined);
  // Whether there is a result, which is half of whether the result block has
  // any reason to exist.
  const describesResult = hasResult ?? viewHasResult(state);
  // Whether a question was put to the source, which is when the applied bar
  // has something to say: the conditions the rows came back under, or — while
  // the first answer is on its way, or after it failed — the ones it was
  // sent under (`hasAsked`). Waiting for rows put the bar on screen as they
  // landed, above the result, and pushed the whole block down under the
  // reader's eyes. A dashboard answers for its panels through `hasResult`.
  const asked = hasResult ?? viewHasAsked(state);
  // The other half of it: a block with a request in it holds the rows the
  // request will fill, drawn loading.
  const pending = resultPending ?? state?.query.status === 'loading';

  // One dialog behind two ways in — the sidebar's gear and the switcher's
  // last item — so the state is here rather than inside either of them.
  const [managing, setManaging] = useState(false);
  const canManage = manage && manager.can.anything;
  // Only when something on the list can actually be managed: a reader with
  // no write permission at all would otherwise get a button whose only
  // lesson is that it leads to a dialog of read-only rows.
  const onManage = canManage ? () => setManaging(true) : undefined;
  const currentId = state?.saved?.id ?? null;
  // One command behind three ways in — the sidebar's `+`, the switcher's
  // item and the empty work area's button — and none of the three exists
  // without it (D4). Each draws it as `NewViewControl`: one kind is a
  // press, several are a menu of them (D20 Ⅱ).
  const create: NewViewCommand | undefined =
    workbench.creatable.length > 0
      ? { creatable: workbench.creatable, create: workbench.create }
      : undefined;
  // Nothing to open and nothing on its way: the list is in and has no view
  // of this kind, and no view was made from nothing either. Said in the
  // work area rather than left blank, because blank reads as broken.
  const none =
    !open &&
    !unopenable &&
    !opened.loading &&
    !list.loading &&
    workbench.openId === null;

  // Whether the list is beside the view, and whether the view fills the
  // screen: two folds that are one concern, because the second decides the
  // first. The buttons each press takes off the screen are the hook's too —
  // focus moves to whichever undoes the press.
  const {
    surfaceRef,
    expandViewRef,
    collapseRef,
    expandRef,
    sidebarOpen,
    toggleSidebar,
    fill,
  } = useWorkbenchFolds({
    expandable,
    open,
    opening: opened.loading,
    defaultSidebarOpen,
    onSidebarOpenChange,
  });

  // Where the keyboard lands after a copy is created. The dialog it was made
  // in closes, and there is nothing left of it to return focus to — the
  // trigger was a menu item in a menu that has also gone — so focus fell to
  // `<body>`. The view that was created is what the screen now shows, so its
  // name is where reading resumes.
  //
  // It cannot be done when the copy lands: opening it releases the previous
  // runtime, and this whole header unmounts until the new one is open. So
  // the intent is held and spent by the effect below, on the first render
  // where there is a title to put it on. A ref rather than state, like the
  // two effects below it: nothing renders from it, and a `setState` in an
  // effect is a cascading render for a value no render reads.
  const viewTitle = useRef<HTMLHeadingElement>(null);
  const created = useRef(false);

  // Spent on the opening the copy produced: the id is in the dependencies
  // because it is what changes when the new view finally opens, and the
  // header is drawn again with the new title on it.
  const openedId = runtime?.id ?? null;
  useLayoutEffect(() => {
    if (!created.current || !open || openedId === null) return;
    created.current = false;
    viewTitle.current?.focus();
  }, [open, openedId]);

  const folded = editorLabel !== undefined && editor != null;
  // A caught failure belongs to the view it happened in: opening another
  // view draws its parts afresh rather than carrying the fallback over.
  const resetKeys = [runtime?.id ?? null];
  const editorIsOpen = useEditorFold({
    controlled: editorOpen,
    // A new view opens with its editor out, since there is nothing in it yet
    // — but not one opened from another's group: its conditions are what it
    // was opened with, the applied bar says them, and an editor unfolded
    // over them says them twice more (2026-09-23 audit).
    fallback:
      defaultEditorOpen ?? (state?.saved === null && !workbench.held?.origin),
    runtimeId: runtime?.id ?? null,
    onChange: onEditorOpenChange,
  });
  // The one query every kind of view has, read once for the two slots that
  // report it: the query strip under the toolbar, and the busy refresh.
  const querying = state?.query.status === 'loading';
  const failed =
    state?.query.status === 'error' && state.query.error !== undefined
      ? state.query.error
      : null;

  // A press that opens the editor takes the keyboard into it (the
  // 2026-09-23 audit, P2-13): the band is drawn under the status line, so
  // the next Tab otherwise walked Refresh, Auto refresh and Fill before it
  // reached the first thing the press was for. Only a press does — the
  // sidebar's rule (`pressed`): a band a host or a new view opened leaves
  // the keyboard where it was. Closing leaves it on the toggle, which is
  // Base UI's own.
  const pressedOpen = useRef(false);
  const openEditor = (next: boolean) => {
    pressedOpen.current = next;
    editorIsOpen.set(next);
  };
  useLayoutEffect(() => {
    if (!editorIsOpen.open || !pressedOpen.current) return;
    pressedOpen.current = false;
    focusIn(document.getElementById(editorId));
  }, [editorIsOpen.open, editorId]);

  // Folding the editor away ends the editing state the inputs inside it
  // started. Closing unmounts them, and an unmounted input fires no blur, so
  // `FilterPanel`'s own handler never runs — the runtime would stay
  // `editing: true` and its auto refresh would stay paused for as long as
  // the view is open. Same shape as the focus effect above: the question is
  // whether this changed, which StrictMode's second setup must answer "no".
  const blurEditor = filter.blur;
  const wasOpen = useRef(editorIsOpen.open);
  useLayoutEffect(() => {
    const open = editorIsOpen.open;
    if (wasOpen.current === open) return;
    wasOpen.current = open;
    if (!open) blurEditor();
  }, [editorIsOpen.open, blurEditor]);

  // Only where it will draw: `resultBlockShown` reads the slot to decide
  // whether there is a result block at all, and an element that renders
  // null still counts as something in it (`filled`).
  const strip =
    strips !== undefined
      ? strips
      : failed && (
          <QueryStrip
            error={failed}
            stale={viewHasResult(state)}
            onRetry={() => runtime?.refresh()}
          />
        );

  // The way back to the list, and the list itself as one control, in the
  // title bar while the sidebar is away.
  const collapsed = !sidebarOpen && (
    <FoldedSidebar
      title={title}
      expandRef={expandRef}
      onExpand={() => toggleSidebar(true)}
      list={list}
      kind={kind}
      currentId={currentId}
      currentTitle={state?.title ?? ''}
      onOpen={workbench.choose}
      create={create}
      onManage={onManage}
    />
  );

  return (
    <ViewSurface
      ref={surfaceRef}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={timeZone}
      // The container's height, always: the result takes what the parts
      // above it leave and the footer sits at the bottom, whatever the rows
      // (`styles.css`, "A workbench fills its container"). A container of
      // no definite height makes `h-full` nothing, and the floor is then
      // the whole of it — 36rem, or the host's `--fve-workbench-min-height`.
      className="h-full min-h-[var(--fve-workbench-min-height,36rem)] gap-0 md:flex-row"
    >
      <SidebarColumn
        panel={panel}
        open={sidebarOpen}
        list={list}
        title={title}
        currentId={currentId}
        onOpen={workbench.choose}
        create={create}
        onManage={onManage}
        onCollapse={() => toggleSidebar(false)}
        collapseRef={collapseRef}
      />

      <main
        // Only while the title is on screen: an id that addresses nothing is
        // a broken label rather than a missing one.
        aria-labelledby={open ? titleId : undefined}
        className={cn(
          'flex min-w-0 flex-1 flex-col p-4',
          SPACE.BLOCKS,
          className,
        )}
      >
        {/* Without an open view there is no title bar to carry the way back,
            and a workbench that could not be un-collapsed would be a trap.

            It is the same bar, so it is the same container: `@container/header`
            is what `ViewHeader` declares and what the definition's name is
            measured against (`@md/header`). Without it here, that name had no
            container to ask and stayed hidden at every width — on the one
            screen where nothing else says which definition this is. */}
        {!open && collapsed && (
          <div className="@container/header flex min-h-10">{collapsed}</div>
        )}

        {unopenable && (
          <Unopenable
            issue={unopenable}
            // The way back is the view the user would have got without
            // asking for this one — and only where that is somewhere else,
            // because an action that re-opens the view that just failed is
            // a button whose whole effect is to redraw this screen.
            onDefault={
              list.defaultInstanceId !== null &&
              list.defaultInstanceId !== workbench.openId
                ? () => workbench.choose(list.defaultInstanceId)
                : undefined
            }
          />
        )}

        {/* The shape of the page that is coming, not a bar saying something
            is (P-13). Its title bar is left out where the collapsed row
            above already stands in that place. */}
        {opened.loading && (
          <OpeningSkeleton framed={resultFramed} header={!collapsed} />
        )}

        {none && <NoViews failed={list.error !== null} create={create} />}

        {open && (
          <>
            {/* The handle is in the title bar and the band is under the
                status line, so the two ends of the fold cannot be nested one
                inside the other — they are wrapped instead. The root draws
                nothing (`contents`), and where a workbench does not fold its
                editor it simply holds neither trigger nor panel. */}
            <EditorFold open={editorIsOpen.open} onOpenChange={openEditor}>
              <TitleBar
                workbench={workbench}
                state={state}
                kind={runtime.kind}
                titleId={titleId}
                titleRef={viewTitle}
                actions={actions}
                resetKeys={resetKeys}
                onRenderFailure={onRenderFailure}
                namesView={sidebarOpen}
                leading={collapsed || undefined}
                editorLabel={folded ? editorLabel : undefined}
                editorModes={editorModes}
                editorPending={editorPending}
                freshness={freshness}
                busy={querying}
                expandable={expandable}
                fill={fill}
                expandViewRef={expandViewRef}
                onCreated={() => {
                  created.current = true;
                }}
                build={build}
                commitElsewhere={commitElsewhere}
              />

              {/* Where this view came from, when it was opened out of another
                (D20): the way back, naming the origin, under the title bar
                and before anything the view says about itself. The
                workbench holds it; no kind's parts know it exists. */}
              {workbench.held?.origin && (
                <OriginBar
                  origin={workbench.held.origin}
                  onBack={workbench.back}
                />
              )}

              <StatusLine
                workbench={workbench}
                state={state}
                kind={kind}
                warnings={warnings}
                besideResult={besideResult}
                nameIssue={nameIssue}
                errorAction={
                  typeof errorAction === 'function'
                    ? errorAction(
                        editor != null && (!folded || editorIsOpen.open),
                      )
                    : errorAction
                }
              />

              {/* The conditions, on a surface of their own. The block exists
                only where there is something in it: an empty card is the
                promise of an editor that is not there. */}
              {editor != null && (
                <ConditionBlock
                  editor={editor}
                  folded={folded}
                  id={editorId}
                  resetKeys={resetKeys}
                  onRenderFailure={onRenderFailure}
                />
              )}
            </EditorFold>

            {/* The applied-conditions band (D12 Ⅲ): what the rows on screen
                were fetched under, a line of its own between the editor and
                the result — neither inside the tray, which is the draft, nor
                inside the result block, which is the rows. It draws nothing
                until a question has been asked. */}
            {/* The search sits at the band's end: it is one of the
                conditions, and the band has the width the title bar does
                not — there, beside the title, the filter, the refresh and
                the expand, it pushed the row onto two lines. */}
            {search ? (
              <div
                data-slot="applied-row"
                className="flex flex-wrap items-center gap-2"
              >
                <AppliedBar
                  filter={filter}
                  asked={asked && applied}
                  className="min-w-0 grow"
                />
                <div className="ml-auto">{search}</div>
              </div>
            ) : (
              <AppliedBar filter={filter} asked={asked && applied} />
            )}

            {/* The result itself (D12 Ⅳ–Ⅶ).

                Only where there is a result to frame, one on its way, or
                something to say about the last one (`resultBlockShown`): a
                block around none of those is the empty block this package's
                own layout rule forbids, and for a config that will not run
                it was a frame around a toolbar. */}
            {resultBlockShown({
              framed: resultFramed,
              hasResult: describesResult,
              pending,
              strips: filled(strip),
              result: filled(result),
            }) && (
              <ShellResult
                framed={resultFramed}
                slots={resultSlots}
                toolbar={toolbar}
                strip={strip}
                result={result}
                resetKeys={resetKeys}
                onRenderFailure={onRenderFailure}
              />
            )}
          </>
        )}
      </main>

      {canManage && (
        <ViewManager
          manager={manager}
          list={list}
          open={managing}
          onOpenChange={setManaging}
          openDirtyId={state?.dirty ? (state.saved?.id ?? null) : null}
        />
      )}

      {/* Outside the surface, and so handed the wording directly: it is the
          one dialog an override would otherwise never reach. */}
      <LeaveDialog leave={leave} messages={wording} />
    </ViewSurface>
  );
}
