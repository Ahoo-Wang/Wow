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
import { createMemoryRouter, RouterProvider } from 'react-router';
import App from './App.tsx';
import type { CurrentUser } from './cosec.ts';

function renderApp(user: CurrentUser | null) {
  const router = createMemoryRouter(
    [
      { path: '/login', element: <p>sign in</p> },
      {
        path: '/',
        element: <App user={user} />,
        children: [{ index: true, element: <p>home</p> }],
      },
    ],
    { initialEntries: ['/'] },
  );
  render(<RouterProvider router={router} />);
}

describe('App', () => {
  it('sends nobody to the sign-in page', () => {
    renderApp(null);
    expect(screen.getByText('sign in')).toBeInTheDocument();
  });

  it('shows the places, and the registry to its admins only', () => {
    renderApp({
      id: 'ann',
      tenantId: 't1',
      roles: ['analytics-admin'],
      admin: true,
    });
    expect(screen.getByText('home')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '数据集管理' }),
    ).toBeInTheDocument();
    expect(screen.getByText('ann · t1')).toBeInTheDocument();
  });

  it('hides the registry from an analyst', () => {
    renderApp({ id: 'bob', roles: [], admin: false });
    expect(screen.queryByRole('link', { name: '数据集管理' })).toBeNull();
  });
});
