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

import { StrictMode } from 'react';
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryViewStore, ViewEngine } from '../src/index.js';
import type { ViewInstance } from '../src/index.js';
import { useWorkbench } from '../src/react/index.js';
import { defaultMessages, WorkbenchShell } from '../src/ui/index.js';
import type { WorkbenchShellProps } from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

afterEach(cleanup);

const EXPAND = defaultMessages['label.workbench.expand-sidebar'];
const COLLAPSE = defaultMessages['label.workbench.collapse-sidebar'];

const saved: ViewInstance = {
  id: 'mine',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

function engineWith(): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition()],
    store: new MemoryViewStore({ instances: [saved] }),
    resolveSource: () => testSource(),
  });
}

/**
 * The shell over a real workbench, with the props under test handed to it
 * directly.
 *
 * The three default workbenches fill only some of these — none of them hands
 * over a controlled fold, and only the dashboard turns the result's surface
 * off — so bending one of them into the remaining shapes would test the
 * bending. This renders the thing that owns the props instead.
 */
function Shell({
  engine,
  hold,
  ...props
}: {
  engine: ViewEngine;
  /** Lets a test reach the very runtime the shell is driving. */
  hold?(workbench: ReturnType<typeof useWorkbench>): void;
} & Partial<WorkbenchShellProps>) {
  const workbench = useWorkbench(engine, 'orders', {
    kinds: ['record'],
    instanceId: 'mine',
  });
  hold?.(workbench);
  return (
    <WorkbenchShell
      workbench={workbench}
      title="Orders"
      editor={<div data-slot="stub-editor">conditions</div>}
      result={<div data-slot="stub-result">rows</div>}
      {...props}
    />
  );
}

/** The shell, rendered and settled on its view. */
async function open(props: Partial<WorkbenchShellProps> = {}) {
  const user = userEvent.setup();
  render(<Shell engine={engineWith()} {...props} />);
  await screen.findByText('rows');
  return user;
}

const block = (name: string) =>
  document.querySelector<HTMLElement>(`[data-slot="${name}"]`);

describe('the result block', () => {
  it('draws the result in one frame, and a dashboard in none', async () => {
    await open();

    const result = block('result-block')!;
    expect(result.querySelector('[data-slot="stub-result"]')).not.toBeNull();
    // One band for the result (D12 Ⅴ): the toolbar is its top row and
    // the pagination its bottom row, the rows run to its edge. Without it
    // the toolbar, the table and the pagination read as three things that
    // happen to be stacked, and the region has no end.
    expect(result.dataset.framed).toBe('true');
    // **Surviving class assertions**: layout at a call site, which is what
    // `className` is for — there is no state behind a length or a
    // direction, and jsdom lays nothing out. The pixels are the browser
    // stories’.
    // A band rather than a card: one rule across its top, and its sides
    // and bottom are the work column's own edges, bled through its padding.
    expect(result.className).toContain('border-t');
    expect(result.className).toContain('-mx-4');
    expect(result.className).not.toContain('rounded-lg');
    // But no padding of its own — the rows go to the edge; what needs a
    // margin (the toolbar, the pagination, a strip) gets it by slot.
    expect(result.className).not.toMatch(/(^|\s)p-\d/);
    expect(result.className).not.toContain('bg-card');

    cleanup();
    await open({ resultFramed: false });
    // A dashboard's result is a grid of cards, and a frame round cards is a
    // frame round frames.
    const grid = block('result-block')!;
    expect(grid.dataset.framed).toBeUndefined();
    expect(grid.className).not.toContain('border');
  });
});

/**
 * A frame is a frame round a result (F-14). A config the definition refuses
 * never ran, so there is nothing for a frame to be round — and what stood
 * there was an empty one holding a toolbar whose Export was still pressable.
 */
