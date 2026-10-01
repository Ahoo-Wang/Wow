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

import { zhCN, type ViewMessages } from "@ahoo-wang/wow-view-engine/ui";
import type { Locale } from "@/i18n.tsx";
import { definitionWords } from "./text.ts";

/**
 * The engine's words in the console's language. Saved views and boards live
 * in the compensation service and are shared: nobody signs in to the
 * console, so everyone who opens it sees the same ones. The save dialog says
 * so, so nobody takes a view for their own.
 */
const WORDING: Record<Locale, ViewMessages> = {
  en: {
    "label.scope.shared.description":
      "Saved in the compensation service. Everyone who opens this console sees it.",
  },
  "zh-CN": {
    ...zhCN,
    "label.scope.shared.description":
      "保存于补偿服务，打开这个控制台的人都看得到。",
  },
};

/**
 * Everything the engine says in `locale`: its own wording, and the words of
 * the definitions' keys (`text(key)`), which the Provider says at render
 * time — so a change of language redraws the one engine rather than
 * building another. One object per language, so the words stay put.
 */
const MESSAGES: Record<Locale, ViewMessages> = {
  en: { ...WORDING.en, ...definitionWords("en") },
  "zh-CN": { ...WORDING["zh-CN"], ...definitionWords("zh-CN") },
};

export function engineMessages(locale: Locale): ViewMessages {
  return MESSAGES[locale];
}
