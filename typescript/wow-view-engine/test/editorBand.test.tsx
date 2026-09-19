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
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EditorBand } from '../src/ui/EditorBand.js';
import { MessagesProvider } from '../src/ui/MessagesProvider.js';

afterEach(cleanup);

/** The band as a workbench drives it: open state owned outside. */
function Band(props: { initial?: boolean; pending?: number }) {
  const [open, setOpen] = useState(props.initial ?? false);
  return (
    <EditorBand
      open={open}
      onOpenChange={setOpen}
      label="Filter"
      pending={props.pending ?? 0}
    >
      <p>The editor</p>
    </EditorBand>
  );
}

describe('EditorBand', () => {
  it('keeps its content out of the way until the row is opened', () => {
    render(<Band />);
    const row = screen.getByRole('button', { name: /Filter/ });

    expect(row.ariaExpanded).toBe('false');
    expect(screen.queryByText('The editor')).toBeNull();

    fireEvent.click(row);

    expect(row.ariaExpanded).toBe('true');
    expect(screen.getByText('The editor')).toBeDefined();
  });

  it('opens with the state its caller gave it', () => {
    render(<Band initial />);

    // A view that was never saved opens on its editor; the band holds no
    // opinion about which, and takes the answer from above.
    expect(screen.getByText('The editor')).toBeDefined();
  });

  it('reports the open state where the chevron can turn on it', () => {
    render(<Band initial />);

    // Base UI publishes the panel's state on the trigger; the chevron's
    // rotation is a variant of that attribute rather than a second copy of
    // the state kept in the band.
    const row = screen.getByRole('button', { name: /Filter/ });
    expect(row.hasAttribute('data-panel-open')).toBe(true);
  });

  it('tells its caller about every change rather than keeping its own', () => {
    const onOpenChange = vi.fn();
    render(
      <EditorBand
        open={false}
        onOpenChange={onOpenChange}
        label="Filter"
        pending={0}
      >
        <p>The editor</p>
      </EditorBand>,
    );

    fireEvent.click(screen.getByRole('button', { name: /Filter/ }));

    // Controlled: the band did not open itself, it asked.
    expect(onOpenChange).toHaveBeenCalledWith(true);
    expect(screen.queryByText('The editor')).toBeNull();
  });

  it('carries the count of what is not applied on the folded row', () => {
    render(<Band pending={3} />);

    // The whole point of the fold is that the editor can be away; a draft
    // nobody can see is one the user has no reason to remember.
    expect(screen.getByRole('button', { name: /3 not applied/ })).toBeDefined();
  });

  it('says nothing about pending when nothing is', () => {
    render(<Band pending={0} />);

    expect(screen.queryByText(/not applied/)).toBeNull();
  });

  it('takes its wording from the catalogue in force', () => {
    render(
      <MessagesProvider
        messages={{ 'label.editor.pending': '{count} waiting' }}
      >
        <Band pending={2} />
      </MessagesProvider>,
    );

    expect(screen.getByText('2 waiting')).toBeDefined();
  });
});
