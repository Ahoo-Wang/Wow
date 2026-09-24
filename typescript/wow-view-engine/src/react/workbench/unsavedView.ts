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

import { useEffect, useRef } from 'react';
import type { ViewConfig, ViewKind } from '../../model/index.js';
import type { AnyViewRuntime, ViewEngine } from '../../runtime/index.js';
import type { HeldView } from '../useWorkbench.js';

/**
 * A view nobody saved, handed to a workbench to open (D22 H): a follow-up a
 * dashboard panel sent through the host's route — the records behind a
 * group, the question split or of the group alone — or an analysis a board
 * owns. It opens as a view made from nothing does: unsaved, under `title`
 * until its first save, which is a create.
 */
export interface UnsavedView {
  title: string;
  config: ViewConfig;
}

/**
 * Opens each new `unsaved` once, through the leave guard, as the
 * workbench's held view; one of a kind the workbench does not draw is left
 * alone. The same object again opens nothing — a host re-rendering with the
 * route it already followed must not open a second copy — so a host opens
 * the same view twice by handing a new object.
 *
 * Opening is an effect, so StrictMode's rehearsal of the unmount closes the
 * view the first run opened (the workbench lets its held views go on the way
 * out); what was seen is forgotten with it, and the second run opens it
 * again.
 */
export function useUnsavedView(
  unsaved: UnsavedView | null | undefined,
  context: {
    engine: ViewEngine;
    definitionId: string;
    kinds: readonly ViewKind[];
    request(proceed: () => void): void;
    hold(view: HeldView): void;
  },
): void {
  const { engine, definitionId, kinds, request, hold } = context;
  const seen = useRef<UnsavedView | null>(null);
  useEffect(
    () => () => {
      seen.current = null;
    },
    [],
  );
  useEffect(() => {
    if (!unsaved || seen.current === unsaved) return;
    seen.current = unsaved;
    if (!kinds.includes(unsaved.config.kind)) return;
    request(() => {
      // `create` types its answer by the config's kind, which a union of
      // configs cannot name; what it builds is the runtime `open` would
      // hand back for the same view.
      const made = engine.create(definitionId, {
        title: unsaved.title,
        scope: 'personal',
        config: unsaved.config,
      }) as unknown as AnyViewRuntime;
      hold({
        runtime: made,
        draft: made.getSnapshot().draft,
        origin: null,
        from: null,
        handed: true,
      });
    });
  }, [unsaved, kinds, request, engine, definitionId, hold]);
}
