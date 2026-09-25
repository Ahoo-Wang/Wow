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

import { executionsView } from "@/features/Executions/linkScope.ts";
import type { Message } from "@/i18n.tsx";
import {
  BOARDS_PATH,
  EVENTS_PATH,
  EXECUTIONS_PATH,
  HOME_PATH,
} from "@/views/navigation.ts";

export const NavItemPaths = {
  Dashboard: HOME_PATH,
  Analytics: "/analytics",
  Boards: BOARDS_PATH,
  Executions: EXECUTIONS_PATH,
  Events: EVENTS_PATH,
} as const;

export interface NavItem {
  readonly label: Message;
  readonly path: string;
}

/**
 * The old console's seven queues, each now a system view of the failed
 * executions (rebuild proposal, batch 5; Q3). The addresses stay, as
 * redirects to their view: alerts, tickets and bookmarks still carry them,
 * with their `id`, `cluster`, `start` and `end`.
 */
export const QueueRoutes: readonly { path: string; view: string }[] = [
  { path: "/active", view: executionsView("active") },
  { path: "/to-retry", view: executionsView("to-retry") },
  { path: "/executing", view: executionsView("executing") },
  { path: "/next-retry", view: executionsView("next-retry") },
  { path: "/non-retryable", view: executionsView("non-retryable") },
  { path: "/succeeded", view: executionsView("succeeded") },
  { path: "/unrecoverable", view: executionsView("unrecoverable") },
];

export const DashboardNavItem: NavItem = {
  label: "Overview",
  path: NavItemPaths.Dashboard,
};

/**
 * 「失败执行」: the view engine's workbench, whose own view list holds the
 * queues and the reader's own views (Q3).
 */
export const ExecutionsNavItem: NavItem = {
  label: "Failed executions",
  path: NavItemPaths.Executions,
};

/**
 * The pages reached from another rather than from the sidebar, each under
 * the item it belongs to: the dashboard workbench under 「概览」, whose board
 * it opens, and the event streams under 「失败执行」.
 */
export const SecondaryPages: readonly (NavItem & { parent: string })[] = [
  {
    label: "Dashboards",
    path: NavItemPaths.Boards,
    parent: NavItemPaths.Dashboard,
  },
  {
    label: "Execution events",
    path: NavItemPaths.Events,
    parent: NavItemPaths.Executions,
  },
];

export const PrimaryNavItems: readonly NavItem[] = [
  DashboardNavItem,
  ExecutionsNavItem,
];
