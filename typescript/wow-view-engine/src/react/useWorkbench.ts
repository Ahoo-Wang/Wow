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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  FilterNode,
  FilterTree,
  Issue,
  RecordViewConfig,
  ViewConfig,
  ViewInstance,
  ViewKind,
} from '../model/index.js';
import { drillFilter } from '../analysis/index.js';
import { defaultRecordConfig } from '../record/index.js';
import type {
  AnyViewRuntime,
  ViewEngine,
  ViewRuntimeState,
  WriteAction,
} from '../runtime/index.js';
import { kindMismatch } from './issues.js';
import { useAutoRefresh, type RefreshController } from './useAutoRefresh.js';
import {
  useFilterEditor,
  type FilterEditorController,
} from './useFilterEditor.js';
import { useSaveCommands, type SaveCommands } from './useSaveCommands.js';
import {
  useOpenView,
  useViewRuntime,
  type OpenViewState,
} from './useViewEngine.js';
import { useViewList, type ViewListState } from './useViewList.js';
import {
  useViewManager,
  type ViewManagerController,
} from './useViewManager.js';
import { useInstanceSync } from './workbench/instanceSync.js';
import { useLeaveGuard, type LeaveGuard } from './workbench/leaveGuard.js';
import { blankView, type NewViewOptions } from './workbench/newView.js';
import { useReleaseDeleted } from './workbench/releaseDeleted.js';

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
}

/**
 * Where a held view came from: the view it was drilled out of, and the
 * conditions the drill added. It is the workbench's fact, not the view's —
 * the "from" line under the title bar reads it, and going back is the
 * workbench's command — so it lives beside the held view rather than in its
 * config, which is what a save would keep.
 */
export interface ViewOrigin {
  /** The view drilled from, still open with the result it was drilled on. */
  runtime: AnyViewRuntime;
  title: string;
  /** What the drill added to the origin's conditions: the row's own. */
  conditions: readonly FilterNode[];
}

/** What a drill would open, as offered to a host that takes drilling over. */
export interface DrillTarget {
  definitionId: string;
  origin: ViewOrigin;
  /** The name the records open under, as the caller of `drill` gave it. */
  title: string;
  /** The record view: the origin's filter plus the row's, default columns. */
  config: RecordViewConfig;
  /** The origin's injected scope, which the drilled view inherits (H4). */
  scopeFilter: FilterTree | null;
}

/**
 * A view the workbench holds rather than opens by id: made from nothing, or
 * drilled out of another. The draft it opened with is kept so the leave
 * guard can tell an untouched one — which costs nothing to let go — from one
 * the user has already shaped.
 */
export interface HeldView {
  runtime: AnyViewRuntime;
  draft: ViewConfig;
  /** Null for a view made from nothing; a drilled view knows its origin. */
  origin: ViewOrigin | null;
  /**
   * The held view the origin itself was, when it was one: drilling out of an
   * unsaved analysis view — or out of a group already followed — must not
   * let that view go, so going back puts it back as it was held.
   */
  from: HeldView | null;
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
   */
  drill(conditions: readonly FilterNode[], title: string): void;
  /**
   * Opens `config` as a view of its own, held with the open view as its
   * origin: the follow-up that narrows an analysis to one group (D20 追问
   * 「只看这一组」) is a new question beside the one it came from, not an
   * edit that writes over it. The origin stays open with its result, so
   * `back()` shows that result again without running it; the view opened is
   * unsaved, named `title`, under the origin's injected scope, and saving it
   * is a first save like any new view's. `conditions` are what the follow-up
   * added, kept on the origin for whoever reads it. The origin keeps its
   * draft, so the leave guard is not asked.
   */
  follow(
    config: ViewConfig,
    title: string,
    conditions: readonly FilterNode[],
  ): void;
  /**
   * Returns to the held view's origin, through the leave guard: an untouched
   * view opened out of another goes without a question, one the user shaped
   * is asked about. Does nothing while nothing held has an origin.
   */
  back(): void;
  opened: OpenViewState;
  /** Null while the view is unopenable or still loading. */
  runtime: AnyViewRuntime | null;
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
  const { instanceId, onInstanceChange, guardUnload, newView, onDrilldown } =
    options;
  // Held by what they say: a host writes the array inline, so the object is
  // new every render while the kinds in it are not.
  const kindsKey = options.kinds.join(' ');
  const kinds = useMemo(() => kindsKey.split(' ') as ViewKind[], [kindsKey]);
  // Only the views this page can open: the sidebar offers no view the body
  // cannot render, and the effective default is resolved among those alone.
  const list = useViewList(engine, definitionId, { kinds });
  const [chosen, setChosen] = useState<string | null>(instanceId ?? null);
  const openId = chosen ?? list.defaultInstanceId;

