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

import { useCallback } from "react";
import { useSearchParams } from "react-router";
import type { ViewSource, ViewStore } from "@ahoo-wang/wow-view-engine";
import { useViewEngine } from "@ahoo-wang/wow-view-engine/react";
import {
  DataWorkbench,
  zhCN,
  type DataWorkbenchProps,
} from "@ahoo-wang/wow-view-engine/ui";
import { useI18n, type Locale } from "@/i18n.tsx";
import { EXECUTION_FAILED } from "@/views/executionFailed.ts";
import { executionEngineOptions, localViewStore } from "@/views/engine.ts";

/** The route parameter naming the open view. */
export const VIEW_PARAM = "view";

type Messages = NonNullable<DataWorkbenchProps["messages"]>;

/**
 * Saved views live in this browser until the Wow storage backend (stage 6);
 * the view list and the save dialog say so, so nobody expects a colleague to
 * see them.
 */
const MESSAGES: Record<Locale, Messages> = {
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

export interface ExecutionsPreviewProps {
  /** For tests: the store the engines read and write. */
  store?: ViewStore;
  /** For tests: where the rows come from instead of the service. */
  source?: ViewSource;
}

interface LocalizedWorkbenchProps extends ExecutionsPreviewProps {
  locale: Locale;
  store: ViewStore;
}

/**
 * One engine in one language, for as long as the language holds: the
 * definition's labels are in one language (G12), so the page keys this by
 * language and a change of language builds a new engine over the same store,
 * disposing the old one on unmount.
 */
function LocalizedWorkbench({
  locale,
  store,
  source,
}: LocalizedWorkbenchProps) {
  const engine = useViewEngine(
    executionEngineOptions({ locale, store, source }),
  );
  const [searchParams, setSearchParams] = useSearchParams();
  const instanceId = searchParams.get(VIEW_PARAM);

  const onInstanceChange = useCallback(
    (id: string | null) => {
      if (id === instanceId) return;
      setSearchParams((current) => {
        const next = new URLSearchParams(current);
        if (id === null) next.delete(VIEW_PARAM);
        else next.set(VIEW_PARAM, id);
        return next;
      });
    },
    [instanceId, setSearchParams],
  );

  return (
    <DataWorkbench
      engine={engine}
      definitionId={EXECUTION_FAILED}
      instanceId={instanceId}
      onInstanceChange={onInstanceChange}
      // The console's shell already has the page's `main`.
      landmark="region"
      locale={locale}
      messages={MESSAGES[locale]}
    />
  );
}

/**
 * 「失败执行（预览）」: the failed executions in the view engine's workbench,
 * beside the old queues until they are replaced (rebuild proposal, batch 1).
 * The open view is the `view` search parameter, so a view is a link.
 */
export default function ExecutionsPreview({
  store,
  source,
}: ExecutionsPreviewProps) {
  const { locale } = useI18n();
  return (
    <div className="executions-preview">
      <LocalizedWorkbench
        key={locale}
        locale={locale}
        store={store ?? localViewStore()}
        source={source}
      />
    </div>
  );
}
