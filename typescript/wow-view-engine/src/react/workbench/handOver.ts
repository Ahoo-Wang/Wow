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
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import type {
  AnalysisViewConfig,
  FilterNode,
  FilterTree,
  RecordViewConfig,
  ViewConfig,
  ViewKind,
} from '../../model/index.js';
import {
  isSimpleTree,
  nodeAt,
  type FilterPath,
  removeAt,
  sameFilterTree,
} from '../../filter/index.js';
import {
  handedConditions,
  listenerSet,
  withHandedFilter,
  type AnyViewRuntime,
  type ViewRuntime,
  type SavedViewTarget,
  type ViewEngine,
  type ViewHandOver,
} from '../../runtime/index.js';
import type { FilterEditorController } from '../useFilterEditor.js';
import type { HeldView } from '../useWorkbench.js';

/**
 * Opens each new `handOver` once, through the leave guard (D22 H, D26 Q30):
 * a view a dashboard or an embed sent through the host's route.
 *
 * - One nobody saved (`unsaved`) — a follow-up on a group, a board's own
 *   analysis — opens as a view made from nothing does: unsaved, under its
 *   title until its first save, which is a create; held as `handed`, so the
 *   shell opens it with its editor folded. One of a kind the workbench does
 *   not draw is left alone.
 * - A saved one (`view`) is opened by its id (`open`), which is what makes
 *   it that view — listed, saved in place, routed by its id.
 *
 * Either runs under the handed `scopeFilter` as its scope. The same object
 * again opens nothing — a host re-rendering with the route it already
 * followed must not open a second copy — so a host opens the same view
 * twice by handing a new object.
 *
 * Opening is an effect, so StrictMode's rehearsal of the unmount closes the
 * view the first run opened (the workbench lets its held views go on the way
 * out); what was seen is forgotten with it, and the second run opens it
 * again.
 */
export function useHandOver(
  handOver: ViewHandOver | null | undefined,
  context: {
    engine: ViewEngine;
    definitionId: string;
    kinds: readonly ViewKind[];
    request(proceed: () => void): void;
    hold(view: HeldView): void;
    open(target: SavedViewTarget): void;
  },
): void {
  const { engine, definitionId, kinds, request, hold, open } = context;
  const seen = useRef<ViewHandOver | null>(null);
  useEffect(
    () => () => {
      seen.current = null;
    },
    [],
  );
  useEffect(() => {
    if (!handOver || seen.current === handOver) return;
    seen.current = handOver;
    if (handOver.kind === 'view') {
      request(() => open(handOver));
      return;
    }
    if (!kinds.includes(handOver.config.kind)) return;
    request(() => {
      // `create` types its answer by the config's kind, which a union of
      // configs cannot name; what it builds is the runtime `open` would
      // hand back for the same view.
      const made = engine.create(definitionId, {
        title: handOver.title,
        scope: 'personal',
        config: handOver.config,
        scopeFilter: handOver.scopeFilter,
      }) as unknown as AnyViewRuntime;
      hold({
        runtime: made,
        draft: made.getSnapshot().draft,
        origin: null,
        from: null,
        handed: true,
        ...(handOver.named ? { named: handOver.named } : {}),
        ...(handOver.from ? { board: handOver.from } : {}),
      });
    });
  }, [handOver, kinds, request, engine, definitionId, hold, open]);
}

/**
 * A saved view handed over (`SavedViewTarget`), once it is open by its id:
 * what the reader set on the board goes onto its own conditions and runs —
 * so it opens 「已修改」, the conditions on 「正在显示」 each removable,
 * and 「还原」 takes them back off. They go onto what the view was saved
 * with, not onto whatever draft it had: the leave guard already asked
 * before that draft went.
 *
 * Answers the draft it was left with, once, until the reader touches it:
 * leaving an untouched hand-over asks nothing, as leaving an untouched view
 * made from nothing asks nothing. Once per hand-over and runtime: a runtime
 * opened again for the same hand-over (StrictMode's rehearsal closes the
 * first) takes the conditions again, since it opened on the saved view.
 *
 * Landing edits the runtime, which is an effect; what it left is read on
 * render, so the effect writes it into a store `useSyncExternalStore`
 * reads (`landingStore`) rather than calling `setState` from its body.
 */
