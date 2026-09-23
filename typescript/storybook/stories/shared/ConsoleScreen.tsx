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
import type { ReactNode } from 'react';

/**
 * A console as an operator has it: the whole screen, nothing around it.
 *
 * For a story that is the product rather than a documented scenario — it
 * goes with `parameters.layout: 'fullscreen'`, and what the scene is and how
 * to use it lives on the docs page instead of above the console. The one
 * child is stretched to at least the viewport's height, so a workbench's
 * list and column reach the bottom of the screen; its table measures the
 * room left to the bottom of the viewport on its own and scrolls inside it.
 * `min-height` rather than `height`: a tall editor band pushes the page
 * longer instead of being cut off. The one column is `minmax(0, 1fr)`, not
 * `auto`: a grid item is at least as wide as its content, and a table of
 * many columns would widen the console past the screen instead of scrolling
 * sideways inside its own port.
 */
export function ConsoleScreen({ children }: { children: ReactNode }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1fr)',
        minHeight: '100dvh',
      }}
    >
      {children}
    </div>
  );
}
