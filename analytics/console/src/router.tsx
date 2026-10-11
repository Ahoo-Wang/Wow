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

import { lazy, Suspense, type ComponentType } from 'react';
import { createBrowserRouter, Navigate } from 'react-router';
import App from './App.tsx';
import { AnalyticsHost } from './AnalyticsHost.tsx';
import { LOGIN_PATH } from './cosec.ts';
import HomePage from './pages/HomePage.tsx';
import LoginPage from './pages/LoginPage.tsx';
import { ADMIN_PATH, BOARDS_PATH, DATASETS_PATH, HOME_PATH } from './routes.ts';

// The workbenches and the registry load on first visit.
const DatasetPage = lazy(() => import('./pages/DatasetPage.tsx'));
const BoardsPage = lazy(() => import('./pages/BoardsPage.tsx'));
const AdminPage = lazy(() => import('./pages/AdminPage.tsx'));

function page(Page: ComponentType) {
  return (
    <Suspense fallback={<p className="muted">加载中…</p>}>
      <Page />
    </Suspense>
  );
}

/** The places; an address the console does not have lands on the home page. */
export const routes = [
  { path: LOGIN_PATH, element: <LoginPage /> },
  {
    element: (
      <AnalyticsHost>
        <App />
      </AnalyticsHost>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: `${DATASETS_PATH}/:id`, element: page(DatasetPage) },
      { path: BOARDS_PATH, element: page(BoardsPage) },
      { path: ADMIN_PATH, element: page(AdminPage) },
      { path: '*', element: <Navigate to={HOME_PATH} replace /> },
    ],
  },
];

export const createAnalyticsRouter = () => createBrowserRouter(routes);
