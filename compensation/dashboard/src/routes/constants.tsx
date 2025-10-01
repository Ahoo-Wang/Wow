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
import { lazy } from "react";
import { FindCategory } from "../features/Failed/FindCategory.ts";

const FailedView = lazy(() => import("../features/Failed/FailedView.tsx"));

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
  category: FindCategory;
  component: React.ComponentType<{ category: FindCategory }>;
  icon?: React.ReactNode;
}

export const NavItems: NavItem[] = [
  {
    label: "To Retry",
    path: NavItemPaths.ToRetry,
    category: FindCategory.ToRetry,
    component: FailedView,
  },
  {
    label: "Executing",
    path: NavItemPaths.Executing,
    category: FindCategory.Executing,
    component: FailedView,
  },
  {
    label: "NextRetry",
    path: NavItemPaths.NextRetry,
    category: FindCategory.NextRetry,
    component: FailedView,
  },
  {
    label: "NonRetryable",
    path: NavItemPaths.NonRetryable,
    category: FindCategory.NonRetryable,
    component: FailedView,
  },
  {
    label: "Succeeded",
    path: NavItemPaths.Succeeded,
    category: FindCategory.Succeeded,
    component: FailedView,
  },
  {
    label: "Unrecoverable",
    path: NavItemPaths.Unrecoverable,
    category: FindCategory.Unrecoverable,
    component: FailedView,
  },
];