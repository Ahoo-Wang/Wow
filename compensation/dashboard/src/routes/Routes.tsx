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

import * as React from "react";
import { createBrowserRouter, Navigate } from "react-router";
import App from "../features/App/App.tsx";
import FailedView from "../components/Failed/FailedView.tsx";

export const NavItemPaths = {
  ToRetry: "/to-retry",
  Executing: "/executing",
  NextRetry: "/next-retry",
  NonRetryable: "/non-retryable",
  Succeeded: "/succeeded",
  Unrecoverable: "/unrecoverable",
};

export interface NavItem {
  label: string;
  path: string;
  component: React.ReactNode;
  icon?: React.ReactNode;
}

export const NavItems: NavItem[] = [
  {
    label: "To Retry",
    path: NavItemPaths.ToRetry,
    component: <FailedView category="ToRetry"></FailedView>,
  },
  {
    label: "Executing",
    path: NavItemPaths.Executing,
    component: <FailedView category="Executing"></FailedView>,
  },
  {
    label: "NextRetry",
    path: NavItemPaths.NextRetry,
    component: <FailedView category="NextRetry"></FailedView>,
  },
  {
    label: "NonRetryable",
    path: NavItemPaths.NonRetryable,
    component: <FailedView category="NonRetryable"></FailedView>,
  },
  {
    label: "Succeeded",
    path: NavItemPaths.Succeeded,
    component: <FailedView category="Succeeded"></FailedView>,
  },
  {
    label: "Unrecoverable",
    path: NavItemPaths.Unrecoverable,
    component: <FailedView category="Unrecoverable"></FailedView>,
  },
];

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
        element: routeItem.component,
      })),
      {
        path: "*",
        element: <Navigate to={NavItemPaths.ToRetry} replace />,
      },
    ],
  },
]);
