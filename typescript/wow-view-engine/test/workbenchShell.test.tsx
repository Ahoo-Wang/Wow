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
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
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
    kind: 'record',
    instanceId: 'mine',
  });
  hold?.(workbench);
  return (
    <WorkbenchShell
      workbench={workbench}
      kind="record"
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
  it('draws the result on a surface, with its caption at the top', async () => {
    await open();

    const result = block('result-block')!;
    // The applied bar is the caption of these rows, so it is inside the
    // block rather than floating above it.
    expect(result.className).toContain('bg-card');
    expect(result.querySelector('[data-slot="stub-result"]')).not.toBeNull();
  });

  it('leaves the surface off when the result is already made of cards', async () => {
    await open({ resultSurface: false });

    // A dashboard's result is a grid of panels, each one a card. A card
    // around them is a frame around a frame.
    const result = block('result-block')!;
    expect(result.className).not.toContain('bg-card');
    expect(result.querySelector('[data-slot="stub-result"]')).not.toBeNull();
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

  it('names the mode on the toggle, and offers it under a chevron', async () => {
    const user = await open({
      editorLabel: 'Filter',
      editorModeLabel: 'Simple',
      editorModes: <div data-slot="stub-modes">modes</div>,
    });

    // "Filter · Simple" answers both what this is and how it is set, so the
    // mode is not a second control to go and find.
    const toggle = screen.getByRole('button', { name: 'Filter · Simple' });
    expect(toggle.textContent).toContain('Simple');

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
