/**
 * @vitest-environment node
 */

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
 * Characterization of server-side rendering: every hook rendered with
 * `renderToString` in Node, with no DOM. It pins the behaviour of today for
 * the refactor of `docs/design/refactor-2026-09.md`: no request goes out, no
 * browser global is touched, and each hook renders its first frame.
 */

import { describe, expect, it } from 'vitest';
import { renderToString } from 'react-dom/server';
import { HOOKS, SsrProbe } from './support/ssrProbe';
import { fakeServer, json } from './support/fakeServer';

describe('renderToString', () => {
  it('runs with no DOM', () => {
    expect(typeof window).toBe('undefined');
    expect(typeof document).toBe('undefined');
  });

  it('sends no request, and every hook with a query renders loading', async () => {
    const server = fakeServer(() => json(null));
    const html = renderToString(<SsrProbe fetcher={server.fetcher} />);
    await new Promise(resolve => setTimeout(resolve, 10));
    expect(server.requests).toHaveLength(0);
    for (const name of HOOKS) {
      // B3 changed this (F12): was `${name}:idle:still`. The first frame is
      // what the next ones show, on the server as on the client.
      expect(html).toContain(`${name}:loading:loading`);
    }
  });

  it('renders idle with autoExecute off', () => {
    const server = fakeServer(() => json(null));
    const html = renderToString(
      <SsrProbe fetcher={server.fetcher} autoExecute={false} />,
    );
    expect(server.requests).toHaveLength(0);
    for (const name of HOOKS) {
      expect(html).toContain(`${name}:idle:still`);
    }
  });
});