export function useHandedConditions(
  handed: SavedViewTarget | null,
  runtime: AnyViewRuntime | null,
): ViewConfig | null {
  const landing = useState(landingStore)[0];
  const landed = useSyncExternalStore(
    landing.subscribe,
    landing.get,
    landing.get,
  );
  useEffect(() => {
    if (!handed || !runtime) return;
    const last = landing.get();
    if (last?.target === handed && last.runtime === runtime) return;
    const snapshot = runtime.getSnapshot();
    const own = snapshot.saved?.config ?? snapshot.draft;
    if (own.kind !== 'dashboard' && handed.filter) {
      const merged = withHandedFilter(own, handed.filter);
      const data = runtime as ViewRuntime<
        RecordViewConfig | AnalysisViewConfig
      >;
      data.edit({ filter: merged.filter, filterMode: merged.filterMode });
      data.apply();
    }
    landing.set({
      target: handed,
      runtime,
      draft: runtime.getSnapshot().draft,
    });
  }, [handed, runtime, landing]);
  return landed && landed.target === handed && landed.runtime === runtime
    ? landed.draft
    : null;
}

/** What a hand-over left a runtime with: the draft, once it landed. */
interface Landed {
  target: SavedViewTarget;
  runtime: AnyViewRuntime;
  draft: ViewConfig;
}

/** The last landing, as a store the effect writes and React reads. */
function landingStore() {
  let landed: Landed | null = null;
  const listeners = listenerSet();
  return {
    get: (): Landed | null => landed,
    set(next: Landed): void {
      landed = next;
      listeners.emit();
    },
    subscribe: listeners.subscribe,
  };
}

/**
 * The filter editor of a saved view handed over (`SavedViewTarget`), whose
 * ✕ on 「正在显示」 takes a condition the board handed it **off** rather
 * than blanking it (D26 Q30). ✕ elsewhere clears a condition's value and
 * leaves its field in the editor for the next question — the reader put the
 * field there. A handed condition is no row the reader built: blanked, it
 * stayed behind as a field-without-a-value the saved view never had, so
 * taking every handed condition off still read 「已修改」 with 保存 offered
 * (walk of #1882). So one is removed outright, and once the tree is the
 * saved one again — the "all of" `drillFilter` wrapped an advanced tree in
 * included — the saved filter and its mode come back as they were, and the
 * view is clean.
 *
 * A handed condition is a child of the root "all of" (`withHandedFilter`
 * puts them there) equal to one handed; one the saved view holds as well is
 * the view's own, and cleared as any other. The same controller otherwise.
 */
export function useHandedRemoval(
  filter: FilterEditorController,
  handed: SavedViewTarget | null,
  runtime: AnyViewRuntime | null,
): FilterEditorController {
  return useMemo(() => {
    const conditions = handedConditions(handed?.filter ?? null);
    if (!runtime || conditions.length === 0) return filter;
    return {
      ...filter,
      clearValue(path: FilterPath) {
        const { draft, saved } = runtime.getSnapshot();
        const own = saved?.config;
        const node = path.length === 1 ? nodeAt(draft.filter, path) : null;
        if (
          draft.kind === 'dashboard' ||
          own?.kind !== draft.kind ||
          !node ||
          !conditions.some(condition => sameFilterTree(node, condition)) ||
          heldBy(own.filter, node)
        ) {
          filter.clearValue(path);
          return;
        }
        const rest = removeAt(draft.filter, path);
        const data = runtime as ViewRuntime<
          RecordViewConfig | AnalysisViewConfig
        >;
        data.edit(
          backToSaved(rest, own.filter)
            ? { filter: own.filter, filterMode: own.filterMode }
            : { filter: rest },
        );
      },
    };
  }, [filter, handed, runtime]);
}

/** Whether a saved tree holds `node` among its root's own conditions. */
function heldBy(saved: FilterTree, node: FilterNode): boolean {
  return (
    isSimpleTree(saved) &&
    saved.children.some(child => sameFilterTree(child, node))
  );
}

/**
 * Whether what is left is the saved tree: the same, or the saved tree alone
 * in the "all of" `drillFilter` wrapped it in to add to it.
 */
function backToSaved(rest: FilterTree, saved: FilterTree): boolean {
  if (sameFilterTree(rest, saved)) return true;
  const [only] = rest.children;
  return (
    rest.op === 'and' &&
    rest.children.length === 1 &&
    only !== undefined &&
    sameFilterTree(only, saved)
  );
}