describe('the result block exists only where there is a result', () => {
  /** The shell settled on its view, whatever it decided to draw under it. */
  async function opened(props: Partial<WorkbenchShellProps>) {
    render(<Shell engine={engineWith()} {...props} />);
    await waitFor(() => expect(block('view-header-block')).not.toBeNull());
  }

  it('draws no block at all for a config that never ran', async () => {
    await opened({ hasResult: false, resultPending: false });

    expect(block('result-block')).toBeNull();
    expect(screen.queryByText('rows')).toBeNull();
  });

  it('draws one while a request is on its way', async () => {
    await opened({ hasResult: false, resultPending: true });

    expect(block('result-block')).not.toBeNull();
    expect(screen.getByText('rows')).toBeTruthy();
  });

  it('draws one for a strip with no rows under it', async () => {
    await opened({
      hasResult: false,
      resultPending: false,
      strips: <div data-slot="stub-strip">the query failed</div>,
    });

    // A query that failed has something to say about the rows that are not
    // there, and the strip is the block's own line.
    expect(block('result-block')).not.toBeNull();
    expect(screen.getByText('the query failed')).toBeTruthy();
  });

  it('keeps an unframed block, which has no frame to be empty', async () => {
    await opened({
      resultFramed: false,
      hasResult: false,
      resultPending: false,
    });

    // The dashboard's grid is its result whether a panel has answered yet
    // or not, and an editable board with nothing on it is where panels are
    // added from.
    expect(block('result-block')).not.toBeNull();
    expect(screen.getByText('rows')).toBeTruthy();
  });
});

/** D12 IV: the toolbar is the block's first row, whatever else it holds. */
describe('the order inside the block', () => {
  it('puts the toolbar above the failure strip', async () => {
    await open({
      toolbar: <div data-slot="stub-toolbar">toolbar</div>,
      strips: <div data-slot="stub-strip">the query failed</div>,
    });

    expect(
      [...block('result-block')!.children].map(child =>
        child.getAttribute('data-slot'),
      ),
    ).toEqual(['stub-toolbar', 'stub-strip', 'stub-result']);
  });
});

/**
 * P-13: what stands there while a view opens is the shape of the page that
 * is coming, not one bar saying that something is.
 */
