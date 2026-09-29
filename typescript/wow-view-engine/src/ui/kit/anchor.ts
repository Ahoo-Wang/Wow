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
 * Where a menu is placed: the mark or row pressed, or the point it was
 * pressed at. The charts hand one over with every press and the follow-up
 * menu hangs from it, so it lives beside neither (2026-09-27 quality
 * review: the charts reached into `analysis/DrillMenu.tsx` for it while
 * the analysis folder drew the charts, a cycle between the two folders).
 */
export type PickAnchor = Element | { getBoundingClientRect(): DOMRect };

/** The point a pointer event happened at, as something a menu can anchor to. */
export function pointAnchor(event: {
  clientX: number;
  clientY: number;
}): PickAnchor {
  const { clientX, clientY } = event;
  return {
    getBoundingClientRect: () =>
      DOMRect.fromRect({ x: clientX, y: clientY, width: 0, height: 0 }),
  };
}
