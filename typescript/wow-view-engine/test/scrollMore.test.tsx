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
  act,
  cleanup,
  render,
  renderHook,
  screen,
} from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  SCROLL_FADE,
  columnsPast,
  fadeMask,
  measurePort,
  rowsUnder,
  useScrollMore,
  wholeHeight,
} from '../src/ui/dashboard/scrollMore.js';
import {
  GROWS_TO_ROWS,
  fittedLayout,
  rowsFor,
  usePanelWholes,
} from '../src/ui/dashboard/panelFit.js';
import { ScrollCue } from '../src/ui/dashboard/ScrollCue.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';

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

/** An element jsdom lays out by hand: where its box stands on screen. */
function place(
  element: Element,
  box: { top: number; bottom: number; left?: number; right?: number },
) {
  const { top, bottom, left = 0, right = 100 } = box;
  element.getBoundingClientRect = () =>
    ({
      top,
      bottom,
      left,
      right,
      height: bottom - top,
      width: right - left,
      x: left,
      y: top,
    }) as DOMRect;
}

/** A table of `count` rows 40px tall under a 40px header, each placed. */
function table(count: number, attributes: { virtual?: number } = {}) {
  const element = document.createElement('table');
  const head = element.createTHead().insertRow();
  for (const name of ['a', 'b', 'c'])
    head.appendChild(document.createElement('th')).textContent = name;
  const body = element.createTBody();
  for (let index = 0; index < count; index += 1) {
    const row = body.insertRow();
    row.insertCell().textContent = String(index);
    row.insertCell();
    if (attributes.virtual)
      row.setAttribute('aria-rowindex', String(index + 2));
    place(row, { top: 40 + index * 40, bottom: 80 + index * 40 });
  }
  if (attributes.virtual)
    element.setAttribute('aria-rowcount', String(attributes.virtual));
  return element;
}

/**
 * What is under a panel body's bottom edge, counted, since a fade there
 * would wash out the table's sticky header and totals (P1-3).
 */
describe('rowsUnder', () => {
  it('counts the rows not wholly above the edge', () => {
    // Rows end at 80, 120, … 480; an edge at 300 holds six whole.
    expect(rowsUnder(table(11), 300)).toBe(5);
    expect(rowsUnder(table(11), 480)).toBe(0);
  });

  it('counts the rows a virtual table does not draw from where the view ends', () => {
    // Twenty rows drawn of a thousand, the totals row last (1 header + 1000 + 1).
    const drawn = table(20, { virtual: 1002 });
    drawn.createTFoot().insertRow().setAttribute('aria-rowindex', '1002');
    // Five whole rows in view: 995 under the edge.
    expect(rowsUnder(drawn, 240)).toBe(995);
  });

  it('leaves out the room a virtual table keeps for the rows it does not draw', () => {
    const drawn = table(2);
    const room = drawn.tBodies[0].insertRow();
    room.insertCell().colSpan = 3;
    place(room, { top: 120, bottom: 4000 });
    expect(rowsUnder(drawn, 200)).toBe(0);
  });
});

describe('columnsPast', () => {
  it('counts the header cells not wholly before the edge, the filler aside', () => {
    const element = table(1);
    const [a, b, c] = element.tHead!.rows[0].cells;
    place(a, { top: 0, bottom: 40, left: 0, right: 100 });
    place(b, { top: 0, bottom: 40, left: 100, right: 200 });
    place(c, { top: 0, bottom: 40, left: 200, right: 300 });
    expect(columnsPast(element, 150)).toBe(2);
    c.dataset.column = 'filler';
    expect(columnsPast(element, 150)).toBe(1);
    expect(columnsPast(document.createElement('table'), 0)).toBe(0);
  });
});

/** A port with its box and its content's, laid by hand. */
function port(box: {
  client: number;
  content: number;
  parent: number;
  top?: number;
}) {
  const parent = document.createElement('div');
  const node = parent.appendChild(document.createElement('div'));
  const child = node.appendChild(document.createElement('div'));
  const top = box.top ?? 100;
  Object.defineProperty(parent, 'offsetHeight', { value: box.parent });
  for (const [name, value] of [
    ['clientHeight', box.client],
    ['offsetHeight', box.client],
    ['scrollHeight', Math.max(box.client, box.content)],
    ['scrollTop', 0],
    ['scrollWidth', 300],
    ['clientWidth', 300],
    ['offsetWidth', 300],
    ['scrollLeft', 0],
  ] as const)
    Object.defineProperty(node, name, { value });
  place(node, { top, bottom: top + box.client });
  place(child, { top, bottom: top + box.content });
  document.body.appendChild(parent);
  return { node, child };
}

describe('wholeHeight', () => {
  it('is the card less the body, plus what the body stacks — whatever the body’s height', () => {
    // A card 440 tall around a 338px body: 102px of chrome.
    expect(
      wholeHeight(port({ client: 338, content: 579, parent: 440 }).node),
    ).toBe(681);
    // Grown, the same content asks for the same height.
    expect(
      wholeHeight(port({ client: 608, content: 579, parent: 710 }).node),
    ).toBe(681);
    // Then a short page: it asks for less, which `scrollHeight` never says.
    expect(
      wholeHeight(port({ client: 608, content: 200, parent: 710 }).node),
    ).toBe(302);
  });

  it('leaves out what takes no room in the stack: a hidden block, a layer over it', () => {
    const { node } = port({ client: 338, content: 300, parent: 440 });
    const hidden = node.appendChild(document.createElement('span'));
    place(hidden, { top: 0, bottom: 0, left: 0, right: 0 });
    const layer = node.appendChild(document.createElement('span'));
    layer.style.position = 'absolute';
    place(layer, { top: 100, bottom: 900 });
    expect(wholeHeight(node)).toBe(402);
  });

  it('asks nothing of a body with no card around it', () => {
    expect(wholeHeight(document.createElement('div'))).toBe(0);
  });
});

