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

import type { ComponentType } from "react";
import { FindCategory } from "../features/Failed/FindCategory.ts";
import LazyFailedView from "./LazyFailedView.tsx";

export const NavItemPaths = {
  Dashboard: "/dashboard",
  Analytics: "/analytics",
  ToRetry: "/to-retry",
  Executing: "/executing",
  NextRetry: "/next-retry",
  NonRetryable: "/non-retryable",
  Succeeded: "/succeeded",
  Unrecoverable: "/unrecoverable",
} as const;

export interface NavItem {
  readonly label: string;
  readonly path: string;
}

export interface QueueNavItem extends NavItem {
  readonly category: FindCategory;
  readonly component: ComponentType<{ category: FindCategory }>;
}

export const NavItems: readonly QueueNavItem[] = [
  {
    label: "To Retry",
    path: NavItemPaths.ToRetry,
    category: FindCategory.ToRetry,
    component: LazyFailedView,
  },
  {
    label: "Executing",
    path: NavItemPaths.Executing,
    category: FindCategory.Executing,
    component: LazyFailedView,
  },
  {
    label: "Next Retry",
    path: NavItemPaths.NextRetry,
    category: FindCategory.NextRetry,
    component: LazyFailedView,
  },
  {
    label: "Non Retryable",
    path: NavItemPaths.NonRetryable,
    category: FindCategory.NonRetryable,
    component: LazyFailedView,
  },
  {
    label: "Succeeded",
    path: NavItemPaths.Succeeded,
    category: FindCategory.Succeeded,
    component: LazyFailedView,
  },
  {
    label: "Unrecoverable",
    path: NavItemPaths.Unrecoverable,
    category: FindCategory.Unrecoverable,
    component: LazyFailedView,
  },
];

export const DashboardNavItem: NavItem = {
  label: "Dashboard",
  path: NavItemPaths.Dashboard,
};

export const PrimaryNavItems: readonly NavItem[] = [
  DashboardNavItem,
  ...NavItems,
];
