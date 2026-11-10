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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { IconButton } from '../src/ui/kit/IconButton.js';

/**
 * `held`: a button out of reach while a write it started runs, and still
 * where the keyboard is (#5820). It says so only while held, so a button
 * that is not held is the caller's, its own `disabled` included.
 */
afterEach(cleanup);

function button(props: { held?: boolean; disabled?: boolean }) {
  const onClick = vi.fn();
  render(
    <IconButton label="Publish" onClick={onClick} {...props}>
      <svg />
    </IconButton>,
  );
  return { element: screen.getByRole('button', { name: 'Publish' }), onClick };
}

describe('IconButton held', () => {
  it('holds the keyboard while held: aria-disabled, focusable, the press swallowed', () => {
    const { element, onClick } = button({ held: true });
    expect(element.getAttribute('aria-disabled')).toBe('true');
    expect(element.hasAttribute('disabled')).toBe(false);
    element.focus();
    expect(document.activeElement).toBe(element);
    fireEvent.click(element);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("leaves a caller's own disabled alone when it is not held", () => {
    const { element, onClick } = button({ held: false, disabled: true });
    expect((element as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(element);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is an ordinary button when neither is said', () => {
    const { element, onClick } = button({});
    expect((element as HTMLButtonElement).disabled).toBe(false);
    expect(element.getAttribute('aria-disabled')).not.toBe('true');
    fireEvent.click(element);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