describe('measurePort', () => {
  it('asks for the whole height only where the body holds a table, or fits its content', () => {
    const { node } = port({ client: 118, content: 183, parent: 170 });
    expect(measurePort(node)).toMatchObject({ below: true, whole: 0 });
    expect(measurePort(node, true)).toMatchObject({ below: true, whole: 235 });
    expect(measurePort(node).rows).toBeUndefined();
  });

  it('counts the rows under the edge and stands clear of the totals band', () => {
    const { node, child } = port({
      client: 300,
      content: 520,
      parent: 400,
      top: 0,
    });
    child.dataset.slot = 'record-table';
    const rows = child.appendChild(table(11));
    const foot = rows.createTFoot();
    foot.dataset.sticky = 'bottom';
    place(foot, { top: 260, bottom: 300 });
    // Rows end at 80 … 480; the band's top at 260 holds five whole.
    expect(measurePort(node)).toMatchObject({
      below: true,
      rows: 6,
      inset: 40,
      whole: 620,
    });
  });
});

describe('fittedLayout', () => {
  const boxes = [
    { id: 'table', x: 0, y: 0, w: 12, h: 5 },
    { id: 'chart', x: 12, y: 0, w: 12, h: 5 },
    { id: 'under', x: 0, y: 5, w: 24, h: 4 },
  ];

  it('grows a panel by whole rows to hold its body, and moves the rest down', () => {
    // 681px is 8 rows of 80 with 10 between (710px); 7 are 620px.
    expect(rowsFor(681, 80)).toBe(8);
    expect(rowsFor(620, 80)).toBe(7);
    expect(rowsFor(0, 80)).toBe(1);
    const fitted = fittedLayout(boxes, new Map([['table', 620]]), 80);
    expect(fitted).toEqual([
      { id: 'table', x: 0, y: 0, w: 12, h: 7 },
      boxes[1],
      { id: 'under', x: 0, y: 7, w: 24, h: 4 },
    ]);
  });

  it('stops at the cap, keeps a taller saved height, and never shrinks one', () => {
    const tall = fittedLayout(boxes, new Map([['table', 5000]]), 80);
    expect(tall[0].h).toBe(GROWS_TO_ROWS);
    const saved = [{ ...boxes[0], h: 12 }];
    expect(fittedLayout(saved, new Map([['table', 5000]]), 80)).toEqual(saved);
    expect(
      fittedLayout(
        boxes,
        new Map([
          ['table', 100],
          ['chart', 0],
        ]),
        80,
      ),
    ).toEqual(boxes);
  });
});

/** The cue on a body's bottom edge, in both catalogues. */
describe('ScrollCue', () => {
  const cue = () =>
    document.querySelector<HTMLElement>('[data-slot="panel-scroll-cue"]');

  it('says the rows under the edge and the columns past it, and is no control', () => {
    render(
      <ScrollCue
        scroll={{ below: true, rows: 6, columns: 2, inset: 40, whole: 0 }}
      />,
    );
    expect(cue()?.textContent).toBe(
      '6 more rows below, 2 more columns to the right',
    );
    expect(cue()?.dataset.rows).toBe('6');
    expect(cue()?.style.bottom).toBe('44px');
    // A picture of the scroll: nothing to Tab to, nothing a reader hears.
    expect(cue()?.closest('[aria-hidden="true"]')).not.toBeNull();
    expect(cue()?.tabIndex).toBe(-1);
  });

  it('says one of each in the singular, and more with no table', () => {
    const { rerender } = render(
      <ScrollCue scroll={{ below: true, rows: 1, inset: 0, whole: 0 }} />,
    );
    expect(cue()?.textContent).toBe('1 more row below');
    rerender(<ScrollCue scroll={{ columns: 1, inset: 0, whole: 0 }} />);
    expect(cue()?.textContent).toBe('1 more column to the right');
    rerender(<ScrollCue scroll={{ below: true, inset: 0, whole: 0 }} />);
    expect(cue()?.textContent).toBe('More below');
    rerender(<ScrollCue scroll={{ inset: 0, whole: 0 }} />);
    expect(cue()).toBeNull();
  });

  it('speaks Chinese', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <ScrollCue
          scroll={{ below: true, rows: 5, columns: 3, inset: 0, whole: 0 }}
        />
      </MessagesProvider>,
    );
    expect(cue()?.textContent).toBe('下面还有 5 行，右边还有 3 列');
  });
});

describe('usePanelWholes', () => {
  it('keeps each panel’s height, one reporter a panel, and shrugs off a scrollbar', () => {
    const { result } = renderHook(() => usePanelWholes());
    const report = result.current.reporter('a');
    expect(result.current.reporter('a')).toBe(report);
    act(() => report(681));
    expect(result.current.wholes.get('a')).toBe(681);
    // 15px less is the horizontal scrollbar gone once the panel grew.
    act(() => report(666));
    expect(result.current.wholes.get('a')).toBe(681);
    // A shorter page is a shorter body, and nothing to draw is nothing.
    act(() => report(302));
    expect(result.current.wholes.get('a')).toBe(302);
    act(() => report(0));
    expect(result.current.wholes.get('a')).toBe(0);
  });
});
