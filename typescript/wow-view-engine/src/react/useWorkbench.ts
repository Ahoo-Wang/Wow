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

import { useCallback, useState } from 'react';
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
import { useLeaveGuard, type LeaveGuard } from './workbench/leaveGuard.js';
import { useReleaseDeleted } from './workbench/releaseDeleted.js';

export interface WorkbenchOptions {
  /**
   * The one kind this workbench draws. It narrows the list — and with it the
   * effective default — and it is what an instance of another kind is
   * measured against.
   */
  kind: ViewKind;
  /** Opens this view first; the user's effective default when left out. */
  instanceId?: string | null;
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
   * A delete moves on. The engine let the runtime go with the instance, so
   * the list is reloaded, the default moves, and the open id follows it — or
   * empties with the list.
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
  const { kind, instanceId = null } = options;
  // Only the views this page can open: the sidebar offers no view the body
  // cannot render, and the effective default is resolved among those alone.
  const list = useViewList(engine, definitionId, { kind });
  const [chosen, setChosen] = useState<string | null>(instanceId);
  const openId = chosen ?? list.defaultInstanceId;

  const opened = useOpenView(engine, openId);
  const wrongKind = kindMismatch(opened.runtime, kind);
  const runtime = wrongKind ? null : opened.runtime;
  const state: ViewRuntimeState<ViewConfig> | null = useViewRuntime(runtime);
  const filter = useFilterEditor(runtime);
  const refresh = useAutoRefresh(runtime);
  const commands = useSaveCommands(engine, runtime);
  const manager = useViewManager(engine, definitionId, list);
  const leave = useLeaveGuard(
    state ? { dirty: state.dirty, write: state.write } : null,
    // Leaving settles the outcome first, because the runtime it belongs to
    // is about to go.
    { onLeave: () => commands.abandon() },
  );
  useReleaseDeleted(openId, chosen, opened, setChosen);

  // Read off the guard rather than the guard itself: `request` is the one
  // stable part of it, and the object is new on every render.
  const request = leave.request;
  const choose = useCallback(
    // Opening another view releases this one's runtime and the draft goes
    // with it, so the switch is asked about before it happens.
    (id: string | null) => request(() => setChosen(id)),
    [request],
  );
  const reload = list.reload;
  const open = useCallback(
    (instance: ViewInstance) => {
      setChosen(instance.id);
      reload();
    },
    [reload],
  );

  return {
    list,
    manager,
    openId,
    choose,
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
    onDeleted: useCallback(() => {
      setChosen(null);
      reload();
    }, [reload]),
    onRecovered: useCallback(() => reload(), [reload]),
  };
}
