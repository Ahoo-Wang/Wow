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

import type { Message } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import { EXECUTION_HISTORY } from "@/views/executionHistory.ts";
import { OVERVIEW, OVERVIEW_BOARD } from "@/views/overview.ts";

/**
 * One of the console's places, as the top bar lists it: a resource of the
 * engine's navigation (`useViewNavigation`) — its page, or one of its
 * system views — under the console's own word for it.
 */
export interface Place {
  readonly label: Message;
  /** The resource, by its definition's id. */
  readonly resource: string;
  /** One of its system views; its page when left out. */
  readonly view?: string;
}

/**
 * The console's four places, in the order the top bar lists them
 * (console-redesign.md §4, Q1): a task each — find what needs handling,
 * handle it, see what just happened, look at the trends. The overview is
 * its system board on the home page; the boards are its workbench. The
 * queues are views inside 「失败执行」, not places.
 */
export const Places: readonly Place[] = [
  { label: "Overview", resource: OVERVIEW, view: OVERVIEW_BOARD },
  { label: "Failed executions", resource: EXECUTION_FAILED },
  { label: "Event stream", resource: EXECUTION_HISTORY },
  { label: "Boards", resource: OVERVIEW },
];
