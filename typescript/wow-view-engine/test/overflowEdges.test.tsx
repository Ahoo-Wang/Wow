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
import { recordTableController } from './fixtures/ui.js';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** jsdom lays nothing out, so the port is told how wide its table is. */
function portOf(scrollWidth: number, clientWidth: number) {
  vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(
    scrollWidth,
  );
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(
    clientWidth,
  );
}

function port(): HTMLElement {
  return document.querySelector<HTMLElement>('[data-slot="record-table"]')!;
}

/**
 * P-23: the held columns' edges say "the middle scrolls under here", so the
 * port says whether there is a middle to scroll, and the edges answer to
 * that word rather than standing on a table that fits.
 */
describe('the port says whether its table overflows', () => {
  it('says so while the table is wider than the port', () => {
    portOf(1200, 800);
    render(<RecordTable table={recordTableController()} />);

    expect(port().hasAttribute('data-overflowing')).toBe(true);
  });

  it('says nothing while the table fits', () => {
    portOf(800, 800);
    render(<RecordTable table={recordTableController()} />);

    expect(port().hasAttribute('data-overflowing')).toBe(false);
  });
});

/**
 * R2-76: a held column's edge fades the content passing under it only while
 * there is content under it, so the port says which sides have columns
 * scrolled out of sight — `data-more-start`, `data-more-end` — and only
 * where it is the scroller itself.
 */
describe('the port says which sides hide columns', () => {
  function scrolledTo(scrollLeft: number) {
    vi.spyOn(HTMLElement.prototype, 'scrollLeft', 'get').mockReturnValue(
      scrollLeft,
    );
  }

  function sides() {
    return {
      start: port().hasAttribute('data-more-start'),
      end: port().hasAttribute('data-more-end'),
    };
  }

  it('says more lies past the end before it is scrolled', () => {
    portOf(1200, 800);
    scrolledTo(0);
    render(<RecordTable table={recordTableController()} />);

    expect(sides()).toEqual({ start: false, end: true });
  });

  it('says both sides hide columns halfway along', () => {
    portOf(1200, 800);
    scrolledTo(200);
    render(<RecordTable table={recordTableController()} />);

    expect(sides()).toEqual({ start: true, end: true });
  });

  it('says only the start hides columns once scrolled to the end', () => {
    portOf(1200, 800);
    scrolledTo(400);
    render(<RecordTable table={recordTableController()} />);

    expect(sides()).toEqual({ start: true, end: false });
  });

  it('says nothing while the table fits', () => {
    portOf(800, 800);
    scrolledTo(0);
    render(<RecordTable table={recordTableController()} />);

    expect(sides()).toEqual({ start: false, end: false });
  });

  it('says nothing where something around it scrolls', () => {
    portOf(1200, 800);
    scrolledTo(200);
    render(<RecordTable table={recordTableController()} scrolls={false} />);

    expect(port().hasAttribute('data-overflowing')).toBe(true);
    expect(sides()).toEqual({ start: false, end: false });
  });

  it('reads the sides again as the port scrolls', async () => {
    portOf(1200, 800);
    scrolledTo(0);
    render(<RecordTable table={recordTableController()} />);
    expect(sides()).toEqual({ start: false, end: true });

    scrolledTo(400);
    port().dispatchEvent(new Event('scroll'));

    await vi.waitFor(() =>
      expect(sides()).toEqual({ start: true, end: false }),
    );
  });
});
