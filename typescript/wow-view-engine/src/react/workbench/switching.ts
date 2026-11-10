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

import { useCallback } from 'react';
import type { SavedViewTarget } from '../../runtime/index.js';
import type { HeldView } from '../useWorkbench.js';

/** What the workbench holds about which view is open, and the guard it asks. */
export interface SwitchingOptions {
  /** The pin: the view opened by id, `null` for the effective default. */
  chosen: string | null;
  setChosen(id: string | null): void;
  /** A view held here, made from nothing or drilled. */
  held: HeldView | null;
  hold(view: HeldView | null): void;
  /** A saved view a board handed over (D26 Q30). */
  handed: SavedViewTarget | null;
  setHanded(target: SavedViewTarget | null): void;
  /** The leave guard's `request`: runs the switch now, or once it is answered. */
  request(next: () => void): void;
  /** The leave guard's `cancel`: takes its question away. */
  withdraw(): void;
}

/**
 * The two ways the workbench changes which view is open: `choose`, a switch
 * to a view by id, asked about through the leave guard; and `openHanded`, a
 * saved view a board handed over, which the hand-over has already asked
 * about. Both let go of a held view and of a hand-over the switch replaces.
 */
export function useSwitching({
  chosen,
  setChosen,
  held,
  hold,
  handed,
  setHanded,
  request,
  withdraw,
}: SwitchingOptions): {
  choose(id: string | null): void;
  openHanded(target: SavedViewTarget): void;
} {
  const choose = useCallback(
    // Opening another view releases this one's runtime and the draft goes
    // with it, so the switch is asked about before it happens. The saved
    // view already open as it is — nothing held over it, nothing handed
    // over to it — is no switch: a question still up about leaving it, a
    // route that went elsewhere and came back, is taken away (#5490).
    (id: string | null) =>
      id === chosen && held === null && handed === null
        ? withdraw()
        : request(() => {
            hold(null);
            setHanded(null);
            setChosen(id);
          }),
    [chosen, held, handed, request, withdraw, hold, setHanded, setChosen],
  );
  const openHanded = useCallback(
    (target: SavedViewTarget) => {
      hold(null);
      setHanded(target);
      setChosen(target.instanceId);
    },
    [hold, setHanded, setChosen],
  );
  return { choose, openHanded };
}
