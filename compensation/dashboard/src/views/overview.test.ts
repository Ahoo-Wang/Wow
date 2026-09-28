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
  systemInstanceId,
  type DashboardViewConfig,
  type DashboardViewPanel,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import { inLocale } from "./text.ts";
import { describe, expect, it } from "vitest";
import { createExecutionEngine } from "./engine.ts";
import { executionFailed } from "./executionFailed.ts";
import { EXECUTION_HISTORY, executionHistory } from "./executionHistory.ts";
import {
  ATTENTION_PANEL,
  OVERVIEW,
  OVERVIEW_WINDOW,
  overview,
} from "./overview.ts";

const LOCALES = ["en", "zh-CN"] as const;

/**
 * The field each board's window narrows each panel by, as the wires once
 * written by hand said: the failed executions by when they ran, the event
 * streams by when they were written, the failures of the window by when
 * they first failed, a recovery by when it last changed. A panel not named
 * is read whole.
 */
const EXECUTED = "state.executeAt";
const WRITTEN = "createTime";
const WINDOWED: Record<string, Record<string, string>> = {
  home: {
    "in-window": EXECUTED,
    actionable: EXECUTED,
    "timed-out": EXECUTED,
    unrecoverable: EXECUTED,
    "new-failures": WRITTEN,
    prepared: WRITTEN,
    "retry-failed": WRITTEN,
    "retry-succeeded": WRITTEN,
    "net-backlog": WRITTEN,
    "retry-success": WRITTEN,
    clusters: EXECUTED,
    repair: "firstEventTime",
    recoverability: EXECUTED,
    retries: EXECUTED,
    [ATTENTION_PANEL]: EXECUTED,
  },
  failures: {
    arrivals: "firstEventTime",
    repair: "firstEventTime",
    recovery: "eventTime",
  },
  activity: {
    newFailures: WRITTEN,
    prepared: WRITTEN,
    retryFailed: WRITTEN,
    retrySucceeded: WRITTEN,
    activity: WRITTEN,
    "event-mix": WRITTEN,
    interventions: WRITTEN,
  },
};

/** A service with nothing in it: the board runs, and every count is zero. */
const emptySource: ViewSource = {
  paged: () => Promise.resolve({ total: 0, list: [] }),
  cursor: () => Promise.resolve({ nextCursor: null, list: [] }),
  aggregate: () => Promise.resolve([]),
};

const unusedSource: ViewSource = {
  paged: () => Promise.reject(new Error("not queried")),
  cursor: () => Promise.reject(new Error("not queried")),
  aggregate: () => Promise.reject(new Error("not queried")),
};

function board(locale: (typeof LOCALES)[number]): DashboardViewConfig {
  const config = inLocale(overview, locale).views?.[0]?.config;
  if (config?.kind !== "dashboard") throw new Error("No overview board");
  return config;
}

function panels(locale: (typeof LOCALES)[number]): DashboardViewPanel[] {
  return board(locale).panels.filter(
    (panel): panel is DashboardViewPanel => panel.kind === "view",
  );
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
    expect(inLocale(overview, "zh-CN").title).toBe("概览");
    expect(inLocale(overview, "en").title).toBe("Overview");
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

  /**
   * The board writes no time wires: the window reaches each panel through
   * its data's time field (`timeField`), or its view's own, as the engine
   * reads the board. What each panel runs under is the same as
   * when the wires were written by hand — so are the numbers.
   */
  it.each(["home", "failures", "activity"])(
    "narrows every panel of %s by the window but the whole backlog",
    async (view) => {
      const engine = createExecutionEngine({
        locale: "en",
        store: new MemoryViewStore(),
        source: emptySource,
        historySource: emptySource,
      });
      try {
        const runtime = await engine.open(systemInstanceId(OVERVIEW, view));
        await new Promise((settled) => setTimeout(settled, 0));
        const { applied } = runtime.getSnapshot();
        if (applied.kind !== "dashboard") throw new Error(view);
        expect(applied.fields.map(({ name }) => name)).toEqual([
          OVERVIEW_WINDOW,
        ]);
        for (const panel of applied.panels) {
          if (panel.kind !== "view") continue;
          const field = WINDOWED[view]?.[panel.id];
          expect(panel.bindings, panel.id).toEqual(
            field
              ? [
                  {
                    globalField: OVERVIEW_WINDOW,
                    panelField: field,
                    auto: true,
                  },
                ]
              : [],
          );
        }
        runtime.dispose();
      } finally {
        engine.dispose();
      }
    },
  );

  it("lays the analyses out on boards of their own, each naming a view there is", () => {
    const definition = inLocale(overview, "en");
    expect(definition.views?.map(({ id }) => id)).toEqual([
      "home",
      "failures",
      "activity",
    ]);
    const offered = new Set(
      [
        inLocale(executionFailed, "en"),
        inLocale(executionHistory, "en"),
      ].flatMap((each) =>
        (each.views ?? []).map(({ id }) => systemInstanceId(each.id, id)),
      ),
    );
    for (const view of definition.views ?? []) {
      if (view.config.kind !== "dashboard") throw new Error(view.id);
      for (const panel of view.config.panels)
        if (panel.kind === "view" && panel.instanceId)
          expect(offered.has(panel.instanceId), panel.instanceId).toBe(true);
    }
  });

  it.each(LOCALES)(
    "reads the clusters in a panel's few columns and opens every column in the workbench (%s)",
    (locale) => {
      const clusters = panels(locale).find(({ id }) => id === "clusters");
      const short = clusters?.owned?.config;
      const whole = inLocale(executionFailed, locale).views?.find(
        ({ id }) => id === "clusters",
      )?.config;
      if (short?.kind !== "analysis" || whole?.kind !== "analysis")
        throw new Error("no cluster analyses");
      const columns = (config: typeof short) =>
        config.groups.length + config.metrics.length;

      expect(columns(short)).toBe(7);
      // Only the function's kind is left out: the context, processor and
      // function fix it; none of the others is fixed by the rest.
      expect(short.groups.map(({ field }) => field)).toEqual([
        "state.function.contextName",
        "state.function.processorName",
        "state.function.name",
        "state.error.errorCode",
      ]);
      expect(columns(whole)).toBe(10);
      expect(clusters?.opens).toBe("system:execution-failed:clusters");
      // The panel's groups are some of the whole view's, so its conditions
      // and its press read the same fields.
      const wholeGroups = whole.groups.map(({ field }) => field);
      for (const { field } of short.groups)
        expect(wholeGroups).toContain(field);
    },
  );

  it("lists the due-for-retry queue as the panel the commands go on", () => {
    const attention = panels("en").find(({ id }) => id === ATTENTION_PANEL);
    expect(attention?.instanceId).toBe("system:execution-failed:next-retry");
  });
});
