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

import { Suspense, type ComponentType } from "react";
import { createBrowserRouter, Navigate } from "react-router";
import App from "../features/App/App.tsx";
import { NavItemPaths, PrimaryNavItems, QueueRoutes } from "./constants.tsx";
import {
  LazyBoardsPage,
  LazyEventsPage,
  LazyExecutionsPage,
  LazyOverviewPage,
} from "./lazyPages.ts";
import { QueueRedirect } from "./QueueRedirect.tsx";
import { Skeleton } from "@/components/ui/skeleton";

const routeFallback = (
  <div className="h-full space-y-4 p-5">
    <Skeleton className="h-12 w-full" />
    <Skeleton className="h-[70vh] w-full" />
  </div>
);

/** A page loaded on first visit, with the skeleton while its chunk comes. */
function page(Page: ComponentType) {
  return (
    <Suspense fallback={routeFallback}>
      <Page />
    </Suspense>
  );
}

export const AppRouter = createBrowserRouter([
  {
    element: <App navItems={PrimaryNavItems} />,
    children: [
      { index: true, element: page(LazyOverviewPage) },
      { path: NavItemPaths.Boards, element: page(LazyBoardsPage) },
      { path: NavItemPaths.Executions, element: page(LazyExecutionsPage) },
      { path: NavItemPaths.Events, element: page(LazyEventsPage) },
      ...QueueRoutes.map(({ path, view }) => ({
        path,
        element: <QueueRedirect view={view} />,
      })),
      {
        path: "/dashboard",
        element: <Navigate to={NavItemPaths.Dashboard} replace />,
      },
      {
        path: NavItemPaths.Analytics,
        element: <Navigate to={NavItemPaths.Dashboard} replace />,
      },
      {
        path: "*",
        element: <Navigate to={NavItemPaths.Dashboard} replace />,
      },
    ],
  },
]);
