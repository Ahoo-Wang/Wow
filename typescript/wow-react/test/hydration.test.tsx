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
 * Characterization of hydration: the server's markup of every hook, hydrated
 * on the client. It pins the behaviour of today for the 2026-09 refactor:
 * the first client frame matches the server's, so React reports no
 * mismatch, and the queries run only once hydrated.
 */

import { describe, expect, it, vi } from 'vitest';
import { act } from '@testing-library/react';
import { renderToString } from 'react-dom/server';
import { hydrateRoot } from 'react-dom/client';
import { HOOKS, SsrProbe } from './support/ssrProbe';
import { fakeServer, json, sse } from './support/fakeServer';

describe('hydrating the server markup', () => {
  it('matches the first client frame, then runs every query', async () => {
    const server = fakeServer(request => {
      if (request.headers.get('Accept')?.includes('text/event-stream'))
        return sse();
      // A cursor answers a page; every other kind takes any JSON.
      const { kind } = request.body as { kind: string };
      return json(kind === 'cursor' ? { list: [], nextCursor: null } : null);
    });
    const html = renderToString(<SsrProbe fetcher={server.fetcher} />);
    const container = document.createElement('div');
    container.innerHTML = html;
    document.body.appendChild(container);
    const onRecoverableError = vi.fn();
    expect(server.requests).toHaveLength(0);
    const root = await act(async () =>
      hydrateRoot(container, <SsrProbe fetcher={server.fetcher} />, {
        onRecoverableError,
      }),
    );
    // Both sides render the first frame `loading` (F12, since B3); they
    // agree, so React reports no mismatch.
    expect(onRecoverableError).not.toHaveBeenCalled();
    await act(() => new Promise(resolve => setTimeout(resolve, 20)));
    expect(server.requests).toHaveLength(HOOKS.length);
    for (const name of HOOKS) {
      expect(container.innerHTML).toContain(`${name}:success:still`);
    }
    act(() => root.unmount());
    container.remove();
  });
});
