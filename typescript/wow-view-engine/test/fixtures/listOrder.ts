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

import { act, fireEvent, waitFor } from '@testing-library/react';
import { vi } from 'vitest';

/** jsdom geometry for adapter contracts; real sensor geometry is verified in browsers. */
export async function keyboardOrder(
  handle: HTMLElement,
  key: string,
  cancel = false,
  duringDrag?: () => void | Promise<void>,
) {
  if ((handle as HTMLButtonElement).disabled) return;
  globalThis.IntersectionObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof IntersectionObserver;
  window.matchMedia ??= () =>
    ({
      matches: false,
      addEventListener() {},
      removeEventListener() {},
    }) as unknown as MediaQueryList;
  document.getAnimations ??= () => [];
  Element.prototype.getAnimations ??= () => [];
  Element.prototype.animate ??= () =>
    ({
      finished: Promise.resolve(),
      cancel() {},
      finish() {},
      addEventListener() {},
      removeEventListener() {},
    }) as unknown as Animation;
  const viewport = [
    vi
      .spyOn(document.documentElement, 'clientWidth', 'get')
      .mockReturnValue(1200),
    vi
      .spyOn(document.documentElement, 'clientHeight', 'get')
      .mockReturnValue(1000),
  ];
  const list = handle.closest('ol')!;
  const geometry = vi
    .spyOn(Element.prototype, 'getBoundingClientRect')
    .mockImplementation(function (this: Element) {
      const row = this.closest('li');
      const index = row ? [...list.children].indexOf(row) : -1;
      return DOMRect.fromRect({
        x: 20,
        y: Math.max(0, index) * 100 + 20,
        width: 200,
        height: row ? 80 : 900,
      });
    });
  try {
    handle.focus();
    fireEvent.keyDown(handle, { key: ' ', code: 'Space' });
    await waitFor(() => {
      if (!list.querySelector('[data-dragging]'))
        throw new Error('drag not started');
    });
    await act(async () => {
      await new Promise(resolve => setTimeout(resolve, 30));
    });
    await act(async () => {
      fireEvent.keyDown(document, { key, code: key });
      await new Promise(resolve => setTimeout(resolve, 30));
    });
    if (duringDrag) await act(duringDrag);
    await act(async () => {
      fireEvent.keyDown(document, {
        key: cancel ? 'Escape' : ' ',
        code: cancel ? 'Escape' : 'Space',
      });
      await new Promise(resolve => setTimeout(resolve, 30));
    });
    await waitFor(() => {
      if (list.querySelector('[data-dragging]'))
        throw new Error('drag not stopped');
    });
    await waitFor(() => {
      if (
        handle.isConnected &&
        !(handle as HTMLButtonElement).disabled &&
        document.activeElement !== handle
      )
        throw new Error('focus not restored');
    });
  } finally {
    geometry.mockRestore();
    viewport.forEach(mock => mock.mockRestore());
  }
}