  // A view the workbench holds is opened by this hook and not by an id, so
  // it is kept here beside the pin. While one made from nothing is open,
  // nothing is opened by id — the pin stays what it was and comes back into
  // force once the view is saved or let go. A drilled view is different: its
  // origin is the view opened by id, and it stays open underneath with the
  // result it was drilled on, which is what going back shows.
  //
  // The ref is what the release reads: closing a runtime belongs to the
  // moment it is replaced, not to a render.
  const [held, setHeld] = useState<HeldView | null>(null);
  const heldRef = useRef<HeldView | null>(null);
  const hold = useCallback(
    (next: HeldView | null) => {
      const previous = heldRef.current;
      heldRef.current = next;
      setHeld(next);
      // The one runtime that must not go with the view it drew is the origin
      // a drill is keeping: it comes back as it was held.
      if (previous && previous !== next?.from) engine.close(previous.runtime);
    },
    [engine],
  );
  // On the way out only. A cleanup keyed on the view itself would close it
  // under StrictMode's rehearsal of the unmount, with nothing to open it
  // again — the view was made by a press, not by an effect.
  useEffect(
    () => () => {
      // Every held view in the chain: a drilled view over the unsaved view
      // it came from is two runtimes to let go.
      for (let view = heldRef.current; view; view = view.from)
        engine.close(view.runtime);
      heldRef.current = null;
    },
    [engine],
  );

  // A view made from nothing releases the one opened by id; a drilled view
  // keeps it, because it is the origin.
  const byId = useOpenView(engine, held && !held.origin ? null : openId);
  const opened: OpenViewState = held
    ? { runtime: held.runtime, loading: false, error: null, scopeIssues: [] }
    : byId;
  const wrongKind = held ? null : kindMismatch(byId.runtime, kinds);
  const runtime = held ? held.runtime : wrongKind ? null : byId.runtime;
  const state: ViewRuntimeState<ViewConfig> | null = useViewRuntime(runtime);
  const autoRun = list.preferences?.autoRun ?? true;
  // The preference reaches every runtime alike; which members run on their
  // own is the model's to say per kind (`autoRunMembers`), and a kind that
  // declares none never does. Read whenever it or the open view changes.
  useEffect(() => {
    runtime?.setAutoApply(autoRun);
  }, [runtime, autoRun]);
  const filter = useFilterEditor(runtime);
  const refresh = useAutoRefresh(runtime);
  const commands = useSaveCommands(engine, runtime);
  const manager = useViewManager(engine, definitionId, list);
  const leave = useLeaveGuard(
    state
      ? {
          // A held view counts as dirty from the start — losing it loses
          // everything — but one nobody has touched yet holds nothing worth
          // a question: the draft is the config it opened with.
          dirty: held ? state.draft !== held.draft : state.dirty,
          write: state.write,
        }
      : null,
    // Leaving settles the outcome first, because the runtime it belongs to
    // is about to go.
    { onLeave: () => commands.abandon(), guardUnload },
  );
  useReleaseDeleted(openId, chosen, byId, setChosen);

  // Read off the guard rather than the guard itself: `request` is the one
  // stable part of it, and the object is new on every render.
  const request = leave.request;
  const choose = useCallback(
    // Opening another view releases this one's runtime and the draft goes
    // with it, so the switch is asked about before it happens.
    (id: string | null) =>
      request(() => {
        hold(null);
        setChosen(id);
      }),
    [request, hold],
  );
  // Which view is open is the one piece of workbench state a host may also
  // hold — a route, a link somebody shares — so the two are kept in
  // agreement here, once, for every workbench and every hand-built one.
  useInstanceSync({
    instanceId,
    chosen,
    choose,
    asking: leave.asking,
    onInstanceChange,
  });

