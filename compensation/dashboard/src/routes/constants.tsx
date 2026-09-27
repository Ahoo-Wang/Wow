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
import {
  BOARDS_PATH,
  EVENTS_PATH,
  EXECUTIONS_PATH,
  HOME_PATH,
} from "@/views/navigation.ts";

/** One of the console's places, as the top bar lists it. */
export interface NavItem {
  readonly label: Message;
  readonly path: string;
}

/**
 * The console's four places, in the order the top bar lists them
 * (console-redesign.md §4, Q1): a task each — find what needs handling,
 * handle it, see what just happened, look at the trends. The queues are
 * views inside 「失败执行」, not places, and the old queue addresses are
 * gone with the old shell (no compatibility, Q1).
 */
export const NavItems: readonly NavItem[] = [
  { label: "Overview", path: HOME_PATH },
  { label: "Failed executions", path: EXECUTIONS_PATH },
  { label: "Event stream", path: EVENTS_PATH },
  { label: "Boards", path: BOARDS_PATH },
];
