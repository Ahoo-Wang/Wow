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

import { Navigate, type RouteObject } from "react-router";
import App from "@/app/App.tsx";
import BoardsPage from "@/pages/BoardsPage.tsx";
import CallerPage from "@/pages/CallerPage.tsx";
import DatasetsPage from "@/pages/DatasetsPage.tsx";
import DraftPage from "@/pages/DraftPage.tsx";
import RowsPage from "@/pages/RowsPage.tsx";
import SourcesPage from "@/pages/SourcesPage.tsx";
import {
  BOARDS_PATH,
  CALLER_PATH,
  DATASETS_PATH,
  DRAFT_PATH,
  ROWS_PATH,
  SOURCES_PATH,
} from "@/views/bindings.ts";

/**
 * The console's pages. The router is a hash router (`main.tsx`): the catalog
 * server serves the console's files as they are, with no fallback to
 * `index.html` for a page's path, so a page is `/#/sources?view=…`; the home
 * page is the sources.
 */
export const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <App />,
    children: [
      { index: true, element: <Navigate to={SOURCES_PATH} replace /> },
      { path: SOURCES_PATH.slice(1), element: <SourcesPage /> },
      { path: DATASETS_PATH.slice(1), element: <DatasetsPage /> },
      { path: ROWS_PATH.slice(1), element: <RowsPage /> },
      { path: BOARDS_PATH.slice(1), element: <BoardsPage /> },
      { path: DRAFT_PATH.slice(1), element: <DraftPage /> },
      { path: CALLER_PATH.slice(1), element: <CallerPage /> },
      { path: "*", element: <Navigate to={SOURCES_PATH} replace /> },
    ],
  },
];
