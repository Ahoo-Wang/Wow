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
import type { WorkbenchController } from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Button } from './components/button.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { AppliedBar } from './AppliedBar.js';
import { EditorBand, EditorBandToggle } from './EditorBand.js';
import { SPACE, SURFACE } from './layout.js';
import { LeaveDialog } from './LeaveGuard.js';
import { ErrorStrip, WarningStrip } from './StatusStrip.js';
import { useViewMessages } from './MessagesProvider.js';
import { RenderBoundary, type RenderFailureHandler } from './RenderBoundary.js';
import type { ViewMessages } from './messages.js';
import { useViewExpansion, ViewExpandToggle } from './ViewExpansion.js';
import { ViewHeader } from './ViewHeader.js';
import { ViewList } from './ViewList.js';
import { ViewManager } from './ViewManager.js';
import { ViewSurface } from './ViewSurface.js';
import { ViewSwitcher } from './ViewSwitcher.js';

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
  /** Strips only one kind has, under the two every kind shows. */
  strips?: ReactNode;
  /** The rows, the chart, the panels — what the page is for. */
  result?: ReactNode;
  /**
   * Whether the result block is drawn on a surface. True for rows and for a
   * chart, which are one thing on one card; false for a dashboard, whose
   * result is already a grid of cards and would otherwise be a card of
   * cards.
   */
  resultSurface?: boolean;
  /**
   * The sidebar a workbench opens on. One boolean governs it, so collapsing
   * is a change in one place rather than in the layout of every part beside
   * it. It is view state and nothing else: never saved, never asked about by
   * the leave guard.
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
   * Whether the result below was ever asked for; the applied bar renders
   * nothing until it was. The open view's own result when left out — a
   * dashboard has none of its own and answers from its panels instead.
   */
  hasResult?: boolean;
  /** The warnings to show; every one the view reports when left out. */
  warnings?: readonly Issue[];
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
 * The main column is **three blocks**, not a stack of rows: the title bar as
 * a banner ruled off from what follows, the conditions on a surface, and the
 * result on a surface. The blocks are what make grouping visible — when the
 * space between two blocks is the space between two buttons, nothing reads
 * as belonging to anything — which is why every distance comes from one
 * scale (`layout.ts`) rather than from whatever looked right at each call
 * site.
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
  strips,
  result,
  resultSurface = true,
  defaultSidebarOpen = true,
  onSidebarOpenChange,
  expandable = true,
  hasResult,
  warnings,
  onRenderFailure,
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
  const describesResult = hasResult ?? state?.result != null;

  const [sidebarOpen, setSidebarOpen] = useState(defaultSidebarOpen);
  // One dialog behind two ways in — the sidebar's gear and the switcher's
  // last item — so the state is here rather than inside either of them.
  const [managing, setManaging] = useState(false);
  const canManage = manager.can.anything;

  const collapseRef = useRef<HTMLButtonElement>(null);
  const expandRef = useRef<HTMLButtonElement>(null);

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

  // Filling the screen. The surface expands where it already is — the whole
  // point of the decision, since re-parenting it would remount the editor
  // and take the draft with it — so the shell needs a handle on its own root
  // and on the button that governs it, and nothing else moves.
  const surfaceRef = useRef<HTMLDivElement>(null);
  const expandViewRef = useRef<HTMLButtonElement>(null);
  // Off while there is no view: the toggle lives in the title bar, which is
  // not drawn then, and an expansion nobody can see a way out of is a trap.
  // Passing the condition in rather than hiding the button releases one that
  // is already in force — switching to a view that will not open puts the
  // page back instead of stranding it.
  const expansion = useViewExpansion(
    surfaceRef,
    expandViewRef,
    expandable && open,
  );
  // The state the last run saw, not "has this run before". StrictMode does
  // setup, cleanup, setup on mount, so a "first run" flag is already spent
  // by the second setup and the effect would take focus off the host's page
  // on arrival — which is the one thing it must never do. Comparing the
  // value answers the question actually being asked: did this change?
  const shown = useRef(sidebarOpen);
  useLayoutEffect(() => {
    if (shown.current === sidebarOpen) return;
    shown.current = sidebarOpen;
    // Collapsing and expanding each take away the button that was just
    // pressed, so focus moves to the one that undoes it. Both are held as
    // refs rather than found again by selector: the buttons live in two
    // different components, and a shell that went looking for one in the
    // document would be reaching past both of them.
    (sidebarOpen ? collapseRef : expandRef).current?.focus();
  }, [sidebarOpen]);

  // Spent on the opening the copy produced: the id is in the dependencies
  // because it is what changes when the new view finally opens, and the
  // header is drawn again with the new title on it.
  const openedId = workbench.runtime?.id ?? null;
  useLayoutEffect(() => {
    if (!created.current || !open || openedId === null) return;
    created.current = false;
    viewTitle.current?.focus();
  }, [open, openedId]);

  const toggleSidebar = (next: boolean) => {
    setSidebarOpen(next);
    onSidebarOpenChange?.(next);
  };

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
      // The identity group's spring while it is there: with the sidebar
      // away the view's name is the switcher's label rather than the
      // heading beside it, so this is the part of the line that may give.
      //
      // It carries `grow` but no longer `min-w-0`. `grow` is how the
      // leftover room reaches the switcher at all; what the switcher does
      // with it is its own business, and it now stops at its contents
      // rather than stretching into a 470px pill. `min-w-0` was safe only
      // while the switcher could shrink to its icons — now the name keeps a
      // floor, and a group allowed to be squeezed below what its contents
      // need is a group that lies to the row above it, which is exactly
      // what painted Save over the view controls before.
      className={cn('flex grow items-center', SPACE.WITHIN)}
    >
      <Button
        ref={expandRef}
        variant="ghost"
        size="icon-sm"
        aria-label={messages.label('label.workbench.expand-sidebar')}
        aria-expanded={false}
        onClick={() => toggleSidebar(true)}
      >
        <PanelLeftOpenIcon />
      </Button>
      {title && (
        // The first thing to go when the row runs out of room: the view's own
        // name outranks the name of everything it is one of.
        //
        // "When the row runs out of room" is a fact about the bar, and the
        // viewport `sm:` this used to ask answered a different question: in
        // a 360px panel on a wide page it showed "Orders" in full while the
        // view's own name was down to "全…". `@2xl/header` asks the bar
        // itself (`@container/header` in `ViewHeader`), which is the only
        // thing that knows.
        <span
          data-slot="definition-title"
          className="text-muted-foreground hidden truncate text-sm @2xl/header:inline"
        >
          {title}
        </span>
      )}
      <ViewSwitcher
        list={list}
        kind={kind}
        currentId={state?.saved?.id ?? null}
        currentTitle={state?.title ?? ''}
        onOpen={workbench.choose}
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
        <>
          <aside
            data-slot="view-sidebar"
            className={cn('flex w-56 shrink-0 flex-col p-3', SPACE.GROUPS)}
          >
            <ViewList
              list={list}
              title={title}
              currentId={state?.saved?.id ?? null}
              onOpen={workbench.choose}
              // Only when something on the list can actually be managed: a
              // reader with no write permission at all would otherwise get a
              // button whose only lesson is that it leads to a dialog of
              // read-only rows.
              onManage={canManage ? () => setManaging(true) : undefined}
              onCollapse={() => toggleSidebar(false)}
              collapseRef={collapseRef}
            />
          </aside>

          <Separator orientation="vertical" className="hidden md:block" />
        </>
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
            and a workbench that could not be un-collapsed would be a trap. */}
        {!open && collapsed && <div className="flex min-h-10">{collapsed}</div>}

        {unopenable && (
          <Alert variant="destructive">
            <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
            <AlertDescription>{messages.issue(unopenable)}</AlertDescription>
          </Alert>
        )}

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {open && (
          <>
            {/* A banner, ruled off rather than boxed: a card around the thing
                that names the page is a card around the page. */}
            <div
              data-slot="view-header-block"
              className="border-border border-b pb-3"
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
                          open={editorIsOpen.open}
                          onOpenChange={editorIsOpen.set}
                          controls={editorId}
                          label={editorLabel}
                          modeLabel={editorModeLabel}
                          modes={editorModes}
                          pending={editorPending}
                        />
                      )}
                      {expandable && (
                        <ViewExpandToggle
                          expansion={expansion}
                          ref={expandViewRef}
                        />
                      )}
                      {freshness}
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

            {/* The conditions, on a surface of their own. The block exists
                only where there is something in it: an empty card is the
                promise of an editor that is not there. */}
            {editor != null &&
              (folded ? (
                <EditorBand
                  id={editorId}
                  open={editorIsOpen.open}
                  className={cn(SURFACE, SPACE.ROWS)}
                >
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
                  className={cn('flex flex-col', SURFACE, SPACE.ROWS)}
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

            {/* Between the blocks rather than inside either: what the view
                reports is about the whole view, not about its conditions and
                not about its rows. */}
            <ErrorStrip
              issues={filter.unmarked}
              title={
                kind === 'dashboard'
                  ? messages.label('label.dashboard.needs-fixing')
                  : undefined
              }
            />
            {/* Warnings block nothing — the result below is the real one — so
                they sit under the errors and never replace it. */}
            <WarningStrip issues={warnings ?? state.issues} />

            {/* The result, and at the top of it the caption that says what
                it is: the applied bar describes these rows, so it belongs to
                them rather than floating above the toolbar on its own.

                Only where one of the three will draw something. An analysis
                that has not run yet has no result, no caption and no strip,
                and a bordered card around all three of them is the empty
                block this package's own layout rule forbids. */}
            {(describesResult || filled(strips) || filled(result)) && (
              <ResultBlock surface={resultSurface}>
                <AppliedBar filter={filter} hasResult={describesResult} />
                {strips}
                {/* The host's bulk and row slots render in here, so this is
                    where a throwing one is held: the rows go, the title bar,
                    the editor and the draft stay. */}
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

/**
 * The result and its caption, boxed or not.
 *
 * A dashboard opts out: every panel it draws is already a card, and a card
 * around a grid of cards is a frame around a frame.
 */
function ResultBlock({
  surface,
  children,
}: {
  surface: boolean;
  children: ReactNode;
}) {
  return (
    <section
      data-slot="result-block"
      className={cn('flex min-w-0 flex-col', SPACE.ROWS, surface && SURFACE)}
    >
      {children}
    </section>
  );
}

/**
 * The editor's fold, which belongs to one opening of one view.
 *
 * The state is tagged with the runtime it was made for rather than reset by
 * an effect: switching views re-reads the default in the same render that
 * shows the new view, so the band is never briefly the previous view's.
 */
function useEditorFold({
  controlled,
  fallback,
  runtimeId,
  onChange,
}: {
  controlled: boolean | undefined;
  fallback: boolean;
  runtimeId: string | null;
  onChange?(open: boolean): void;
}): { open: boolean; set(open: boolean): void } {
  const [held, setHeld] = useState<{ id: string | null; open: boolean } | null>(
    null,
  );
  const mine = held !== null && held.id === runtimeId;
  return {
    open: controlled ?? (mine ? held.open : fallback),
    set(open) {
      setHeld({ id: runtimeId, open });
      onChange?.(open);
    },
  };
}

/**
 * Whether a slot was given something that will draw.
 *
 * A workbench fills a slot with `condition && <Thing/>`, so an unfilled one
 * arrives as `false` rather than as nothing at all — and a block that
 * counted it as content would be the empty card the layout rule forbids.
 */
function filled(slot: ReactNode): boolean {
  return slot !== null && slot !== undefined && slot !== false;
}
