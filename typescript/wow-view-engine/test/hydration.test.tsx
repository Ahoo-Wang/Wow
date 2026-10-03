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

/**
 * Hydration: the server's markup of each surface (`serverRender.test.tsx`),
 * hydrated in the browser by an engine of its own, as a page does. The
 * first client frame is the opening state the server sent — every store
 * answers the server's snapshot while hydrating — so React reports no
 * mismatch, and the view opens once hydrated.
 */

import { act, waitFor } from '@testing-library/react';
import { hydrateRoot } from 'react-dom/client';
import { renderToString } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SURFACES, serverEngine } from './fixtures/server.js';

const containers: HTMLElement[] = [];
afterEach(() => {
  for (const container of containers.splice(0)) container.remove();
});

describe('hydrating the server markup', () => {
  it.each(Object.entries(SURFACES))(
    '%s matches the first client frame, then opens its view',
    async (_, { page, slot }) => {
      const html = renderToString(page(serverEngine().engine));
      const container = document.createElement('div');
      container.innerHTML = html;
      document.body.appendChild(container);
      containers.push(container);
      const onRecoverableError = vi.fn();
      const consoleError = vi.spyOn(console, 'error');
      const { engine, source } = serverEngine();

      const root = await act(async () =>
        hydrateRoot(container, page(engine), { onRecoverableError }),
      );

      expect(onRecoverableError).not.toHaveBeenCalled();
      expect(consoleError).not.toHaveBeenCalled();
      await waitFor(() => expect(source.paged).toHaveBeenCalled());
      await waitFor(() =>
        expect(container.querySelector(`[data-slot="${slot}"]`)).toBeNull(),
      );
      act(() => root.unmount());
    },
  );
});
