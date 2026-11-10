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

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  parseSystemInstanceId,
  sameJson,
  type FilterNode,
  type Issue,
  type ViewConfig,
  type ViewInstance,
  type ViewKind,
} from '../model/index.js';
import type {
  AnyViewRuntime,
  BoardOrigin,
  SavedViewTarget,
  ViewEngine,
  ViewHandOver,
  ViewNavigation,
  ViewRuntimeState,
  WriteAction,
} from '../runtime/index.js';
import { lookedOnly, untouchedSince } from '../runtime/navigation.js';
import { kindMismatch } from './issues.js';
import { useAutoRefresh, type RefreshController } from './useAutoRefresh.js';
import {
  useFilterEditor,
  type FilterEditorController,
} from './useFilterEditor.js';
import { useSaveCommands, type SaveCommands } from './useSaveCommands.js';
import {
  useOpenView,
  type DashboardOpening,
  type OpenViewState,
} from './useOpenView.js';
import { useViewRuntime } from './useViewRuntime.js';
import { useViewList, type ViewListState } from './useViewList.js';
import {
  useViewManager,
  type ViewManagerController,
} from './useViewManager.js';
import { useInstanceSync } from './workbench/instanceSync.js';
import { useSwitching } from './workbench/switching.js';
import { useLeaveGuard, type LeaveGuard } from './workbench/leaveGuard.js';
import type { NewViewOptions } from './workbench/newView.js';
import { useReleaseDeleted } from './workbench/releaseDeleted.js';
import {
  namedState,
  useHeldCommands,
  useHold,
  type DrillTarget,
  type HeldView,
} from './workbench/heldView.js';
import {
  useHandedConditions,
  useHandedRemoval,
  useHandOver,
} from './workbench/handOver.js';

export interface WorkbenchOptions {
  /**
   * The kinds this workbench has parts for. They narrow the list — and with
   * it the effective default — and an instance of any other kind is reported
   * as unopenable. A data workbench draws record and analysis views in one
   * list (D20); a host narrows it to one kind by naming only that one (D9).
   */
  kinds: readonly ViewKind[];
  /**
   * Which view is open, as `value` is on an input: leaving it out is the
   * uncontrolled form and the workbench owns the open view from the effective
   * default on; passing it — a string, or `null` for that effective default —
   * makes the host the one that says which view is open, and every later
   * change of it opens that view.
   *
   * It converges rather than renders, because a view holds an unsaved draft:
   * a pushed value goes through the same leave guard a click goes through,
   * and `onInstanceChange` says what is open now. See
   * `workbench/instanceSync.ts` for the whole of that contract.
   */
  instanceId?: string | null;
  /**
   * Told which view is open whenever that changes: the user picked another,
   * a save-as opened its copy, a rename kept it, or a deleted view let the
   * pin go. `null` means the effective default, exactly as it does in
   * `instanceId`, so what comes out goes back in unchanged — which is what
   * makes a workbench addressable by a route.
   */
  onInstanceChange?(id: string | null): void;
  /**
   * Whether closing the tab is guarded as well as switching views. On by
   * default; a host that mounts this workbench as one part of a larger page
   * — or renders it on a server — can say no. See `workbench/leaveGuard.ts`.
   */
  guardUnload?: boolean;
  /**
   * What a view made from nothing opens as: the name it carries until its
   * first save, and the config it starts from. Left out, the workbench
   * offers no new view — the engine refuses a view with no title, and the
   * title is wording, which this layer does not carry. `/ui` passes its
   * catalogue's word; a host's own template goes in `templates`. A view
   * opened out of another is named by whoever opens it (`drill`, `follow`),
   * so this has no say there.
   */
  newView?: NewViewOptions;
  /**
   * Takes over drilling (H5): given, the workbench builds the record view a
   * row would open and hands it here instead of holding it — a host that
   * wants the records on a page of its own opens them there. `canDrill` is
   * unchanged by it; the gesture exists exactly when there is something to
   * drill into.
   */
  onDrilldown?(target: DrillTarget): void;
  /**
   * What a dashboard opens with (D22 E, F), asked as each view opens: the
   * tab and the filter values a host's route names. Nothing, and the board
   * opens where its reader last read it, every filter at its default
   * (`ViewEngine.open`).
   */
  opening?(instanceId: string): DashboardOpening | undefined;
  /**
   * A view a dashboard or an embed handed the host's route, to open here
   * (D22 H, D26 Q30): a saved one (`view`), opened by its id with what the
   * reader set on the board among its own conditions, or one nobody saved
   * (`unsaved`) — a follow-up on a group, a board's own analysis — opened as
   * a view made from nothing. Either runs under what the page holds as its
   * scope. Each new object opens once, through the leave guard
   * (`useHandOver`).
   */
  handOver?: ViewHandOver | null;
  /**
   * The host's route, which the way back to the board a view was handed
   * from goes by (`board`, D26 Q33). Without it there is no way back drawn.
   */
  onNavigate?(to: ViewNavigation): void;
}

