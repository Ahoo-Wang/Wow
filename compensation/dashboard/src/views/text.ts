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

import { withText, type TextResolver } from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";
import { ACTIVITY_ANALYSES_WORDS } from "./activityAnalyses.ts";
import { EXECUTION_ACTION_WORDS } from "./executionActions.ts";
import { EXECUTION_FAILED_WORDS } from "./executionFailed.ts";
import { EXECUTION_HISTORY_WORDS } from "./executionHistory.ts";
import { FAILURE_ANALYSES_WORDS } from "./failureAnalyses.ts";
import { OVERVIEW_WORDS } from "./overview.ts";
import { scoped, type Words } from "./textKeys.ts";

/** Each file's words, under the scope its keys are written in. */
const WORDS: Readonly<Record<string, Words>> = {
  executionFailed: EXECUTION_FAILED_WORDS,
  executionActions: EXECUTION_ACTION_WORDS,
  failureAnalyses: FAILURE_ANALYSES_WORDS,
  executionHistory: EXECUTION_HISTORY_WORDS,
  activityAnalyses: ACTIVITY_ANALYSES_WORDS,
  overview: OVERVIEW_WORDS,
};

function catalogue(locale: Locale): Readonly<Record<string, string>> {
  return Object.assign(
    {},
    ...Object.entries(WORDS).map(([scope, words]) =>
      scoped(scope, words[locale]),
    ),
  );
}

const CATALOGUES: Readonly<Record<Locale, Readonly<Record<string, string>>>> = {
  en: catalogue("en"),
  "zh-CN": catalogue("zh-CN"),
};

/**
 * The definitions' words in `locale`, by key: what the Provider says their
 * keys in (`engineMessages`), one object per language.
 */
export function definitionWords(
  locale: Locale,
): Readonly<Record<string, string>> {
  return CATALOGUES[locale];
}

/**
 * How the definitions' keys are said in `locale` (`ViewEngineOptions.text`):
 * the definitions are the same in every language, and the engine reads
 * them in the one in force.
 */
export function definitionText(locale: Locale): TextResolver {
  const words = CATALOGUES[locale];
  return (key) => words[key];
}

/** A definition, a view or a board in `locale`'s words, as the engine reads it. */
export function inLocale<T>(value: T, locale: Locale): T {
  return withText(value, definitionText(locale));
}
