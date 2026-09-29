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
import { afterEach, describe, expect, it } from 'vitest';
import { Select, SelectItem } from '../src/ui/components/select.js';
import { draggableStyle } from '../src/ui/kit/cspNonce.js';
import { SelectContent } from '../src/ui/kit/popups.js';

/*
 * Every `<style>` the engine's libraries add carries the page's nonce (D61,
 * D74). The browser holds the whole engine to it under the README's policy
 * (Storybook's `StrictCsp.test.stories.tsx`); here are the two hand-offs
 * that story found missing — Base UI's select and the grid's drag library —
 * one by one. The drag-and-drop library's is in `renameInput.test.tsx`.
 */

const DRAGGABLE = '#react-draggable-style-el';

/** Publishes `nonce` the way Vite does, until the returned call. */
function publish(nonce: string): () => void {
  const meta = document.createElement('meta');
  meta.setAttribute('property', 'csp-nonce');
  meta.setAttribute('nonce', nonce);
  document.head.append(meta);
  return () => meta.remove();
}

afterEach(() => {
  cleanup();
  document.head.querySelector(DRAGGABLE)?.remove();
});

describe('the page’s CSP nonce on what the libraries add', () => {
  it('adds the grid drag library’s style under the nonce, once, before it can', () => {
    // No nonce on the page: nothing is added, and the library adds its own.
    draggableStyle();
    expect(document.head.querySelector(DRAGGABLE)).toBeNull();

    const withdraw = publish('r4nd0m');
    try {
      draggableStyle();
      draggableStyle();
      const styles =
        document.head.querySelectorAll<HTMLStyleElement>(DRAGGABLE);
      expect(styles).toHaveLength(1);
      expect(styles[0]!.nonce).toBe('r4nd0m');
      // The library's own rules, which match only while it marks `<body>`.
      expect(styles[0]!.textContent).toContain(
        '.react-draggable-transparent-selection *::selection {all: inherit;}',
      );
    } finally {
      withdraw();
    }
  });

  it('puts the nonce on the style a select’s list adds', () => {
    const withdraw = publish('s3l3ct');
    try {
      render(
        <Select open>
          <SelectContent>
            <SelectItem value="20">20</SelectItem>
          </SelectContent>
        </Select>,
      );
      const styles = [...document.querySelectorAll('style')].filter(style =>
        style.textContent?.includes('base-ui-disable-scrollbar'),
      );
      expect(styles.length).toBeGreaterThan(0);
      for (const style of styles) expect(style.nonce).toBe('s3l3ct');
    } finally {
      withdraw();
    }
  });
});
