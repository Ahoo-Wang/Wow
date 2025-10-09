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

import { Suspense } from "react";
import { createBrowserRouter, Navigate } from "react-router";
import App from "../features/App/App.tsx";
import { NavItems, NavItemPaths } from "./constants.tsx";
import { Skeleton } from "antd";

export const AppRouter = createBrowserRouter([
  {
    element: <App navItems={NavItems} />,
    children: [
      {
        index: true,
        element: <Navigate to={NavItemPaths.ToRetry} replace />,
      },
      ...NavItems.map((routeItem) => ({
        path: routeItem.path,
        element: (
          <Suspense
            fallback={
              <Skeleton active/>
            }
          >
            <routeItem.component key={routeItem.category} category={routeItem.category} />
          </Suspense>
        ),
      })),
      {
        path: "*",
        element: <Navigate to={NavItemPaths.ToRetry} replace />,
      },
    ],
  },
]);