/**
 * Everything a workbench needs that is not its own markup: which views there
 * are, which one is open, what it says, and what happens when the user moves
 * to another.
 *
 * The default workbenches differ in their editor and their result and in
 * nothing else, so the shell around those two is assembled once here. A host
 * that writes its own markup calls this and loses none of the rules — the
 * wrong-kind report, the released pin, the leave guard and the header's four
 * outcomes are all in the controller rather than in the components.
 */
export interface WorkbenchController {
  /** The kinds this workbench draws, as it was given them. */
  kinds: readonly ViewKind[];
  /**
   * 「改了就跑」 (D20): whether an analysis runs again on its own as its
   * question is edited — the user's preference for this definition, on
   * when unsaid, pushed onto the open analysis view. The range still waits
   * for Apply either way.
   */
  autoRun: boolean;
  setAutoRun(on: boolean): Promise<void>;
  /** The views of this definition, of those kinds, in the user's order. */
  list: ViewListState;
  manager: ViewManagerController;
  /** What is open now: the explicit choice, or the effective default. */
  openId: string | null;
  /** Opens another view, through the leave guard. */
  choose(id: string | null): void;
  /**
   * The kinds a new view may be made of here, in the order of `kinds`: the
   * definition has the kind, the user may create in some audience, and
   * `newView` gave it a name. Every "new view" control exists on this and
   * on nothing else (D4) — one kind is a button, several are a menu, none is
   * no control.
   */
  creatable: readonly ViewKind[];
  /**
   * Opens a view of that kind made from nothing, through the leave guard;
   * a kind not in `creatable` does nothing. It is unsaved until its first
   * save, which is a create: the workbench then opens what the store took,
   * as it does a copy. An unsaved view has no id, so a host routing the
   * workbench hears nothing until that save lands.
   */
  create(kind: ViewKind): void;
  /**
   * The view the workbench holds instead of opening by id, if any: one made
   * from nothing, or one opened out of another (`drill`, `follow`). Its
   * `origin` is what the "from" line reads.
   */
  held: HeldView | null;
  /**
   * Whether a row of the open analysis view can open the records behind it
   * (H2): the workbench draws record views and the definition has them.
   * The drill gesture exists on this and on nothing else.
   */
  canDrill: boolean;
  /**
   * Opens the records behind one row of the open analysis view: an unsaved
   * record view under the origin's conditions plus `conditions`, held with
   * its origin so the way back is on screen (D20). The origin stays open
   * with the result it was drilled on, so going back shows that result
   * rather than running it again. Nothing is left behind, so the leave guard
   * is not asked. Does nothing while `canDrill` is false.
   *
   * `title` is what the records open under until they are saved — wording,
   * which this layer does not carry, so the caller says it: `/ui` names them
   * by what they are, the definition's records of that group (D20 追问).
   * `subject` is that name without the group, which the view goes by once
   * `conditions` no longer narrow it (`GroupNaming`); left out, `title`
   * stands whatever the conditions become.
   */
  drill(
    conditions: readonly FilterNode[],
    title: string,
    subject?: string,
  ): void;
  /**
   * Opens `config` as a view of its own, held with the open view as its
   * origin: the follow-up that narrows an analysis to one group (D20 追问
   * 「只显示这一组」) is a new question beside the one it came from, not an
   * edit that writes over it. The origin stays open with its result, so
   * `back()` shows that result again without running it; the view opened is
   * unsaved, named `title`, under the origin's injected scope, and saving it
   * is a first save like any new view's. `conditions` are what the follow-up
   * added, kept on the origin for whoever reads it. The origin keeps its
   * draft, so the leave guard is not asked. `subject` is as `drill`'s.
   */
  follow(
    config: ViewConfig,
    title: string,
    conditions: readonly FilterNode[],
    subject?: string,
  ): void;
  /**
   * Returns to the held view's origin, through the leave guard: an untouched
   * view opened out of another goes without a question, one the user shaped
   * is asked about. Does nothing while nothing held has an origin.
   */
  back(): void;
  /**
   * The board the open view was handed from (D26 Q33), while it is the view
   * on screen and a route goes back: the shell draws 「返回〈仪表盘〉」 from
   * it. `null` under a view opened out of another here, whose own way back
   * (`back`) is the one on screen.
   */
  board: BoardOrigin | null;
  /** Goes back to `board` through the host's route, past the leave guard. */
  toBoard(): void;
  /**
   * Whether the view on screen is one a board or an embed handed over
   * (`handOver`, D26 Q30), saved or not: the press that sent it took the
   * keyboard off the page it was on, so the shell gives it to the title.
   */
  handedOver: boolean;
  opened: OpenViewState;
  /** Null while the view is unopenable or still loading. */
  runtime: AnyViewRuntime | null;
  /**
   * The open view's state — under the name it goes by now: a held view
   * named by a group whose conditions it no longer runs under is called by
   * its `subject` (`HeldView.named`), so the title bar, 「另存为」 and a
   * follow-up opened from it never name a group it does not show.
   */
  state: ViewRuntimeState<ViewConfig> | null;
  /**
   * Why there is nothing to draw: the open failed, or it opened and is of
   * another kind. A host may name any id, so a view this page has no kernel
   * for is reported where every other failure to open is, rather than left
   * as a title bar over an empty body.
   */
  unopenable: Issue | null;
  /**
   * What admission found wrong with the definition itself — a system view
   * the kernel refuses, a field group naming a field it does not have.
   * The shell shows them in the status line beside the view's own, since a
   * definition's error is otherwise reported to `onIssue` alone and the
   * screen said nothing.
   */
  definitionIssues: Issue[];
  /**
   * Where the open view sits in the definition's `views`, when it is one
   * the definition declares in code, else `null`. A definition finding under
   * `['views', n, …]` with this `n` is about the view on screen — which, for
   * a board, reports its own panels' findings by name as well (`panels`),
   * so the status line says each once.
   */
  declaredAt: number | null;
  commands: SaveCommands;
  /**
   * How the open view renews its own answer: one refresh now, and the
   * interval it keeps itself up to date by. It is assembled here rather than
   * in each workbench because `refresh` lives on `ViewConfigBase` — every
   * kind has one, and every kind reaches it the same way.
   */
  refresh: RefreshController;
  /**
   * The open view's filter editor. One per opening, built here because the
   * shell needs it too — the applied bar describes it, and the error strip
   * shows exactly the errors it does not mark (`filter.unmarked`).
   */
  filter: FilterEditorController;
  leave: LeaveGuard;
  /** A saved copy is what the workbench then shows. */
  onSaved(instance: ViewInstance): void;
  /**
   * A rename keeps the view. The pin goes on before the reload: a workbench
   * riding on the default would otherwise close its runtime and lose the
   * draft.
   */
  onRenamed(instance: ViewInstance): void;
  /**
   * A delete moves on. The engine let the runtime go with the instance, and
   * it has already told the list which id went (D15), so all that is left
   * here is to let the pin go: the default moves, and the open id follows it
   * — or empties with the list.
   */
  onDeleted(): void;
  /** A recovered write may have changed the list; read it again. */
  onRecovered(action: WriteAction): void;
}

