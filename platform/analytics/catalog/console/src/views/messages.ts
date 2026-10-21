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

import {
  zhCN,
  type MessageOverrides,
  type ViewMessages,
} from "@ahoo-wang/wow-view-engine/ui";
import { BOARD_WORDS } from "./boards.ts";
import { DATASET_ACTION_WORDS } from "./datasetActions.ts";
import { DATASET_WORDS } from "./datasets.ts";
import { SOURCE_ACTION_WORDS } from "./sourceActions.ts";
import { SOURCE_WORDS } from "./sources.ts";
import { scoped, type Locale, type Words } from "./words.ts";

/** Each file's words, under the scope its keys are written in. */
const WORDS: Readonly<Record<string, Words>> = {
  boards: BOARD_WORDS,
  sources: SOURCE_WORDS,
  sourceActions: SOURCE_ACTION_WORDS,
  datasets: DATASET_WORDS,
  datasetActions: DATASET_ACTION_WORDS,
};

/** The definitions' words in `locale`, by key. */
export function definitionWords(locale: Locale): Record<string, string> {
  return Object.assign(
    {},
    ...Object.entries(WORDS).map(([scope, words]) =>
      scoped(scope, words[locale]),
    ),
  );
}

/**
 * The engine's own wording in the console's language. Saved views live in
 * this browser (`browserViewStore`), whatever audience a view is saved for.
 */
const WORDING = {
  en: {},
  "zh-CN": zhCN,
} satisfies Record<Locale, MessageOverrides>;

const MESSAGES: Record<Locale, ViewMessages> = {
  en: { ...WORDING.en, ...definitionWords("en") },
  "zh-CN": { ...WORDING["zh-CN"], ...definitionWords("zh-CN") },
};

/** Everything the engine says in `locale`: its wording and the definitions' keys. */
export function engineMessages(locale: Locale): ViewMessages {
  return MESSAGES[locale];
}