describe('opening a view', () => {
  /** An engine whose store never answers, so the open never settles. */
  function neverOpens(): ViewEngine {
    const store = new MemoryViewStore({ instances: [saved] });
    store.get = () => new Promise<ViewInstance>(() => {});
    return new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
  }

  async function skeleton(
    props: Partial<WorkbenchShellProps> = {},
  ): Promise<HTMLElement> {
    render(<Shell engine={neverOpens()} {...props} />);
    await waitFor(() => expect(block('opening-skeleton')).not.toBeNull());
    return block('opening-skeleton')!;
  }

  it('draws the title bar and the framed result the view will have', async () => {
    const shape = await skeleton();

    expect(shape.getAttribute('aria-busy')).toBe('true');
    // Said once, by a live region of its own: `aria-busy` on a live region
    // holds its announcements back, and this one never turns false.
    expect(within(shape).getByRole('status').textContent).toBe(
      defaultMessages['label.workbench.opening'],
    );

    expect(
      shape.querySelector('[data-slot="view-header-skeleton"]'),
    ).not.toBeNull();
    const result = shape.querySelector<HTMLElement>(
      '[data-slot="result-block"]',
    )!;
    expect(result.dataset.framed).toBe('true');
    // The toolbar is the first row of the frame here too, and the rows
    // under it are rows.
    expect(result.firstElementChild?.getAttribute('data-slot')).toBe(
      'result-toolbar',
    );
    expect(
      result.querySelectorAll(
        '[data-slot="result-rows-skeleton"] [data-slot="skeleton"]',
      ),
    ).toHaveLength(3);
    // The shape of an answer is not an answer: nothing in it is read out.
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('leaves the title bar out where the collapsed row already stands there', async () => {
    const shape = await skeleton({
      defaultSidebarOpen: false,
      resultFramed: false,
    });

    // The shell draws the way back and the switcher in that place while the
    // list is folded away; a second bar under it would be two title bars.
    expect(screen.getByRole('button', { name: EXPAND })).toBeTruthy();
    expect(
      shape.querySelector('[data-slot="view-header-skeleton"]'),
    ).toBeNull();
    // And a dashboard's skeleton frames nothing, as its result does not.
    expect(
      shape.querySelector<HTMLElement>('[data-slot="result-block"]')!.dataset
        .framed,
    ).toBeUndefined();
  });
});

describe('the editor fold', () => {
  it('draws the editor plainly when it has no name to fold under', async () => {
    await open();

    // No `editorLabel` means no fold: the analysis editor has no settled
    // shape yet (decisions.md Q2), and a fold is a decision about shape.
    expect(block('condition-block')).not.toBeNull();
    expect(block('editor-band')).toBeNull();
    expect(screen.queryByRole('button', { name: /^Filter/ })).toBeNull();
  });

  it('folds under a name, and opens on the rule a saved view sets', async () => {
    const user = await open({ editorLabel: 'Filter' });

    // A saved view opens folded — its author already decided — and folding
    // unmounts rather than hides, so the editor is not on the page at all.
    expect(block('editor-band')).toBeNull();
    const toggle = screen.getByRole('button', { name: 'Filter' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.hasAttribute('aria-controls')).toBe(false);

    await user.click(toggle);
    expect(block('editor-band')).not.toBeNull();
    expect(toggle.getAttribute('aria-controls')).toBe(block('editor-band')!.id);
  });

  it('opens on what the host asked for instead, when it asked', async () => {
    await open({ editorLabel: 'Filter', defaultEditorOpen: true });

    expect(block('editor-band')).not.toBeNull();
  });

  it('obeys a host that holds the state itself', async () => {
    const changed = vi.fn();
    const user = await open({
      editorLabel: 'Filter',
      editorOpen: false,
      onEditorOpenChange: changed,
    });

    await user.click(screen.getByRole('button', { name: 'Filter' }));
    // Told, but not moved: a controlled fold is the host's to change, and a
    // shell that opened anyway would fight whoever owns it.
    expect(changed).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenCalledWith(true);
    expect(block('editor-band')).toBeNull();
  });

  it('offers the modes under a chevron, not as a word on the toggle', async () => {
    const user = await open({
      editorLabel: 'Filter',
      editorModes: <div data-slot="stub-modes">modes</div>,
    });

    // The toggle is called what it opens; the mode is the menu's to show.
    expect(screen.getByRole('button', { name: 'Filter' })).toBeDefined();

    await user.click(
      screen.getByRole('button', {
        name: defaultMessages['label.workbench.editor-modes'],
      }),
    );
    expect(await screen.findByText('modes')).toBeDefined();
  });

  it('shows no chevron when the editor offers no modes', async () => {
    await open({ editorLabel: 'Filter' });

    expect(
      screen.queryByRole('button', {
        name: defaultMessages['label.workbench.editor-modes'],
      }),
    ).toBeNull();
  });

  it('carries the count of what is not applied, folded or not', async () => {
    await open({ editorLabel: 'Filter', editorPending: 2 });

    // The one credential a folded editor cannot carry for itself. The count
    // is part of the button's text, so the name is matched loosely.
    expect(
      screen.getByRole('button', { name: /^Filter/ }).textContent,
    ).toContain('2 not applied');
  });

  it('says nothing about pending when nothing is', async () => {
    await open({ editorLabel: 'Filter', editorPending: 0 });

    expect(
      screen.getByRole('button', { name: 'Filter' }).textContent,
    ).not.toContain('not applied');
  });
});

describe('the result block', () => {
  it('draws no block at all when there would be nothing in it', async () => {
    const user = userEvent.setup();
    // An analysis that has not run: no result to caption, no strip, and a
    // workbench that has not filled the result slot either.
    render(
      <Shell engine={engineWith()} result={undefined} hasResult={false} />,
    );
    await screen.findByRole('heading', { name: 'Mine' });
    await waitFor(() => expect(block('view-header-block')).not.toBeNull());

    // A bordered card around three things that all render nothing is the
    // empty block this package's own layout rule forbids.
    expect(block('result-block')).toBeNull();
    expect(user).toBeDefined();
  });

  it('draws the block for a strip alone, with no result yet', async () => {
    render(
      <Shell
        engine={engineWith()}
        result={undefined}
        hasResult={false}
        strips={<div>could not be run</div>}
      />,
    );
    await screen.findByText('could not be run');

    expect(block('result-block')).not.toBeNull();
  });

  it('counts an unfilled slot as empty, not as content', async () => {
    // A workbench fills a slot with `condition && <Thing/>`, so an unfilled
    // one arrives as `false` rather than as nothing at all.
    render(
      <Shell
        engine={engineWith()}
        result={false as unknown as undefined}
        hasResult={false}
      />,
    );
    await screen.findByRole('heading', { name: 'Mine' });

    expect(block('result-block')).toBeNull();
  });
});

describe('the editing state the fold owns', () => {
  it('ends editing when a controlled fold is closed over a focused input', async () => {
    const engine = engineWith();
    let held: ReturnType<typeof useWorkbench> | null = null;
    const hold = (workbench: ReturnType<typeof useWorkbench>) => {
      held = workbench;
    };
    const { rerender } = render(
      <Shell engine={engine} hold={hold} editorLabel="Filter" editorOpen />,
    );
    await screen.findByText('conditions');

    // Whatever an input inside the editor started, the runtime is holding.
    const runtime = held!.runtime!;
    act(() => runtime.setEditing(true));
    expect(runtime.getSnapshot().editing).toBe(true);

    rerender(
      <Shell
        engine={engine}
        hold={hold}
        editorLabel="Filter"
        editorOpen={false}
      />,
    );

    // Closing unmounts the inputs, and an unmounted input fires no blur, so
    // nothing else would ever turn this off: auto refresh would stay paused
    // for as long as the view is open.
    await waitFor(() => expect(runtime.getSnapshot().editing).toBe(false));
  });
});

describe('the sidebar as a host sets it', () => {
  it("leaves the page's focus alone on arrival, StrictMode included", async () => {
    render(
      <StrictMode>
        <Shell engine={engineWith()} />
      </StrictMode>,
    );
    await screen.findByText('rows');

    // StrictMode does setup, cleanup, setup on mount. A "first run" flag is
    // already spent by the second setup, and the effect would take focus off
    // the host's page for a state nobody asked for.
    expect(document.activeElement).toBe(document.body);
  });

  it('still moves focus on a real change under StrictMode', async () => {
    const user = userEvent.setup();
    render(
      <StrictMode>
        <Shell engine={engineWith()} />
      </StrictMode>,
    );
    await screen.findByText('rows');

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    expect(document.activeElement).toBe(
      screen.getByRole('button', { name: EXPAND }),
    );
  });

  it('opens folded when the host says so, and can still be opened', async () => {
    const changed = vi.fn();
    const user = await open({
      defaultSidebarOpen: false,
      onSidebarOpenChange: changed,
    });

    expect(block('view-sidebar')).toBeNull();
    await user.click(screen.getByRole('button', { name: EXPAND }));

    expect(block('view-sidebar')).not.toBeNull();
    expect(changed).toHaveBeenCalledExactlyOnceWith(true);
  });

  it('holds the state itself when the host only wants to be told', async () => {
    const user = await open();

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    // No `onSidebarOpenChange` given, and it still folds: the state is the
    // shell's, and the callback is a notification rather than the owner.
    await waitFor(() => expect(block('view-sidebar')).toBeNull());
  });
});

/** A `ResizeObserver` that reports what it was given and fires on demand. */
class ResizeSpy {
  readonly observed: Element[] = [];
  constructor(private readonly callback: ResizeObserverCallback) {}
  observe(node: Element): void {
    this.observed.push(node);
  }
  unobserve(): void {}
  disconnect(): void {}
  resize(): void {
    this.callback([], this as unknown as ResizeObserver);
  }
}

/**
 * What the shell decides for itself when the host says nothing.
 *
 * Below `md` the list is not beside the view but stacked over it, so a
 * column that narrow opens folded — and it is the surface's own width that
 * answers, not the viewport's, because a 360px panel on a wide page is the
 * same phone-shaped column.
 */
describe('the sidebar the shell decides', () => {
  /**
   * The width the surface reports for the rest of this test.
   *
   * jsdom lays nothing out and answers 0 to every measurement, so the one
   * number this rule reads is the one thing a test here has to supply. It is
   * put on the prototype because the surface is not in the document until
   * `render` has run, and taken off again by `restoreAllMocks`.
   */
  const laidOutAt = (width: number) =>
    vi
      .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
      .mockReturnValue({ width } as DOMRect);

  /**
   * The surface's own observer, and the handle that fires it.
   *
   * Every stub that was ever constructed is kept, because the shell rebuilds
   * its observer on each render — the one that matters is the last one still
   * watching the surface.
   */
  function watchingSurface(): () => void {
    const spies: ResizeSpy[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class extends ResizeSpy {
        constructor(callback: ResizeObserverCallback) {
          super(callback);
          spies.push(this);
        }
      },
    );
    return () => {
      const surface = block('view-surface')!;
      const watching = spies.filter(spy => spy.observed.includes(surface));
      act(() => watching[watching.length - 1]!.resize());
    };
  }

  afterEach(() => vi.unstubAllGlobals());

  it('opens folded in a column narrower than md', async () => {
    laidOutAt(375);
    await open();

    expect(block('view-sidebar')).toBeNull();
    // And the way to it is where it always is while the list is away.
    expect(screen.getByRole('button', { name: EXPAND })).toBeDefined();
  });

  it('opens with the list beside the view from md up', async () => {
    laidOutAt(1024);
    await open();

    expect(block('view-sidebar')).not.toBeNull();
  });

  it('takes a width of zero as no answer rather than as narrow', async () => {
    // jsdom's own answer, and a detached or unlaid-out host's: nothing at
    // all. Nothing is not a reason to fold a list away.
    await open();

    expect(block('view-sidebar')).not.toBeNull();
  });

  it('obeys a host that said so, however narrow the column', async () => {
    laidOutAt(375);
    await open({ defaultSidebarOpen: true });

    // The host knows something about its page that a measurement does not.
    expect(block('view-sidebar')).not.toBeNull();
  });

  it('takes no focus when it folds itself on arrival', async () => {
    laidOutAt(375);
    await open();

    // Nothing was pressed, so nothing follows a press. A page that moved the
    // keyboard onto its own button as it loaded would take the cursor out of
    // whatever the host's page was doing.
    expect(document.activeElement).toBe(document.body);
  });

  it('tells a host that mirrors the fold, even when it folded itself', async () => {
    laidOutAt(375);
    const changed = vi.fn();
    await open({ onSidebarOpenChange: changed });

    // A notification and not a decision: the host asked to be told whenever
    // this changes, and this is a change.
    expect(changed).toHaveBeenCalledExactlyOnceWith(false);
  });

  it('follows the width both ways, not only on arrival', async () => {
    const rect = laidOutAt(1280);
    const resize = watchingSurface();
    const changed = vi.fn();
    await open({ onSidebarOpenChange: changed });
    expect(block('view-sidebar')).not.toBeNull();

    // A window dragged narrow is the same phone-shaped column as one that
    // opened narrow: the list was beside the view and now would be stacked
    // on top of it, eating 235px above the first row.
    rect.mockReturnValue({ width: 608 } as DOMRect);
    resize();
    expect(block('view-sidebar')).toBeNull();

    // And back, because the room it was folded for is there again.
    rect.mockReturnValue({ width: 1280 } as DOMRect);
    resize();
    expect(block('view-sidebar')).not.toBeNull();

    rect.mockReturnValue({ width: 608 } as DOMRect);
    resize();
    expect(block('view-sidebar')).toBeNull();

    // The host mirroring the fold hears every one of those, and nothing in
    // between: a change is reported once, when it happens.
    expect(changed.mock.calls).toEqual([[false], [true], [false]]);
    // Nothing was pressed, so the keyboard stayed where the user left it.
    expect(document.activeElement).toBe(document.body);
  });

  it('leaves the fold alone once the user has pressed it', async () => {
    const rect = laidOutAt(1280);
    const resize = watchingSurface();
    const user = await open();

    await user.click(screen.getByRole('button', { name: COLLAPSE }));
    expect(block('view-sidebar')).toBeNull();

    // Narrow and wide again. The measurement agreed with the press on the
    // way down and would disagree on the way back up — and it is the press
    // that stands: undoing what the user explicitly asked for, under their
    // hands, on every drag of the window, is worse than a list that is
    // folded in a column with room for it.
    rect.mockReturnValue({ width: 608 } as DOMRect);
    resize();
    rect.mockReturnValue({ width: 1280 } as DOMRect);
    resize();
    expect(block('view-sidebar')).toBeNull();

    // The same the other way: a list called back in a narrow column stays.
    await user.click(screen.getByRole('button', { name: EXPAND }));
    rect.mockReturnValue({ width: 608 } as DOMRect);
    resize();
    expect(block('view-sidebar')).not.toBeNull();
  });
});