/**
 * The shell of one workbench, without any of its look.
 *
 * The kinds are a parameter: what differs between record, analysis and
 * dashboard is the editor and the result they render from `runtime`, not the
 * way a view is found, opened, left or saved.
 *
 * One command is the exception, and it is the follow-up seam rather than an
 * oversight: `drill` opens the records behind one group of an analysis
 * result (D20 追问). It names both kinds on purpose — `canDrill` asks the
 * open view to be an `analysis` and `record` to be among the kinds this
 * workbench lists, and what it opens is a `RecordViewConfig` built from
 * `defaultRecordConfig` with `drillFilter` over the conditions that ran.
 * Nothing else here reads a kind.
 */
export function useWorkbench(
  engine: ViewEngine,
  definitionId: string,
  options: WorkbenchOptions,
): WorkbenchController {
  const {
    instanceId,
    onInstanceChange,
    guardUnload,
    newView,
    onDrilldown,
    opening,
    onNavigate,
  } = options;
  // Held by what they say: a host writes the array inline, so the object is
  // new every render while the kinds in it are not.
  const kindsKey = options.kinds.join(' ');
  const kinds = useMemo(() => kindsKey.split(' ') as ViewKind[], [kindsKey]);
  // Only the views this page can open: the sidebar offers no view the body
  // cannot render, and the effective default is resolved among those alone.
  const list = useViewList(engine, definitionId, { kinds });
  const [chosen, setChosen] = useState<string | null>(instanceId ?? null);
  const openId = chosen ?? list.defaultInstanceId;

  // A view the workbench holds rather than opens by id; see `useHold`.
  const { held, heldRef, hold } = useHold(engine);

  // A saved view a board handed over (D26 Q30): the page's hold is its
  // scope for as long as it is the view opened by id, and goes when the
  // reader picks another.
  const [handed, setHanded] = useState<SavedViewTarget | null>(null);
  const handedHere =
    handed &&
    handed.instanceId === openId &&
    handed.definitionId === definitionId
      ? handed
      : null;
  // A view made from nothing releases the one opened by id; a drilled view
  // keeps it, because it is the origin.
  // Only this definition's views: an id of another's — an address two
  // workbenches share, a host's slip — is refused before it runs.
  const opensHere = useCallback(
    (id: string) => ({ ...opening?.(id), definitionId }),
    [opening, definitionId],
  );
  const byId = useOpenView(engine, held && !held.origin ? null : openId, {
    scopeFilter: handedHere?.scopeFilter ?? null,
    opening: opensHere,
  });
  const opened: OpenViewState = held
    ? {
        runtime: held.runtime,
        status: 'success',
        error: null,
        scopeIssues: [],
        // Never offered: a view held here was made, and did not fail to open.
        retry: byId.retry,
      }
    : byId;
  const wrongKind = held ? null : kindMismatch(byId.runtime, kinds);
  const runtime = held ? held.runtime : wrongKind ? null : byId.runtime;
  const current = useViewRuntime(runtime);
  const handedDraft = useHandedConditions(
    handedHere,
    wrongKind ? null : byId.runtime,
  );
  const state = useMemo(() => namedState(current, held), [current, held]);
  const autoRun = list.preferences.value?.autoRun ?? true;
  // The preference reaches every runtime alike; which members run on their
  // own is the model's to say per kind (`autoRunMembers`), and a kind that
  // declares none never does. Read whenever it or the open view changes.
  // Held off until the preferences have answered: the default `true` above
  // stands in only for a preference that is not there, and a reader who
  // turned it off would otherwise have an edit made before the answer run
  // on its own. One made while held runs once the answer turns it on.
  const preferencesSettled = list.preferences.status !== 'loading';
  useEffect(() => {
    runtime?.setAutoApply(preferencesSettled && autoRun);
  }, [runtime, autoRun, preferencesSettled]);
  // A handed saved view's ✕ takes a board's condition off (D26 Q30); a
  // view held here is another runtime, and clears as any view does.
  const filter = useHandedRemoval(useFilterEditor(runtime), {
    handed: held ? null : handedHere,
    runtime,
  });
  const refresh = useAutoRefresh(runtime);
  const commands = useSaveCommands(engine, runtime);
  const manager = useViewManager(engine, definitionId, { list });
  const leave = useLeaveGuard(
    state
      ? {
          // A held view counts as dirty from the start — losing it loses
          // everything — but one nobody has touched yet holds nothing worth
          // a question: the draft is the config it opened with.
          // A saved view handed over is the same: what the board added is
          // its only change until the reader makes another, and the way
          // back puts that on the board again (D26 Q33). Compared as it
          // is, keys and all: shaped and shaped back is untouched.
          // A search, a sort or a page size on a view this reader cannot
          // save in place is a look-up, not work (R2-39).
          dirty: held
            ? !sameJson(state.draft, held.draft)
            : state.dirty &&
              !untouchedSince(state.draft, handedDraft) &&
              (commands.can.save || !lookedOnly(state, runtime?.definition)),
          write: state.write,
        }
      : null,
    // Leaving settles the outcome first, because the runtime it belongs to
    // is about to go.
    {
      onLeave: () => commands.abandon(),
      guardUnload,
      // Saved in place, then left (R2-100): a personal view saved before,
      // that may be saved in place and has nothing in the way of a new
      // write. A shared one is not: its save asks first, since it changes
      // what everyone sees, and a way around that question is no answer.
      ...(state?.saved &&
        !held &&
        commands.can.save &&
        commands.state.audience === 'personal' &&
        !commands.state.hasErrors &&
        state.write === null && { save: commands.save }),
    },
  );
  useReleaseDeleted({ openId, chosen, opened: byId, setChosen });

  // Read off the guard rather than the guard itself: `request` and `cancel`
  // are its stable parts, and the object is new on every render.
  const { request, cancel: withdraw } = leave;
  const { choose, openHanded } = useSwitching({
    chosen,
    setChosen,
    held,
    hold,
    handed,
    setHanded,
    request,
    withdraw,
  });
  useHandOver(options.handOver, {
    engine,
    definitionId,
    kinds,
    request,
    hold,
    open: openHanded,
  });
  // Which view is open is the one piece of workbench state a host may also
  // hold — a route, a link somebody shares — so the two are kept in
  // agreement here, once, for every workbench and every hand-built one.
  useInstanceSync({
    instanceId,
    chosen,
    choose,
    asking: leave.asking,
    onInstanceChange,
    handOver: options.handOver,
  });

  const definition = engine.definitions.get(definitionId);
  // The ways into and out of a held view; see `useHeldCommands`.
  const heldCommands = useHeldCommands({
    engine,
    definitionId,
    kinds,
    definition,
    newView,
    onDrilldown,
    onNavigate,
    runtime,
    state,
    held,
    heldRef,
    hold,
    request,
    handedHere,
  });

  const reload = list.reload;
  const open = useCallback(
    (instance: ViewInstance) => {
      // What the store took is what the screen shows from here, opened by
      // its id like any other view; a held view that was just saved is let
      // go for the instance it became.
      hold(null);
      setChosen(instance.id);
      reload();
    },
    [reload, hold],
  );

  return {
    kinds,
    autoRun,
    setAutoRun: useCallback(
      async (on: boolean) => {
        await engine.setAutoRun(definitionId, on);
        reload();
      },
      [engine, definitionId, reload],
    ),
    list,
    manager,
    openId,
    choose,
    ...heldCommands,
    held,
    handedOver: held ? held.handed === true : handedHere !== null,
    opened,
    runtime,
    state,
    unopenable: opened.error ?? wrongKind,
    definitionIssues: engine.definitionIssues(definitionId),
    declaredAt: declaredAt(definition, state?.saved?.id),
    commands,
    filter,
    refresh,
    leave,
    onSaved: open,
    onRenamed: open,
    // No reload from here: the engine's notification is already out, carrying
    // the id that went, and a plain reload would take that id back out of the
    // request — listing the row, and naming it as the default, until the
    // store answers.
    onDeleted: useCallback(() => setChosen(null), []),
    onRecovered: useCallback(() => reload(), [reload]),
  };
}

/** Where the view `id` names sits in the definition's `views`, or `null`. */
function declaredAt(
  definition: { id: string; views?: readonly { id: string }[] } | undefined,
  id: string | undefined,
): number | null {
  const declared = id ? parseSystemInstanceId(id) : null;
  if (!definition || declared?.definitionId !== definition.id) return null;
  const at = (definition.views ?? []).findIndex(
    view => view.id === declared.viewId,
  );
  return at < 0 ? null : at;
}
