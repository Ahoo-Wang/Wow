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

import { text, type Text } from "@ahoo-wang/wow-view-engine";
import type { Locale } from "@/i18n.tsx";

/** One file's words, per language, under the names its definitions use. */
export type Words = Record<Locale, Record<string, string>>;

/**
 * The keys of one file's words, each `scope.name`: what its definitions
 * write where a label goes (`text(key)`), so a definition is the same in
 * every language and the engine says it in the one in force
 * (host-integration.md 3.1, `definitionText`).
 */
export function textKeys<W extends Record<string, string>>(
  scope: string,
  words: W,
): { readonly [K in keyof W]: Text } {
  return Object.fromEntries(
    Object.keys(words).map((name) => [name, text(`${scope}.${name}`)]),
  ) as { readonly [K in keyof W]: Text };
}

/** One file's words flattened under its scope, in one language. */
export function scoped(
  scope: string,
  words: Record<string, string>,
): Record<string, string> {
  return Object.fromEntries(
    Object.entries(words).map(([name, said]) => [`${scope}.${name}`, said]),
  );
}
