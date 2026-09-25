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

/**
 * The engine's words in the console's language. Saved views and boards live
 * in this browser until the Wow storage backend (stage 6); the view lists
 * and the save dialogs say so, so nobody expects a colleague to see them.
 */
const MESSAGES: Record<Locale, ViewMessages> = {
  en: {
    "label.scope.group.personal": "My views (this browser)",
    "label.scope.personal.description":
      "Saved in this browser on this computer. Only you see it.",
  },
  "zh-CN": {
    ...zhCN,
    "label.scope.group.personal": "我的视图（本机）",
    "label.scope.personal.description":
      "存在这台电脑的这个浏览器里，只有你看得到。",
  },
};

export function engineMessages(locale: Locale): ViewMessages {
  return MESSAGES[locale];
}
