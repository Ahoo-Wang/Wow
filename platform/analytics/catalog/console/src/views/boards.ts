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

import type { DashboardDefinition } from "@ahoo-wang/wow-view-engine";
import { textKeys, type Words } from "./words.ts";

/** The definition the boards over the datasets are views of. */
export const BOARDS = "analytics-catalog-boards";

/** Every word the definition shows, per language. */
export const BOARD_WORDS = {
  // The engine's word for a board, in its own messages too.
  en: { title: "Dashboards" },
  "zh-CN": { title: "仪表盘" },
} satisfies Words;

const t = textKeys("boards", BOARD_WORDS.en);

/**
 * The boards over the datasets (D238 ⑨): a board is a view of this
 * definition whose panels name views of any dataset served, of whichever
 * service, side by side. It owns no data and ships no board; the data owners
 * build them, kept where their other views are (this browser's store).
 */
export const boards: DashboardDefinition = {
  id: BOARDS,
  title: t.title,
  kind: "dashboard",
};
