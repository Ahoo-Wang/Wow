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

import { render, screen } from '@testing-library/react';
import { MemoryViewStore } from '@ahoo-wang/wow-view-engine';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { AnalyticsHost } from '../AnalyticsHost.tsx';
import { createAnalyticsEngine } from '../engine.ts';
import HomePage from './HomePage.tsx';

describe('HomePage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reads the directory and offers the boards', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>(
      async () =>
        new Response('[]', { headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetch);
    const engine = createAnalyticsEngine(new MemoryViewStore());
    const router = createMemoryRouter([
      {
        path: '/',
        element: (
          <AnalyticsHost engine={engine}>
            <HomePage />
          </AnalyticsHost>
        ),
      },
    ]);
    render(<RouterProvider router={router} />);
    expect(await screen.findByText('还没有发布的数据集。')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '新建或打开看板' }),
    ).toHaveAttribute('href', '/boards');
    expect(String(fetch.mock.calls[0]?.[0])).toContain('/dataset');
  });
});
