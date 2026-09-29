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

import { useId } from 'react';
import type { gridBlocks } from './gridBlocks.js';

/**
 * The cells of a board being built (D34): one row of blocks as an SVG
 * pattern, repeated down the whole layer from the grid's padding — one
 * drawing, not one element a cell; nothing to walk past, nothing to press.
 *
 * The drawing is in the page rather than an image the stylesheet loads (a
 * `data:` URL the layer was masked with until D74): a strict policy's
 * `img-src` refuses a `data:` image, and the blocks went missing on exactly
 * the pages that hold one. In the page the blocks also take their colour
 * from the stylesheet directly (`styles.css`, `dashboard-grid-blocks`), a
 * token that follows the mode and the preset.
 */
export function GridBlocksLayer({
  row,
}: {
  row: NonNullable<ReturnType<typeof gridBlocks>>;
}) {
  // An id a `url(#…)` reads as it is: React's own may carry characters a
  // CSS identifier would have to escape.
  const pattern = `fve-grid-blocks${useId().replace(/[^\w-]/g, '')}`;
  return (
    <svg data-slot="dashboard-grid-blocks" aria-hidden="true" focusable="false">
      <defs>
        <pattern
          id={pattern}
          patternUnits="userSpaceOnUse"
          x={0}
          y={row.top}
          width={row.width}
          height={row.pitch}
        >
          {row.blocks.map(block => (
            <rect
              key={`${block.x},${block.y}`}
              x={block.x}
              y={block.y}
              width={block.width}
              height={block.height}
              rx={row.radius}
            />
          ))}
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill={`url(#${pattern})`} />
    </svg>
  );
}
