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
import { PanelLeftOpenIcon } from 'lucide-react';
import type { Issue, ViewKind } from '../model/index.js';
// Aliased: `hasResult` here is the prop a dashboard overrides it with.
import { hasResult as viewHasResult } from '../runtime/index.js';
import type { WorkbenchController } from '../react/index.js';
import { IconButton } from './IconButton.js';
import { AppliedBar } from './AppliedBar.js';
import { EditorBand, EditorBandToggle, EditorFold } from './EditorBand.js';
import { SPACE, TRAY } from './layout.js';
import { LeaveDialog } from './LeaveGuard.js';
import { ErrorStrip, WarningStrip } from './StatusStrip.js';
import { useViewMessages } from './MessagesProvider.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import type { ViewMessages } from './messages.js';
import { ViewExpandToggle } from './ViewExpansion.js';
import { ViewHeader } from './ViewHeader.js';
import { ViewList } from './ViewList.js';
import { ViewManager } from './ViewManager.js';
import { ViewSurface } from './ViewSurface.js';
import { ViewSwitcher } from './ViewSwitcher.js';
import { NoViews } from './workbench/NoViews.js';
import { OpeningSkeleton } from './workbench/OpeningSkeleton.js';
import { ResultBlock, resultBlockShown } from './workbench/ResultBlock.js';
import { Unopenable } from './workbench/Unopenable.js';
import { filled, useEditorFold } from './workbench/useEditorFold.js';
import { useWorkbenchFolds } from './workbench/useWorkbenchFolds.js';

