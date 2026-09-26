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

import { act, cleanup, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  SCROLL_FADE,
  fadeMask,
  useScrollMore,
} from '../src/ui/dashboard/scrollMore.js';

afterEach(cleanup);

/** A port jsdom lays out by hand: its content, its box and its scroll. */
function lay(
  node: HTMLElement,
  box: { content: number; client: number; offset?: number; left?: number },
) {
  const define = (name: string, value: number) =>
    Object.defineProperty(node, name, { configurable: true, value });
  define('scrollWidth', box.content);
  define('clientWidth', box.client);
  define('offsetWidth', box.offset ?? box.client);
  define('scrollLeft', box.left ?? 0);
}

function Port({ box }: { box: Parameters<typeof lay>[1] }) {
  const [node, setNode] = useState<HTMLDivElement | null>(null);
  if (node) lay(node, box);
  const { more, style } = useScrollMore(node);
  return (
    <div
      ref={setNode}
      data-testid="port"
      data-scroll-more={more}
      style={style}
    />
  );
}

const frame = () =>
  act(
    () => new Promise<void>(resolve => requestAnimationFrame(() => resolve())),
  );

/**
 * A panel's table wider than the panel says so: the body fades out towards
 * the side with more columns past it (W13, where macOS hid the scrollbar
 * and 「最早下次重试」 read as not there at all).
 */
describe('useScrollMore', () => {
  it('says nothing where everything fits', () => {
    render(<Port box={{ content: 400, client: 400 }} />);
    const port = screen.getByTestId('port');
    expect(port.dataset.scrollMore).toBeUndefined();
  });

  it('fades towards the end until scrolled there, then towards the start', async () => {
    const { rerender } = render(<Port box={{ content: 700, client: 550 }} />);
    // The first measure runs once the port is known.
    rerender(<Port box={{ content: 700, client: 550 }} />);
    await frame();
    const port = screen.getByTestId('port');
    // The mask itself is `fadeMask`'s (below); jsdom keeps no mask style.
    expect(port.dataset.scrollMore).toBe('end');

    lay(port, { content: 700, client: 550, left: 70 });
    port.dispatchEvent(new Event('scroll'));
    await frame();
    expect(port.dataset.scrollMore).toBe('both');

    lay(port, { content: 700, client: 550, left: 150 });
    port.dispatchEvent(new Event('scroll'));
    await frame();
    expect(port.dataset.scrollMore).toBe('start');
  });
});

describe('fadeMask', () => {
  it('fades the end over the fade, and leaves the scrollbar whole', () => {
    expect(fadeMask('end', 15).maskImage).toBe(
      `linear-gradient(to right, #000 0, #000 calc(100% - ${15 + SCROLL_FADE}px), transparent calc(100% - 15px), #000 calc(100% - 15px))`,
    );
  });

  it('fades the start, and both sides at once, with no scrollbar', () => {
    expect(fadeMask('start', 0).maskImage).toBe(
      `linear-gradient(to right, transparent 0, #000 ${SCROLL_FADE}px, #000 calc(100% - 0px))`,
    );
    expect(fadeMask('both', 0).WebkitMaskImage).toBe(
      `linear-gradient(to right, transparent 0, #000 ${SCROLL_FADE}px, #000 calc(100% - ${SCROLL_FADE}px), transparent calc(100% - 0px))`,
    );
  });
});
