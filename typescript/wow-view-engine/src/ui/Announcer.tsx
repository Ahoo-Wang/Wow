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
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from 'react';

export interface Announcer {
  /**
   * Say this, out loud, once. The same words again are said again: a
   * second press that did what the first did is still a press to hear.
   */
  say(message: string): void;
  /**
   * The live region itself. It is handed back rather than rendered by the
   * caller's own markup so that one call is one region: a surface cannot
   * end up with two of them, and cannot end up with none. `null` from
   * `useSurfaceAnnouncer` where the region is the surface's.
   */
  region: ReactNode;
}

/**
 * One live region for one surface, and the sentence to put in it.
 *
 * Four surfaces here move a row by a keyboard shortcut of their own — the
 * column settings, the sort editor, the view manager and the visualization
 * panel's series list — and the first three each had their own
 * `role="status" aria-live="polite"` block, written out three times. The
 * region is not the interesting part of any of them: the wording is
 * (`dragWording.ts`, `dragAnnounce.ts`, and each list's own `drag.ts`),
 * and the caller hands that over already formatted.
 *
 * Why there is a region at all, next to a drag library that owns one:
 * `@dnd-kit`'s `Accessibility` plugin announces what the plugin itself
 * drives — pick up, drop, cancel — and every one of those surfaces also
 * offers a one-press arrow move that never reaches its `KeyboardSensor`. That move is
 * what this says. **One per surface** is the rule the shared hook exists to
 * keep: two polite regions on one screen are two voices reading over each
 * other, and a reader is given no way to tell which answered the key.
 *
 * Each sentence is a node of its own inside the region, keyed by how many
 * have been said: a reader announces what is added to a live region, and
 * the same text set twice adds nothing.
 *
 * The `slot` is the region's `data-slot`, so a suite can still read back
 * what a particular surface said.
 */
export function useAnnouncer(slot: string): Announcer {
  const [said, setSaid] = useState({ message: '', count: 0 });
  const say = useCallback(
    (message: string) => setSaid(last => ({ message, count: last.count + 1 })),
    [],
  );
  return {
    say,
    region: (
      <div
        data-slot={slot}
        role="status"
        aria-live="polite"
        className="sr-only"
      >
        {said.message !== '' && <span key={said.count}>{said.message}</span>}
      </div>
    ),
  };
}

/** The voice of the surface a part is drawn inside. */
const SurfaceVoice = createContext<((message: string) => void) | null>(null);

/**
 * Hands a surface's one voice to every part drawn inside it: a board's
 * grid, its tabs, its filters and its building all say what they did
 * through the board's region, not each through a region of its own.
 */
export function SurfaceAnnouncer({
  say,
  children,
}: {
  say(message: string): void;
  children: ReactNode;
}) {
  return <SurfaceVoice.Provider value={say}>{children}</SurfaceVoice.Provider>;
}

/**
 * The region of the surface this part is drawn inside, where one hands its
 * voice down (`SurfaceAnnouncer`) — `region` is then `null`, there being
 * nothing of the part's own to draw — or a region of its own, for a part a
 * host draws by itself.
 */
export function useSurfaceAnnouncer(slot: string): Announcer {
  const surface = useContext(SurfaceVoice);
  const own = useAnnouncer(slot);
  return surface ? { say: surface, region: null } : own;
}
