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

/**
 * The open view, drawn in the work column: its title bar with the editor's
 * fold, where it came from, its status line, its conditions, the conditions
 * its rows came back under, and the result. It holds what only the open view
 * needs — the editor's fold and the keyboard the fold takes, the query's
 * strip, and where the keyboard lands when a view replaces another — and is
 * drawn by `WorkbenchShell` for as long as the shell is, so what one opening
 * hands the next survives the views in between (it draws nothing while no
 * view is open).
 */

import {
  useCallback,
  useId,
  useLayoutEffect,
  useRef,
  type ReactNode,
} from 'react';
import type * as React from 'react';
// Aliased: `hasResult` here is the prop a dashboard overrides it with.
import {
  hasAsked as viewHasAsked,
  hasResult as viewHasResult,
} from '../../runtime/index.js';
import type { ViewKind } from '../../model/index.js';
import { AppliedBar } from '../filter/AppliedBar.js';
import { focusIn } from '../kit/focus.js';
import { QueryStrip } from '../kit/StatusStrip.js';
import { ConditionBlock } from './ConditionBlock.js';
import { EditorFold } from './EditorBand.js';
import { OriginBar } from './OriginBar.js';
import { resultBlockShown, ShellResult } from './ResultBlock.js';
import { StatusLine } from './StatusLine.js';
import { TitleBar } from './TitleBar.js';
import { filled, useEditorFold } from './useEditorFold.js';
import type { useWorkbenchFolds } from './useWorkbenchFolds.js';
import type { WorkbenchShellProps } from './WorkbenchShell.js';

type Folds = ReturnType<typeof useWorkbenchFolds>;

/** What the shell hands the open view: its own props, and what it worked out. */
export interface OpenViewColumnProps extends Omit<
  WorkbenchShellProps,
  | 'title'
  | 'colorMode'
  | 'preset'
  | 'density'
  | 'tokens'
  | 'messages'
  | 'locale'
  | 'timeZone'
  | 'clock'
  | 'defaultSidebarOpen'
  | 'onSidebarOpenChange'
  | 'manage'
  | 'panel'
  | 'onPanelClose'
  | 'landmark'
  | 'className'
  | 'expandable'
  | 'resultFramed'
> {
  /** A view opened and is this page's to draw; nothing is drawn otherwise. */
  open: boolean;
  /** The kind on screen, which names the status line's words. */
  kind: ViewKind | undefined;
  /** The title's id, which the work column is labelled by. */
  titleId: string;
  /** Whether the title bar names the view: the sidebar is beside it. */
  namesView: boolean;
  /** What leads the title bar while the sidebar is away. */
  leading: ReactNode;
  fill: Folds['fill'];
  /** `WorkbenchShellProps.expandable`, its default already taken. */
  expandable: boolean;
  /** `WorkbenchShellProps.resultFramed`, its default already taken. */
  resultFramed: boolean;
  expandViewRef: Folds['expandViewRef'];
}