export interface WorkbenchShellProps {
  workbench: WorkbenchController;
  /** The kind being drawn; the title bar and the error strip name it. */
  kind: ViewKind;
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
   * How this view is renewed, as the last of the view-level controls. It is
   * a slot rather than something the shell builds from `workbench.refresh`,
   * because a Record workbench already carries the same control in its
   * result toolbar, where the freshness group is, and one view with two
   * entries to one setting is one entry too many.
   */
  freshness?: ReactNode;
  /** The view's own editor, between the title bar and the strips. */
  editor?: ReactNode;
  /**
   * What the editor is called. Given one, the editor lives in a fold whose
   * toggle sits in the title bar; left out, the editor is drawn open on its
   * own block. It is opt-in because only a view whose editor *has* a settled
   * shape can fold it — `docs/design/decisions.md` Q2 leaves the analysis
   * editor's form open, so that workbench passes none.
   */
  editorLabel?: string;
  /**
   * The editor's current mode, appended to the toggle's accessible name —
   * "Filter · Simple". It is what `editorModes` changes, said without
   * opening the menu.
   */
  editorModeLabel?: string;
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
  /** Strips only one kind has, under the two every kind shows. */
  strips?: ReactNode;
  /** The rows, the chart, the panels — what the page is for. */
  result?: ReactNode;
  /**
   * The way out of an error the status line reports, at the end of its
   * line. A record view offers its column settings; the other two have
   * nowhere of their own to send a reader yet.
   */
  errorAction?: ReactNode;
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
   * Whether a request is on its way, which is the other reason a result
   * block exists before there is a result: the rows are drawn loading in
   * it. The open view's own query when left out.
   */
  resultPending?: boolean;
  /** The warnings to show; every one the view reports when left out. */
  warnings?: readonly Issue[];
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
 */
export function WorkbenchShell({
  workbench,
  kind,
  title,
  theme,
  messages: wording,
  locale,
  timeZone,
  actions,
  freshness,
  editor,
  editorLabel,
  editorModeLabel,
  editorModes,
  editorOpen,
  defaultEditorOpen,
  onEditorOpenChange,
  editorPending = 0,
  toolbar,
  strips,
  result,
  errorAction,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable = true,
  manage = true,
  hasResult,
  resultPending,
  warnings,
  onRenderFailure,
  resultFramed = true,
  resultSlots,
  className,
}: WorkbenchShellProps) {
  const messages = useViewMessages(wording);
  const titleId = useId();
  const editorId = useId();
  const { filter, leave, list, manager, opened, state, unopenable } = workbench;
  // A view that opened and is this page's to draw. Anything else is reported
  // instead of being dressed up as a title bar over an empty body.
  const open = state !== null && workbench.runtime !== null && !unopenable;
  // Whether there is a result for the applied bar to describe. It renders
  // nothing without one, which is also half of whether the result block has
  // any reason to exist.
  const describesResult = hasResult ?? viewHasResult(state);
  // The other half of it: a block with a request in it holds the rows the
  // request will fill, drawn loading.
  const pending = resultPending ?? state?.query.status === 'loading';

  // One dialog behind two ways in — the sidebar's gear and the switcher's
  // last item — so the state is here rather than inside either of them.
  const [managing, setManaging] = useState(false);
  const canManage = manage && manager.can.anything;
  // One command behind three ways in — the sidebar's `+`, the switcher's
  // item and the empty work area's button — and none of the three exists
  // without it (D4).
  const create = workbench.canCreate ? workbench.create : undefined;
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
  const openedId = workbench.runtime?.id ?? null;
  useLayoutEffect(() => {
    if (!created.current || !open || openedId === null) return;
    created.current = false;
    viewTitle.current?.focus();
  }, [open, openedId]);

  const folded = editorLabel !== undefined && editor != null;
  // A caught failure belongs to the view it happened in: opening another
  // view draws its parts afresh rather than carrying the fallback over.
  const resetKeys = [workbench.runtime?.id ?? null];
  const editorIsOpen = useEditorFold({
    controlled: editorOpen,
    fallback: defaultEditorOpen ?? state?.saved === null,
    runtimeId: workbench.runtime?.id ?? null,
    onChange: onEditorOpenChange,
  });

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

  // The way back to the list, and the list itself as one control. Both exist
  // only while the sidebar is away — with it on screen, the list *is* the
  // switcher and the sidebar's heading is the definition's title.
  const collapsed = !sidebarOpen && (
    <div
      data-slot="view-collapsed"
      // No box of its own: `contents` hands the way back, the definition's
      // name and the switcher to the identity group as its own items.
      //
      // It used to be a flex box that carried `grow` — the group's spring —
      // and the switcher grew inside it up to its own label (`w-0 grow
      // max-w-fit`). With a long name that was fine: the switcher took the
      // room and the audience word and Save stood right after it. With a
      // short one the switcher stopped at its label and the box went on
      // growing, so the audience and Save stood at the far end of an empty
      // stretch instead of against the name (D12 Ⅰ). Capping the box at
      // `max-w-max` was no answer: the switcher is `w-0` precisely so a
      // long name does not ask the row for the whole string, which also
      // makes its max-content contribution its 6em floor — the cap pinned
      // it there. As direct items the switcher is the group's spring
      // itself, grows to its label and no further, the audience and Save
      // follow it, and whatever is left over lies after Save where nothing
      // stands. The group's floor is unchanged: it is the sum of its items'
      // floors either way, and the switcher keeps its own.
      className="contents"
    >
      <IconButton
        ref={expandRef}
        label={messages.label('label.workbench.expand-sidebar')}
        variant="ghost"
        size="icon-sm"
        aria-expanded={false}
        onClick={() => toggleSidebar(true)}
      >
        <PanelLeftOpenIcon />
      </IconButton>
      {title && (
        // With the list folded away this is where the page's name lives, so
        // it is the `h1` the sidebar's heading was, at the weight of a name
        // rather than a caption: a muted small word before the switcher
        // read as a hint, and nothing said it was the parent of the view
        // beside it. The slash does — the two are a path, "Orders / Pending".
        //
        // Still the first thing to go when the row runs out of room: the
        // view's own name outranks the name of everything it is one of.
        // "When the row runs out of room" is a fact about the bar, and the
        // viewport `sm:` this used to ask answered a different question: in
        // a 360px panel on a wide page it showed "Orders" in full while the
        // view's own name was down to "全…". `@md/header` asks the bar
        // itself (`@container/header` in `ViewHeader`); `@2xl` was so
        // eager that most embeddings never saw the name at all.
        <>
          {/* `text-base`, the same 16/600 the sidebar's heading wears when
              the list is open: folding the list away moves this `h1`, it
              does not demote it. At `text-sm` it was 14 over a 13px
              switcher — two heading levels squashed into one and a half,
              and the page's own name set smaller than the view it holds. */}
          <h1
            data-slot="definition-title"
            className="hidden min-w-0 truncate text-base font-semibold @md/header:block"
          >
            {title}
          </h1>
          <span
            aria-hidden
            data-slot="definition-separator"
            className="text-muted-foreground hidden select-none @md/header:inline"
          >
            /
          </span>
        </>
      )}
      <ViewSwitcher
        list={list}
        kind={kind}
        currentId={state?.saved?.id ?? null}
        currentTitle={state?.title ?? ''}
        onOpen={workbench.choose}
        onCreate={create}
        onManage={canManage ? () => setManaging(true) : undefined}
      />
    </div>
  );

  return (
    <ViewSurface
      ref={surfaceRef}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={timeZone}
      className="gap-0 md:flex-row"
    >
      {sidebarOpen && (
        // Bare: the ground, the padding and the rule that divides the two
        // columns are the list's own (D12), so an `aside` that also painted
        // them would be a second opinion about where the column ends. The
        // `Separator` that used to stand here went with them — one edge,
        // drawn once, by whichever part the edge belongs to.
        <aside
          data-slot="view-sidebar"
          className="flex w-full shrink-0 flex-col md:w-56"
        >
          <ViewList
            list={list}
            title={title}
            currentId={state?.saved?.id ?? null}
            onOpen={workbench.choose}
            onCreate={create}
            onRetry={list.error ? () => list.reload() : undefined}
            // Only when something on the list can actually be managed: a
            // reader with no write permission at all would otherwise get a
            // button whose only lesson is that it leads to a dialog of
            // read-only rows.
            onManage={canManage ? () => setManaging(true) : undefined}
            onCollapse={() => toggleSidebar(false)}
            collapseRef={collapseRef}
          />
        </aside>
      )}

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

        {none && <NoViews failed={list.error !== null} onCreate={create} />}

        {open && (
          <>
            {/* The handle is in the title bar and the band is under the
                status line, so the two ends of the fold cannot be nested one
                inside the other — they are wrapped instead. The root draws
                nothing (`contents`), and where a workbench does not fold its
                editor it simply holds neither trigger nor panel. */}
            <EditorFold
              open={editorIsOpen.open}
              onOpenChange={editorIsOpen.set}
            >
              {/* A banner, ruled off rather than boxed: a card around the thing
                that names the page is a card around the page. */}
              <div
                data-slot="view-header-block"
                // The rule runs the whole width of the column, under `main`'s own
                // padding, so it meets the sidebar's edge and the two heads end
                // on one continuous line rather than two dashes with a gap.
                className="border-border -mx-4 border-b px-4 pb-3"
              >
                <ViewHeader
                  state={state}
                  kind={kind}
                  commands={workbench.commands}
                  titleId={titleId}
                  titleRef={viewTitle}
                  actions={
                    actions != null && (
                      <RenderBoundary
                        name="actions"
                        compact
                        resetKeys={resetKeys}
                        onFailure={onRenderFailure}
                      >
                        {actions}
                      </RenderBoundary>
                    )
                  }
                  // Something in `leading` already shows the kind and the name,
                  // so the bar does not show them a second time.
                  namesView={sidebarOpen}
                  leading={collapsed || undefined}
                  trailing={
                    // All three are answers to *how am I looking at this*,
                    // which is what this group is, and they read outwards: the
                    // editor governs what the view asks, filling the screen
                    // governs the room the answer gets, and the refresh
                    // governs how often it is renewed.
                    (folded || expandable || freshness !== undefined) && (
                      <>
                        {folded && (
                          <EditorBandToggle
                            label={editorLabel}
                            modeLabel={editorModeLabel}
                            modes={editorModes}
                            pending={editorPending}
                          />
                        )}
                        {freshness}
                        {expandable && (
                          <ViewExpandToggle
                            expansion={fill}
                            ref={expandViewRef}
                          />
                        )}
                      </>
                    )
                  }
                  onSaved={workbench.onSaved}
                  onCreated={() => {
                    created.current = true;
                  }}
                  onRenamed={workbench.onRenamed}
                  onDeleted={workbench.onDeleted}
                  onRecovered={workbench.onRecovered}
                />
              </div>

              {/* The status line (D12 Ⅰ′): what the view reports about itself,
                under the title bar and only when there is something to say —
                a config that will not run, a warning that does not block.
                The last write's outcome is the title bar's own line above.
                `empty:hidden` keeps the row out of the flow when every strip
                rendered nothing, so the ruler's 16px does not stack twice. */}
              <div
                data-slot="status-line"
                className={cn('flex flex-col empty:hidden', SPACE.ROWS)}
              >
                <ErrorStrip
                  // The definition's own findings beside the view's: an
                  // error in the definition was reported to `onIssue` and to
                  // nobody on screen (F-05).
                  issues={[...filter.unmarked, ...workbench.definitionIssues]}
                  title={
                    kind === 'dashboard'
                      ? messages.label('label.dashboard.needs-fixing')
                      : undefined
                  }
                  action={errorAction}
                />
                {/* Warnings block nothing — the result below is the real one —
                  so they sit under the errors and never replace it. Failed
                  preferences are one: the list still works, in the server's
                  order, so it is said as a warning and said once. */}
                <WarningStrip
                  issues={[
                    ...(warnings ?? state.issues),
                    ...workbench.definitionIssues,
                    ...(list.preferencesError
                      ? [
                          {
                            ...list.preferencesError,
                            severity: 'warning' as const,
                          },
                        ]
                      : []),
                  ]}
                />
              </div>

              {/* The conditions, on a surface of their own. The block exists
                only where there is something in it: an empty card is the
                promise of an editor that is not there. */}
              {editor != null &&
                (folded ? (
                  <EditorBand id={editorId} className={cn(TRAY, SPACE.ROWS)}>
                    <RenderBoundary
                      name="editor"
                      resetKeys={resetKeys}
                      onFailure={onRenderFailure}
                    >
                      {editor}
                    </RenderBoundary>
                  </EditorBand>
                ) : (
                  <section
                    data-slot="condition-block"
                    className={cn('flex flex-col', TRAY, SPACE.ROWS)}
                  >
                    <RenderBoundary
                      name="editor"
                      resetKeys={resetKeys}
                      onFailure={onRenderFailure}
                    >
                      {editor}
                    </RenderBoundary>
                  </section>
                ))}
            </EditorFold>

            {/* The applied-conditions band (D12 Ⅲ): what the rows on screen
                were fetched under, a line of its own between the editor and
                the result — neither inside the tray, which is the draft, nor
                inside the result block, which is the rows. It draws nothing
                until there is a result to describe. */}
            <AppliedBar filter={filter} hasResult={describesResult} />

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
              strips: filled(strips),
              result: filled(result),
            }) && (
              <ResultBlock framed={resultFramed} slots={resultSlots}>
                {/* The host's bulk slot renders in the toolbar and its row
                    slot in the rows, so both are held: a throwing one takes
                    the bar or the rows, and the title bar, the editor and
                    the draft stay. Two boundaries rather than one so that
                    the half that still works still draws — and the strip
                    between them is neither's, because a query that failed
                    has to be readable whatever the host's buttons did. */}
                <RenderBoundary
                  name="result"
                  resetKeys={resetKeys}
                  onFailure={onRenderFailure}
                >
                  {toolbar}
                </RenderBoundary>
                {strips}
                <RenderBoundary
                  name="result"
                  resetKeys={resetKeys}
                  onFailure={onRenderFailure}
                >
                  {result}
                </RenderBoundary>
              </ResultBlock>
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
