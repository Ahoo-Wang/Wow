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

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ViewInstanceSummary } from '../src/index.js';
import type { ViewListState } from '../src/react/index.js';
import { ViewList } from '../src/ui/index.js';
import { Truncated } from '../src/ui/kit/Truncated.js';

afterEach(cleanup);

/**
 * A text cut with an ellipsis is read whole on pointing (second review
 * R2-75: 9 of 13 view names cut in the 1280 sidebar had no title and no
 * tooltip). jsdom lays nothing out, so each case says how wide the text
 * runs (`scrollWidth`) against its box (`clientWidth`).
 */
function sized(element: HTMLElement, scrollWidth: number, clientWidth = 100) {
  vi.spyOn(element, 'scrollWidth', 'get').mockReturnValue(scrollWidth);
  vi.spyOn(element, 'clientWidth', 'get').mockReturnValue(clientWidth);
}

describe('Truncated (R2-75)', () => {
  const LONG = '品类 → 子类的实付构成（近 12 个月，对比上一年同期）';
  const tip = () => document.querySelector('[data-slot="tooltip-content"]');

  /**
   * Past the tooltip's opening delay (Base UI's 600ms): the wait is the
   * behaviour under test where a fitting text must open nothing.
   */
  const pastDelay = () =>
    act(async () => {
      await new Promise(resolve => setTimeout(resolve, 700));
    });

  it('opens the whole text in a tooltip where the text is cut', async () => {
    render(<Truncated data-testid="t" text={LONG} />);
    const text = screen.getByTestId('t');
    sized(text, 400);
    await userEvent.hover(text);
    await waitFor(() => expect(tip()?.textContent).toBe(LONG));
    // A tooltip, not the native title, which opens for a mouse alone (D16-6).
    expect(text.hasAttribute('title')).toBe(false);
  });

  it('opens nothing where the text fits, so nothing repeats the screen', async () => {
    render(<Truncated data-testid="t" text="订单" />);
    const text = screen.getByTestId('t');
    sized(text, 40);
    await userEvent.hover(text);
    await pastDelay();
    expect(tip()).toBeNull();
  });

  it('is the element it is drawn as, nothing wrapped around it', () => {
    render(<Truncated as="h2" id="heading" data-slot="x" text={LONG} />);
    const heading = screen.getByRole('heading', { level: 2 });
    expect(heading.id).toBe('heading');
    expect(heading.dataset.slot).toBe('x');
    expect(heading.textContent).toBe(LONG);
  });

  it('gives a view list row’s cut name its tooltip, and its name stays whole for a reader', async () => {
    const view: ViewInstanceSummary = {
      id: 'long',
      definitionId: 'orders',
      title: LONG,
      scope: 'personal',
      kind: 'analysis',
      revision: '1',
    };
    const list: ViewListState = {
      items: [view],
      all: [view],
      preferences: null,
      permissions: {
        createPersonal: true,
        createShared: true,
        reorder: true,
        setDefault: true,
        instance: () => ({ save: true, rename: true, delete: true }),
      },
      defaultInstanceId: null,
      loading: false,
      error: null,
      preferencesError: null,
      preferencesSettled: true,
      reload: () => {},
    };
    render(<ViewList list={list} currentId={null} onOpen={() => {}} />);
    const name = screen.getByText(LONG);
    expect(screen.getByRole('button', { name: new RegExp(LONG) })).toBe(
      name.closest('button'),
    );
    sized(name, 400);
    await userEvent.hover(name);
    await waitFor(() => expect(tip()?.textContent).toBe(LONG));
  });
});
