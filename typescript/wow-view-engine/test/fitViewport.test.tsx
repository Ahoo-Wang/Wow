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

import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RecordTable } from '../src/ui/index.js';
import { MIN_FIT_PX } from '../src/ui/record/fitViewport.js';
import { recordTableController } from './fixtures/ui.js';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** jsdom lays nothing out, so the geometry is told to it. */
function laidOut(portTop: number, portBottom: number, frameBottom: number) {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
    function (this: HTMLElement) {
      const isFrame = this.dataset.slot === 'result-block';
      const top = isFrame ? 0 : portTop;
      const bottom = isFrame ? frameBottom : portBottom;
      return {
        top,
        bottom,
        left: 0,
        right: 800,
        width: 800,
        height: bottom - top,
        x: 0,
        y: top,
        toJSON: () => ({}),
      } as DOMRect;
    },
  );
}

function port(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-slot="record-table"]')!;
}

/**
 * P-22: the port's cap is the room left in the viewport, so the frame — the
 * summaries and the pagination row under the rows — ends at the window's
 * bottom edge whenever there are more rows than room.
 */
describe('the scroll port ends where the viewport does', () => {
  it('caps the port at the viewport less its top and what the frame draws under it', () => {
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(900);
    // The port starts 300px down; the frame runs 60px past it (pagination).
    laidOut(300, 800, 860);
    render(
      <div data-slot="result-block">
        <RecordTable table={recordTableController()} />
      </div>,
    );

    expect(port().style.getPropertyValue('--fve-record-table-fit')).toBe(
      `${900 - 300 - 60}px`,
    );
    // A **surviving class assertion**. Which of three heights wins is a
    // cascade written inside one `max-h-[…]` value — the host's cap, then
    // the measured fit above, then the static `70vh` — and an order of
    // fallbacks in a CSS value is not a state any element could carry. The
    // measured number itself is read off the element above.
    expect(port().className).toContain(
      'max-h-[var(--fve-record-table-max-h,var(--fve-record-table-fit,70vh))]',
    );
  });

  it('never squeezes the port below its floor', () => {
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(400);
    laidOut(380, 700, 760);
    render(
      <div data-slot="result-block">
        <RecordTable table={recordTableController()} />
      </div>,
    );

    expect(port().style.getPropertyValue('--fve-record-table-fit')).toBe(
      `${MIN_FIT_PX}px`,
    );
  });

  it('measures nothing for a table that holds against a scrolling surface', () => {
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(900);
    laidOut(300, 800, 860);
    render(<RecordTable table={recordTableController()} scrolls={false} />);

    expect(port().style.getPropertyValue('--fve-record-table-fit')).toBe('');
  });
});