  // Decided once per render from what is true now, so the controls that
  // offer a new view and the command they call cannot disagree.
  const definition = engine.definitions.get(definitionId);
  const permissions = engine.permissions(definitionId);
  const blanks = kinds.map(
    kind =>
      [
        kind,
        blankView(definition, kind, permissions, engine.limits, newView),
      ] as const,
  );
  const creatable = blanks
    .filter(([, blank]) => blank !== null)
    .map(([kind]) => kind);
  const title = newView?.title;
  const create = useCallback(
    (kind: ViewKind) => {
      const blank = blanks.find(([made]) => made === kind)?.[1];
      if (!blank || title === undefined) return;
      request(() => {
        // `create` types its answer by the config's kind, which a union of
        // configs cannot name; what it builds is the runtime `open` would
        // hand back for the same view.
        const made = engine.create(definitionId, {
          title,
          scope: blank.scope,
          config: blank.config,
        }) as unknown as AnyViewRuntime;
        hold({
          runtime: made,
          draft: made.getSnapshot().draft,
          origin: null,
          from: null,
        });
      });
    },
    [blanks, title, request, engine, definitionId, hold],
  );

  // Opens `config` beside the view on screen, which becomes its origin: kept
  // open with its result, so going back shows that result without a run.
  // Not a permission: a view opened this way is looked at, not written (H1);
  // its first save asks, as a new view's does. It inherits the origin's
  // scope as it is — the page's narrowing is not the view's to widen (H4).
  //
  // Not through the leave guard either: nothing is left. The origin keeps
  // its draft and its pending write, held or opened by id, and `back()`
  // returns to both — asking "leave without saving?" here would name a loss
  // that does not happen.
  const holdFrom = useCallback(
    (
      config: ViewConfig,
      viewTitle: string,
      conditions: readonly FilterNode[],
    ) => {
      if (!runtime || !state) return;
      const made = engine.create(definitionId, {
        title: viewTitle,
        scope: 'personal',
        config,
        scopeFilter: runtime.scopeFilter,
      }) as unknown as AnyViewRuntime;
      hold({
        runtime: made,
        draft: made.getSnapshot().draft,
        origin: { runtime, title: state.title, conditions },
        from: heldRef.current,
      });
    },
    [runtime, state, engine, definitionId, hold],
  );

  // Drilling needs a record view to open and a place to draw it.
  const canDrill =
    runtime?.kind === 'analysis' &&
    kinds.includes('record') &&
    definition?.kind === 'data' &&
    definition.record !== undefined;
  const drill = useCallback(
    (conditions: readonly FilterNode[], viewTitle: string) => {
      if (
        !canDrill ||
        !runtime ||
        !state ||
        !definition ||
        definition.kind !== 'data'
      )
        return;
      // Under what ran, not what is being typed: the row came from the
      // applied conditions.
      const config: RecordViewConfig = {
        ...defaultRecordConfig(definition, engine.limits),
        filter: drillFilter(state.applied.filter, conditions),
      };
      if (onDrilldown) {
        onDrilldown({
          definitionId,
          origin: { runtime, title: state.title, conditions },
          title: viewTitle,
          config,
          scopeFilter: runtime.scopeFilter,
        });
        return;
      }
      holdFrom(config, viewTitle, conditions);
    },
    [
      canDrill,
      runtime,
      state,
      definition,
      engine,
      definitionId,
      onDrilldown,
      holdFrom,
    ],
  );
  const back = useCallback(() => {
    const current = heldRef.current;
    if (!current?.origin) return;
    // The origin was either held itself — put back as it was — or opened by
    // id, which never closed: letting the drilled view go shows it again,
    // with the result it kept.
    request(() => hold(current.from));
  }, [request, hold]);

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
    creatable,
    create,
    held,
    canDrill,
    drill,
    follow: holdFrom,
    back,
    opened,
    runtime,
    state,
    unopenable: opened.error ?? wrongKind,
    definitionIssues: engine.definitionIssues(definitionId),
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
