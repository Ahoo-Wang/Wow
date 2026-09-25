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
  MemoryViewStore,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import { describe, expect, it } from "vitest";
import { createExecutionEngine } from "./engine.ts";
import { EXECUTION_FAILED } from "./executionFailed.ts";
import { EXECUTION_HISTORY } from "./executionHistory.ts";
import {
  ATTENTION_PANEL,
  OVERVIEW,
  OVERVIEW_WINDOW,
  overviewDefinition,
} from "./overview.ts";

const LOCALES = ["en", "zh-CN"] as const;

const unusedSource: ViewSource = {
  paged: () => Promise.reject(new Error("not queried")),
  cursor: () => Promise.reject(new Error("not queried")),
  aggregate: () => Promise.reject(new Error("not queried")),
};

function board(locale: (typeof LOCALES)[number]): DashboardViewConfig {
  const config = overviewDefinition(locale).views?.[0]?.config;
  if (config?.kind !== "dashboard") throw new Error("No overview board");
  return config;
}

function panels(locale: (typeof LOCALES)[number]): DashboardViewPanel[] {
  return board(locale).panels.filter(
    (panel): panel is DashboardViewPanel => panel.kind === "view",
  );
}

/** Which definition a panel reads, whether it owns its view or not. */
function definitionOf(panel: DashboardViewPanel): string {
  return panel.owned?.definitionId ?? EXECUTION_FAILED;
}

describe("overviewDefinition", () => {
  it.each(LOCALES)("is admitted by the engine in %s", (locale) => {
    const engine = createExecutionEngine({
      locale,
      store: new MemoryViewStore(),
      source: unusedSource,
      historySource: unusedSource,
    });
    try {
      // The due-for-retry panel names a system view of another definition,
      // which the board's own admission cannot read; opening it does.
      expect(
        engine
          .definitionIssues(OVERVIEW)
          .filter(({ severity }) => severity === "error"),
      ).toEqual([]);
      expect(engine.definitionIssues(EXECUTION_HISTORY)).toEqual([]);
    } finally {
      engine.dispose();
    }
  });

  it("lays out the same panels in every language", () => {
    const [en, zh] = LOCALES.map(board);
    expect(zh.panels.map(({ id, layout }) => [id, layout])).toEqual(
      en.panels.map(({ id, layout }) => [id, layout]),
    );
    expect(overviewDefinition("zh-CN").title).toBe("概览");
    expect(overviewDefinition("en").title).toBe("Overview");
  });

  it("stays inside the 24 columns, one panel to a cell", () => {
    const taken = new Set<string>();
    for (const { id, layout } of board("en").panels) {
      expect(layout.x + layout.w, id).toBeLessThanOrEqual(24);
      for (let x = layout.x; x < layout.x + layout.w; x++)
        for (let y = layout.y; y < layout.y + layout.h; y++) {
          expect(taken.has(`${x}:${y}`), id).toBe(false);
          taken.add(`${x}:${y}`);
        }
    }
  });

  it("narrows every panel by the window but the whole backlog", () => {
    expect(board("en").fields.map(({ name }) => name)).toEqual([
      OVERVIEW_WINDOW,
    ]);
    for (const panel of panels("en")) {
      const field =
        definitionOf(panel) === EXECUTION_HISTORY
          ? "createTime"
          : "state.executeAt";
      expect(panel.bindings, panel.id).toEqual(
        panel.id === "all-active"
          ? []
          : [{ globalField: OVERVIEW_WINDOW, panelField: field }],
      );
    }
  });

  it("lists the due-for-retry queue as the panel the commands go on", () => {
    const attention = panels("en").find(({ id }) => id === ATTENTION_PANEL);
    expect(attention?.instanceId).toBe("system:execution-failed:next-retry");
  });
});
