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
 * The views a workbench holds rather than opens by id (`HeldView`): one made
 * from nothing (`create`), one followed out of a group or drilled out of a
 * row of the view on screen (`follow`, `drill`), and one a host handed over.
 * `useHold` keeps the one held and lets each go at the moment it is
 * replaced; `useHeldCommands` is the ways into and out of one — and the way
 * back to the view or the board it came from.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  FilterNode,
  FilterTree,
  RecordViewConfig,
  ViewConfig,
  ViewKind,
} from '../../model/index.js';
import { drillFilter, narrowsTo } from '../../analysis/index.js';
import { defaultRecordConfig } from '../../record/index.js';
import type {
  AnyViewRuntime,
  BoardOrigin,
  GroupNaming,
  SavedViewTarget,
  ViewEngine,
  ViewRuntimeState,
} from '../../runtime/index.js';
import { withFilterMode } from '../../runtime/navigation.js';
import type { WorkbenchOptions } from '../useWorkbench.js';
import { blankView } from './newView.js';

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
   * What its title says of the group it was opened from, when it names one
   * (「订单 · 仓库 是 华南」): while those conditions still narrow it, the
   * title stands; once the reader takes one off, the workbench calls it by
   * `subject` (`WorkbenchController.state`).
   */
  named?: GroupNaming;
  /**
   * The held view the origin itself was, when it was one: drilling out of an
   * unsaved analysis view — or out of a group already followed — must not
   * let that view go, so going back puts it back as it was held.
   */
  from: HeldView | null;
  /**
   * Handed over by a host (`WorkbenchOptions.unsaved`) — a dashboard's
   * follow-up on a group, a board's own analysis — rather than made here:
   * like a view opened from another's group, its conditions are what it was
   * opened with, so the shell opens it with its editor folded.
   */
  handed?: true;
  /** The board it was handed from, for the way back (`WorkbenchController.board`). */
  board?: BoardOrigin;
}

/** The view held, and the one call that replaces it. */
export function useHold(engine: ViewEngine): {
  held: HeldView | null;
  heldRef: { readonly current: HeldView | null };
  hold(next: HeldView | null): void;
} {
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
  return { held, heldRef, hold };
}

/** What `useHeldCommands` reads off the workbench it serves. */
export interface HeldCommandsInput extends Pick<
  WorkbenchOptions,
  'newView' | 'onDrilldown' | 'onNavigate'
> {
  engine: ViewEngine;
  definitionId: string;
  kinds: readonly ViewKind[];
  definition: ReturnType<ViewEngine['definitions']['get']>;
  runtime: AnyViewRuntime | null;
  state: ViewRuntimeState<ViewConfig> | null;
  held: HeldView | null;
  heldRef: { readonly current: HeldView | null };
  hold(next: HeldView | null): void;
  /** The leave guard's: runs `action` once whatever would be lost is settled. */
  request(action: () => void): void;
  /** A saved view a board handed over, while it is the one opened by id. */
  handedHere: SavedViewTarget | null;
}

/**
 * The ways into and out of a held view, as the controller offers them
 * (`WorkbenchController.create`, `drill`, `follow`, `back`, `toBoard`).
 */
export function useHeldCommands({
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
}: HeldCommandsInput) {
  // Decided once per render from what is true now, so the controls that
  // offer a new view and the command they call cannot disagree.
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
        const made = engine.create(definitionId, {
          title,
          scope: blank.scope,
          config: blank.config,
        });
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
      subject?: string,
    ) => {
      if (!runtime || !state) return;
      const made = engine.create(definitionId, {
        title: viewTitle,
        scope: 'personal',
        config,
        scopeFilter: runtime.scopeFilter,
      });
      hold({
        runtime: made,
        draft: made.getSnapshot().draft,
        origin: { runtime, title: state.title, conditions },
        from: heldRef.current,
        ...(subject === undefined ? {} : { named: { subject, conditions } }),
      });
    },
    [runtime, state, engine, definitionId, hold, heldRef],
  );

  // Drilling needs a record view to open and a place to draw it.
  const canDrill =
    runtime?.kind === 'analysis' &&
    kinds.includes('record') &&
    definition?.kind === 'data' &&
    definition.record !== undefined;
  const drill = useCallback(
    (
      conditions: readonly FilterNode[],
      viewTitle: string,
      subject?: string,
    ) => {
      if (
        !canDrill ||
        !runtime ||
        !state ||
        state.applied.kind === 'dashboard' ||
        !definition ||
        definition.kind !== 'data'
      )
        return;
      // Under what ran, not what is being typed: the row came from the
      // applied conditions.
      // Advanced where the row's range could not flatten beside the
      // analysis's own on the same field (`drillFilter`).
      const config: RecordViewConfig = withFilterMode({
        ...defaultRecordConfig(definition, engine.limits),
        filter: drillFilter(state.applied.filter, conditions),
      });
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
      holdFrom(config, viewTitle, conditions, subject);
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
  }, [request, hold, heldRef]);
  // The board a view was handed from, while that view is on screen: a view
  // opened out of it here shows its own way back instead.
  const board = !onNavigate
    ? null
    : held
      ? held.origin
        ? null
        : (held.board ?? null)
      : (handedHere?.from ?? null);
  const toBoard = useCallback(() => {
    if (board) request(() => onNavigate?.(board.back));
  }, [board, request, onNavigate]);

  return {
    creatable,
    create,
    canDrill,
    drill,
    follow: holdFrom,
    back,
    board,
    toBoard,
  };
}

/**
 * The state under the name the view goes by now. A held view named by a
 * group (`HeldView.named`) keeps that name while the group's conditions are
 * all among what it applied, and is called by its subject once one is not:
 * taken off the applied bar, edited to another value, negated. Read off the
 * applied config, not the draft — the title names what the screen shows,
 * and a condition still being typed has not changed that.
 */
export function namedState(
  state: ViewRuntimeState<ViewConfig> | null,
  held: HeldView | null,
): ViewRuntimeState<ViewConfig> | null {
  const named = held?.named;
  if (
    !state ||
    !named ||
    state.applied.kind === 'dashboard' ||
    narrowsTo(state.applied.filter, named.conditions)
  )
    return state;
  return { ...state, title: named.subject };
}