export function OpenViewColumn({
  open,
  kind,
  titleId,
  namesView,
  leading,
  fill,
  expandViewRef,
  workbench,
  expandable,
  resultFramed,
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
  hasResult,
  applied = true,
  resultPending,
  warnings,
  nameIssue,
  onRenderFailure,
  resultSlots,
  resultWithoutQuery = false,
}: OpenViewColumnProps) {
  const editorId = useId();
  const { filter, runtime, state } = workbench;
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

  // Where the keyboard lands when a view replaces another; see `useTitleFocus`.
  const { viewTitle, onCreated } = useTitleFocus(
    open,
    runtime?.id ?? null,
    workbench.handedOver,
  );

  const folded = editorLabel !== undefined && editor != null;
  // A caught failure belongs to the view it happened in: opening another
  // view draws its parts afresh rather than carrying the fallback over.
  const resetKeys = [runtime?.id ?? null];
  const editorIsOpen = useEditorFold({
    controlled: editorOpen,
    // A new view opens with its editor out, since there is nothing in it yet
    // — but not one opened from another's group, nor one a host handed over
    // (a dashboard's follow-up, D22 H): its conditions are what it was
    // opened with, the applied bar says them, and an editor unfolded over
    // them says them twice more (2026-09-23 audit).
    fallback:
      defaultEditorOpen ??
      (state?.saved === null &&
        !workbench.held?.origin &&
        !workbench.held?.handed),
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

  // A press that opens the editor takes the keyboard in; see `useEditorKeyboard`.
  const openEditor = useEditorKeyboard(editorIsOpen, editorId, filter.blur);

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
            conditionsChanged={state?.query.conditionsChanged === true}
            onRetry={() => runtime?.refresh()}
          />
        );

  if (!open || runtime === null || state === null) return null;
  return (
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
          namesView={namesView}
          leading={leading}
          editorLabel={folded ? editorLabel : undefined}
          editorModes={editorModes}
          editorPending={editorPending}
          freshness={freshness}
          busy={querying}
          expandable={expandable}
          fill={fill}
          expandViewRef={expandViewRef}
          onCreated={onCreated}
          build={build}
          commitElsewhere={commitElsewhere}
        />

        {/* Where this view came from, when it was opened out of another
        (D20) or handed over from a dashboard (D26 Q33): the way
        back, naming the origin, under the title bar and before
        anything the view says about itself. The workbench holds
        it; no kind's parts know it exists. */}
        {workbench.held?.origin ? (
          <OriginBar
            title={workbench.held.origin.title}
            from="view"
            onBack={workbench.back}
          />
        ) : (
          workbench.board && (
            <OriginBar
              title={workbench.board.title}
              from="dashboard"
              onBack={workbench.toBoard}
            />
          )
        )}

        <StatusLine
          workbench={workbench}
          state={state}
          kind={kind}
          warnings={warnings}
          besideResult={besideResult}
          nameIssue={nameIssue}
          conditionsHidden={editor == null || (folded && !editorIsOpen.open)}
          {...(editor != null &&
            folded && {
              onShowConditions: () => editorIsOpen.set(true),
            })}
          errorAction={
            typeof errorAction === 'function'
              ? errorAction(editor != null && (!folded || editorIsOpen.open))
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
          className="fve:flex fve:flex-wrap fve:items-center fve:gap-2"
        >
          <AppliedBar
            filter={filter}
            asked={asked && applied}
            className="fve:min-w-0 fve:grow"
          />
          <div className="fve:ml-auto">{search}</div>
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
        hasResult: describesResult || resultWithoutQuery,
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
  );
}

/**
 * Where the keyboard lands after a copy is created, or when a view replaces
 * another under it: the open view's title (the block below says why). Held
 * here, across openings, because the title bar unmounts between them.
 */
function useTitleFocus(
  open: boolean,
  openedId: string | null,
  handedOver: boolean,
): {
  viewTitle: React.RefObject<HTMLHeadingElement | null>;
  /** What the title bar calls once a copy is created: its title takes the keyboard. */
  onCreated: () => void;
} {
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
  //
  // The same holds for any view that replaces another under the keyboard: a
  // follow-up's 「查看这些记录」 opens a record view where the row it was
  // pressed on stood, and the origin bar's 「返回」 goes with the view it
  // was on — both dropped the keyboard on `<body>` (the 2026-09-25 keyboard
  // walkthrough). So a view drawn
  // in place of another takes the keyboard when it has nowhere else to be —
  // on `<body>`, never out of a control that still holds it (the list the
  // view was chosen in keeps it). The first view of all is not a swap: a
  // page that has just loaded keeps its focus where the host put it —
  // unless a board handed it over (「在工作台中打开」, a press on a group,
  // D26 Q30): that press took the keyboard off the board's page, and it
  // lands on the title of what it opened, as a drill does.
  const shownId = useRef<string | null>(null);
  const swapped = useRef(false);
  useLayoutEffect(() => {
    if (openedId !== null && openedId !== shownId.current) {
      swapped.current = shownId.current !== null || handedOver;
      shownId.current = openedId;
    }
    if (!open || openedId === null) return;
    const fell =
      swapped.current &&
      (document.activeElement === null ||
        document.activeElement === document.body);
    swapped.current = false;
    if (!created.current && !fell) return;
    created.current = false;
    viewTitle.current?.focus();
  }, [open, openedId, handedOver]);
  const onCreated = useCallback(() => {
    created.current = true;
  }, []);
  return { viewTitle, onCreated };
}

/**
 * A press that opens the editor takes the keyboard into it, and folding it
 * away ends the editing its inputs started (the blocks below say why).
 * Returns the fold's `onOpenChange`.
 */
function useEditorKeyboard(
  editorIsOpen: ReturnType<typeof useEditorFold>,
  editorId: string,
  blurEditor: () => void,
): (next: boolean) => void {
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
  const wasOpen = useRef(editorIsOpen.open);
  useLayoutEffect(() => {
    const open = editorIsOpen.open;
    if (wasOpen.current === open) return;
    wasOpen.current = open;
    if (!open) blurEditor();
  }, [editorIsOpen.open, blurEditor]);
  return openEditor;
}
