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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  EditorBand,
  EditorBandToggle,
  EditorFold,
} from '../src/ui/EditorBand.js';
import { DropdownMenuItem } from '../src/ui/components/dropdown-menu.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';
import { ViewSurface } from '../src/ui/ViewSurface.js';

afterEach(cleanup);

const BAND_ID = 'editor-band';

/**
 * The fold as a workbench drives it: the handle in the title bar, the band
 * below it, and the one piece of state they share held above both.
 */
function Fold({
  initial = false,
  pending = 0,
  modes = false,
}: {
  initial?: boolean;
  pending?: number;
  modes?: boolean;
}) {
  const [open, setOpen] = useState(initial);
  return (
    <ViewSurface>
      {/* One root around both ends: the handle is in the title bar and the
          band is below it, with blocks in between, so they are wrapped
          rather than nested. */}
      <EditorFold open={open} onOpenChange={setOpen}>
        <EditorBandToggle
          label="Filter"
          modes={
            modes ? <DropdownMenuItem>Advanced</DropdownMenuItem> : undefined
          }
          pending={pending}
        />
        <EditorBand id={BAND_ID}>
          {/* Whatever a workbench folds away names itself; the band does not
              name it a second time. */}
          <section aria-label="Filter">The editor</section>
        </EditorBand>
      </EditorFold>
    </ViewSurface>
  );
}

/** The handle, by the name it wears with or without a mode appended. */
function toggle(): HTMLElement {
  return screen.getByRole('button', { name: /Filter/ });
}

/** The fold itself, which has no role to be found by. */
function band(): HTMLElement {
  const found = document.querySelector('[data-slot="editor-band"]');
  if (!found) throw new Error('no band');
  return found as HTMLElement;
}

describe('EditorBand', () => {
  it('keeps its content out of the way until the fold is opened', () => {
    render(<Fold />);

    expect(screen.queryByText('The editor')).toBeNull();

    fireEvent.click(toggle());

    expect(screen.getByText('The editor')).toBeDefined();
  });

  /**
   * Unmounted rather than hidden: the editor's inputs are the view's draft,
   * and a folded band that kept them would leave focusable controls on a
   * page that shows no editor.
   */
  it('leaves nothing of the folded editor in the document', () => {
    render(<Fold />);

    expect(document.querySelector('[data-slot="editor-band"]')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Filter' })).toBeNull();
  });

  /**
   * No landmark of its own: the editor inside already names itself, and a
   * band wrapped round it under the same name is a second landmark a screen
   * reader cannot tell from the first.
   */
  it('adds no name of its own over the editor that has one', () => {
    render(<Fold initial />);

    expect(screen.getAllByRole('region', { name: 'Filter' })).toHaveLength(1);
    expect(band().getAttribute('role')).toBeNull();
    expect(band().getAttribute('aria-label')).toBeNull();
  });

  it('opens with the state its caller gave it', () => {
    render(<Fold initial />);

    // A view that was never saved opens on its editor; the band holds no
    // opinion about which, and takes the answer from above.
    expect(screen.getByText('The editor')).toBeDefined();
  });

  /** The band carries the id and nothing else, so the handle in the title
   * bar can say what it opens. */
  it('wears the id the handle points at', () => {
    render(<Fold initial />);

    expect(band().id).toBe(BAND_ID);
    expect(toggle().getAttribute('aria-controls')).toBe(BAND_ID);
  });
});

describe('EditorBandToggle', () => {
  it('reports the open state of the fold it is the handle of', () => {
    render(<Fold />);

    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    // Nothing to control while the band is unmounted: an `aria-controls`
    // pointing at no element is a broken reference, not an empty one.
    expect(toggle().getAttribute('aria-controls')).toBeNull();

    fireEvent.click(toggle());

    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(toggle().getAttribute('aria-controls')).toBe(BAND_ID);
  });

  it('tells its caller about every change rather than keeping its own', () => {
    const onOpenChange = vi.fn();
    render(
      <EditorFold open={false} onOpenChange={onOpenChange}>
        <EditorBandToggle label="Filter" pending={0} />
      </EditorFold>,
    );

    fireEvent.click(toggle());

    // Controlled: the handle did not open itself, it asked.
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
  });

  it('carries the count of what is not applied while the editor is away', () => {
    render(<Fold pending={3} />);

    // The whole point of the fold is that the editor can be away; a draft
    // nobody can see is one the user has no reason to remember.
    expect(screen.getByRole('button', { name: /3 not applied/ })).toBeDefined();
  });

  it('says nothing about pending when nothing is', () => {
    render(<Fold pending={0} />);

    expect(screen.queryByText(/not applied/)).toBeNull();
  });

  it('takes its wording from the catalogue in force', () => {
    render(
      <MessagesProvider
        messages={{ 'label.editor.pending': '{count} waiting' }}
      >
        <Fold pending={2} />
      </MessagesProvider>,
    );

    expect(screen.getByText('2 waiting')).toBeDefined();
  });

  /**
   * The handle says what it opens and nothing about how: the mode is the
   * editor's setting, shown as the checked item of the menu beside it, and
   * a second word on the button competed with the count of what is edited
   * and not applied (2026-09-23 visual review).
   */
  it('is called what it opens, with the pending count and no mode', () => {
    render(<Fold modes pending={2} />);

    // The count joined in, since two inline spans would run together as
    // "Filter2 not applied" in a name computed from them.
    expect(
      screen.getByRole('button', { name: 'Filter · 2 not applied' }),
    ).toBeDefined();
    expect(toggle().textContent).not.toContain('·');
  });

  it('is only its label when nothing is pending', () => {
    render(<Fold modes />);

    expect(toggle().getAttribute('aria-label')).toBeNull();
    expect(screen.getByRole('button', { name: 'Filter' })).toBeDefined();
  });

  it('offers no modes menu when the editor has no modes', () => {
    render(<Fold />);

    expect(screen.queryByRole('button', { name: 'Editor options' })).toBeNull();
  });

  it('hangs the modes off a chevron beside it when there are some', async () => {
    const user = userEvent.setup();
    render(<Fold modes />);

    await user.click(screen.getByRole('button', { name: 'Editor options' }));

    expect(await screen.findByText('Advanced')).toBeDefined();
  });

  it("takes the chevron's name from the catalogue in force", () => {
    render(
      <MessagesProvider
        messages={{ 'label.workbench.editor-modes': 'Ways to edit' }}
      >
        <Fold modes />
      </MessagesProvider>,
    );

    expect(screen.getByRole('button', { name: 'Ways to edit' })).toBeDefined();
  });
});
