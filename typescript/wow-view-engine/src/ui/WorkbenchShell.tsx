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
import { FileQuestionMarkIcon, PanelLeftOpenIcon } from 'lucide-react';
import type { Issue, ViewKind } from '../model/index.js';
import type { WorkbenchController } from '../react/index.js';
import { Button } from './components/button.js';
import { IconButton } from './IconButton.js';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from './components/empty.js';
import { Skeleton } from './components/skeleton.js';
import { AppliedBar } from './AppliedBar.js';
import { EditorBand, EditorBandToggle } from './EditorBand.js';
import { SPACE, TRAY } from './layout.js';
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

/**
 * The width below which the list stops being *beside* the view.
 *
 * It is Tailwind's `md`, written as the number the stylesheet uses, because
 * this is the same threshold the surface changes direction at: above it the
 * shell is a row and the column stands next to the work area; below it the
 * shell is a column and the list is a block on top of the result.
 */
const NARROW = 768;

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
   * The sidebar a workbench opens on. One boolean governs it, so collapsing
   * is a change in one place rather than in the layout of every part beside
   * it. It is view state and nothing else: never saved, never asked about by
   * the leave guard.
   *
   * Left out, the shell decides from the room it was actually given: a
   * column narrower than `md` opens folded, because below that width the
   * list is not beside the view but stacked on top of it, and 204px of
   * navigation above the first row is the worst trade a phone can make. A
   * host that says `true` or `false` is obeyed at every width — it knows
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
   * Whether the result below was ever asked for; the applied bar renders
   * nothing until it was. The open view's own result when left out — a
   * dashboard has none of its own and answers from its panels instead.
   */
  hasResult?: boolean;
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
  strips,
  result,
  defaultSidebarOpen,
  onSidebarOpenChange,
  expandable = true,
  hasResult,
  warnings,
  onRenderFailure,
  resultFramed = true,
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

  // The fold as the *page* has it — the one a host sets and is told about.
  // What is actually on screen is derived from it below, because a screen
  // filled by the view has a fold of its own.
  const [pageSidebarOpen, setPageSidebarOpen] = useState(
    defaultSidebarOpen ?? true,
  );
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
  //
  // But a switch that is merely *loading* keeps it (Q7, decided 2026-09-21):
  // filling the screen is a posture of the workspace, not a state of the
  // view that happened to be open — the user asked for room for the rows,
  // and the next view's rows want the same room. The old runtime's release
  // is one render with nothing open and the next one already on its way;
  // ending the fill there made every switch a way out nobody had taken.
  // The title bar is back the moment the view is, and Escape works in
  // between.
  const expansion = useViewExpansion(
    surfaceRef,
    expandViewRef,
    expandable && (open || opened.loading),
  );
  // Filling the screen is a screen of its own, and the list is folded for
  // it: filling is a gesture about the *result* — "give the rows the room" —
  // and a 224px column of navigation is the first thing that is not the
  // result. On a phone it is worse than that, because below `md` the list is
  // not even beside the rows: it stacks above them and the table starts
  // 204px down, which is the fewest rows a filled screen has ever bought.
  //
  // So the fold is two answers rather than one, and which is in force is
  // **derived** from whether the screen is filled. Nothing is remembered and
  // nothing is put back: leaving reads the page's answer again because it is
  // still there. The alternative — one boolean, folded and restored by an
  // effect watching the expansion — is a cascading render for a value the
  // render can simply work out, and it was also two rules pretending to be
  // one.
  //
  // `inFill` starts folded on every fill, which is what `fill.toggle` below
  // is for: pressing that button is the only way *into* a fill (Escape and
  // switching views only end one), so it is the one place the previous
  // fill's answer has to be let go.
  const expanded = expansion.expanded;
  const [inFill, setInFill] = useState(false);
  const sidebarOpen = expanded ? inFill : pageSidebarOpen;
  const fill = {
    expanded,
    toggle: () => {
      setInFill(false);
      expansion.toggle();
    },
  };

  // The state the last run saw, not "has this run before". StrictMode does
  // setup, cleanup, setup on mount, so a "first run" flag is already spent
  // by the second setup and the effect would take focus off the host's page
  // on arrival — which is the one thing it must never do. Comparing the
  // value answers the question actually being asked: did this change?
  const shown = useRef(sidebarOpen);
  // And *why* it changed. The fold now moves for three reasons — a button,
  // a measurement on arrival, the screen being filled — and only the first
  // is a press. Focus follows a press because the press took its own button
  // off the screen; it must not follow the other two, which would take the
  // keyboard out of whatever the user was doing, on arrival or on the way
  // into a filled screen.
  const pressed = useRef(false);
  useLayoutEffect(() => {
    if (shown.current === sidebarOpen) return;
    shown.current = sidebarOpen;
    if (!pressed.current) return;
    pressed.current = false;
    // Collapsing and expanding each take away the button that was just
    // pressed, so focus moves to the one that undoes it. Both are held as
    // refs rather than found again by selector: the buttons live in two
    // different components, and a shell that went looking for one in the
    // document would be reaching past both of them.
    (sidebarOpen ? collapseRef : expandRef).current?.focus();
  }, [sidebarOpen]);

  // The room this workbench was actually given, measured once on arrival.
  //
  // Below `md` the list is not beside the view but stacked over it, so a
  // column that narrow opens folded. It is the *surface's* width that
  // answers, not the viewport's: a 360px panel on a wide page is the same
  // phone-shaped column, which is the lesson `ViewHeader` already learned
  // when its viewport `sm:` showed a full definition title beside a view
  // name truncated to one character.
  //
  // A width of 0 is jsdom, a detached tree, or a host that has not laid this
  // out yet, all saying nothing at all — and nothing is not a reason to
  // fold. A host that passed the boolean is obeyed at every width: it knows
  // something about its page that a measurement does not.
  const measured = useRef(false);
  useLayoutEffect(() => {
    if (measured.current || defaultSidebarOpen !== undefined) return;
    measured.current = true;
    const width = surfaceRef.current?.getBoundingClientRect().width ?? 0;
    if (width === 0 || width >= NARROW) return;
    setPageSidebarOpen(false);
    onSidebarOpenChange?.(false);
  }, [defaultSidebarOpen, onSidebarOpenChange]);

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
    pressed.current = true;
    // While the screen is filled the answer is the fill's, and it lasts as
    // long as the fill does: a user who wants the list back inside one gets
    // it, and the next fill still starts without it.
    if (expanded) setInFill(next);
    else setPageSidebarOpen(next);
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
          <h1
            data-slot="definition-title"
            className="hidden min-w-0 truncate text-sm font-semibold @md/header:block"
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
            and a workbench that could not be un-collapsed would be a trap. */}
        {!open && collapsed && <div className="flex min-h-10">{collapsed}</div>}

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

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {open && (
          <>
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
                          open={editorIsOpen.open}
                          onOpenChange={editorIsOpen.set}
                          controls={editorId}
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
                issues={filter.unmarked}
                title={
                  kind === 'dashboard'
                    ? messages.label('label.dashboard.needs-fixing')
                    : undefined
                }
              />
              {/* Warnings block nothing — the result below is the real one —
                  so they sit under the errors and never replace it. */}
              <WarningStrip issues={warnings ?? state.issues} />
            </div>

            {/* The conditions, on a surface of their own. The block exists
                only where there is something in it: an empty card is the
                promise of an editor that is not there. */}
            {editor != null &&
              (folded ? (
                <EditorBand
                  id={editorId}
                  open={editorIsOpen.open}
                  className={cn(TRAY, SPACE.ROWS)}
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

            {/* The applied-conditions band (D12 Ⅲ): what the rows on screen
                were fetched under, a line of its own between the editor and
                the result — neither inside the tray, which is the draft, nor
                inside the result block, which is the rows. It draws nothing
                until there is a result to describe. */}
            <AppliedBar filter={filter} hasResult={describesResult} />

            {/* The result itself (D12 Ⅴ–Ⅶ).

                Only where one of the two will draw something. An analysis
                that has not run yet has no result and no strip, and a block
                around neither of them is the empty block this package's own
                layout rule forbids. */}
            {(describesResult || filled(strips) || filled(result)) && (
              <ResultBlock framed={resultFramed}>
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
 * A view that could not be opened, with the way off the screen it leaves.
 *
 * It wears the same form as an empty result — icon, title, what happened,
 * one action — rather than the red block it used to be. The block said the
 * application had broken; what has happened is that one view of it is not
 * there, which is a normal thing for a link, a bookmark or a deleted view to
 * lead to, and the page around it is working fine. The reason is still said
 * in full: the `Issue` is the description, so "another kind" and "no longer
 * exists" stay apart.
 *
 * `role="alert"` stays on it. The form is calmer, but the fact is still that
 * what the user asked for is not on screen, and a reader who cannot see the
 * page changing needs to be told that as it happens.
 */
function Unopenable({
  issue,
  onDefault,
}: {
  issue: Issue;
  onDefault?(): void;
}) {
  const messages = useViewMessages();
  return (
    <Empty role="alert" data-slot="view-unopenable">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <FileQuestionMarkIcon />
        </EmptyMedia>
        <EmptyTitle>{messages.label('label.view.unopenable')}</EmptyTitle>
        <EmptyDescription>{messages.issue(issue)}</EmptyDescription>
      </EmptyHeader>
      {onDefault && (
        <EmptyContent>
          <Button variant="outline" size="sm" onClick={onDefault}>
            {messages.label('label.view.open-default')}
          </Button>
        </EmptyContent>
      )}
    </Empty>
  );
}

/**
 * The result and its caption, on no card of their own (D12).
 *
 * The card used to be here for every kind but the dashboard, and it was a
 * frame around a frame in all of them: a table draws its own header layer,
 * its own hairlines between rows and its own edges on the held columns, so a
 * border and 12px of padding around that put the first row of data behind
 * five layers of chrome. Without it the table runs to the block's edge and
 * the lines on screen are the table's own, which is the only set of lines
 * that means anything. A dashboard needed the opt-out to avoid a card of
 * cards; now nobody needs it, and the prop that carried it is gone rather
 * than left as a default nobody sets.
 */
function ResultBlock({
  framed,
  children,
}: {
  framed: boolean;
  children: ReactNode;
}) {
  return (
    <section
      data-slot="result-block"
      data-framed={framed || undefined}
      className={cn(
        'flex min-w-0 flex-col',
        framed
          ? // One frame round the result and nothing else (D12): the toolbar
            // is its top row and the pagination its bottom row, ruled off;
            // the rows run to its edge; what else lands in it — a query
            // strip, an empty state, cards — keeps a margin of its own.
            'border-border overflow-hidden rounded-lg border ' +
              '[&>[data-slot=result-toolbar]]:border-border [&>[data-slot=result-toolbar]]:border-b [&>[data-slot=result-toolbar]]:px-3 [&>[data-slot=result-toolbar]]:py-2 ' +
              '[&>[data-slot=record-pagination]]:border-border [&>[data-slot=record-pagination]]:bg-muted/40 [&>[data-slot=record-pagination]]:border-t [&>[data-slot=record-pagination]]:px-3 [&>[data-slot=record-pagination]]:py-2 ' +
              '[&>[data-slot=status-strip]]:m-3 [&>[data-slot=record-empty]]:my-6 [&>[data-slot=record-cards]]:p-3'
          : SPACE.ROWS,
      )}
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
