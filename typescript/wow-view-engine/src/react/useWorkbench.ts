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

import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  Issue,
  ViewConfig,
  ViewInstance,
  ViewKind,
} from '../model/index.js';
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
   * The one kind this workbench draws. It narrows the list — and with it the
   * effective default — and it is what an instance of another kind is
   * measured against.
   */
  kind: ViewKind;
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
   * catalogue's word; a host's own template goes in `config`.
   */
  newView?: NewViewOptions;
}

/**
 * A view made from nothing: the runtime, and the draft it opened with. The
 * draft is kept so the leave guard can tell an untouched new view — which
 * costs nothing to let go — from one the user has already shaped.
 */
interface FreshView {
  runtime: AnyViewRuntime;
  draft: ViewConfig;
}

/**
 * Everything a workbench needs that is not its own markup: which views there
 * are, which one is open, what it says, and what happens when the user moves
 * to another.
 *
 * The three default workbenches differ in their editor and their result and
 * in nothing else, so the shell around those two is assembled once here. A
 * host that writes its own markup calls this and loses none of the rules —
 * the wrong-kind report, the released pin, the leave guard and the header's
 * four outcomes are all in the controller rather than in the components.
 */
export interface WorkbenchController {
  /** The views of this definition and kind, in the user's order. */
  list: ViewListState;
  manager: ViewManagerController;
  /** What is open now: the explicit choice, or the effective default. */
  openId: string | null;
  /** Opens another view, through the leave guard. */
  choose(id: string | null): void;
  /**
   * Whether a new view is on offer here: the definition has this kind, the
   * user may create in some audience, and `newView` gave it a name. Every
   * "new view" control exists on this and on nothing else (D4).
   */
  canCreate: boolean;
  /**
   * Opens a view made from nothing, through the leave guard. It is unsaved
   * until its first save, which is a create: the workbench then opens what
   * the store took, as it does a copy. An unsaved view has no id, so a host
   * routing the workbench hears nothing until that save lands.
   */
  create(): void;
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
 * Nothing here is record-, analysis- or dashboard-shaped: the kind is a
 * parameter, and what differs between the three is the editor and the result
 * they render from `runtime`, not the way a view is found, opened, left or
 * saved.
 */
export function useWorkbench(
  engine: ViewEngine,
  definitionId: string,
  options: WorkbenchOptions,
): WorkbenchController {
  const { kind, instanceId, onInstanceChange, guardUnload, newView } = options;
  // Only the views this page can open: the sidebar offers no view the body
  // cannot render, and the effective default is resolved among those alone.
  const list = useViewList(engine, definitionId, { kind });
  const [chosen, setChosen] = useState<string | null>(instanceId ?? null);
  const openId = chosen ?? list.defaultInstanceId;

  // A view made from nothing is opened by this hook and not by an id, so it
  // is held here beside the pin. While one is open, nothing is opened by id
  // — the pin stays what it was and comes back into force once the new view
  // is saved or let go. The ref is what the release reads: closing a runtime
  // belongs to the moment it is replaced, not to a render.
  const [fresh, setFresh] = useState<FreshView | null>(null);
  const freshRef = useRef<FreshView | null>(null);
  const hold = useCallback(
    (next: FreshView | null) => {
      const previous = freshRef.current;
      freshRef.current = next;
      setFresh(next);
      if (previous) engine.close(previous.runtime);
    },
    [engine],
  );
  // On the way out only. A cleanup keyed on the view itself would close it
  // under StrictMode's rehearsal of the unmount, with nothing to open it
  // again — the view was made by a press, not by an effect.
  useEffect(() => () => hold(null), [hold]);

  const byId = useOpenView(engine, fresh ? null : openId);
  const opened: OpenViewState = fresh
    ? { runtime: fresh.runtime, loading: false, error: null, scopeIssues: [] }
    : byId;
  const wrongKind = fresh ? null : kindMismatch(byId.runtime, kind);
  const runtime = fresh ? fresh.runtime : wrongKind ? null : byId.runtime;
  const state: ViewRuntimeState<ViewConfig> | null = useViewRuntime(runtime);
  const filter = useFilterEditor(runtime);
  const refresh = useAutoRefresh(runtime);
  const commands = useSaveCommands(engine, runtime);
  const manager = useViewManager(engine, definitionId, list);
  const leave = useLeaveGuard(
    state
      ? {
          // A new view counts as dirty from the start — losing it loses
          // everything — but one nobody has touched yet holds nothing worth
          // a question: the draft is the config it opened with.
          dirty: fresh ? state.draft !== fresh.draft : state.dirty,
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
  const blank = blankView(
    engine.definitions.get(definitionId),
    kind,
    engine.permissions(definitionId),
    engine.limits,
    newView,
  );
  const title = newView?.title;
  const create = useCallback(() => {
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
      hold({ runtime: made, draft: made.getSnapshot().draft });
    });
  }, [blank, title, request, engine, definitionId, hold]);

  const reload = list.reload;
  const open = useCallback(
    (instance: ViewInstance) => {
      // What the store took is what the screen shows from here, opened by
      // its id like any other view; a new view that was just saved is let
      // go for the instance it became.
      hold(null);
      setChosen(instance.id);
      reload();
    },
    [reload, hold],
  );

  return {
    list,
    manager,
    openId,
    choose,
    canCreate: blank !== null,
    create,
    opened,
    runtime,
    state,
    unopenable: opened.error ?? wrongKind,
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
