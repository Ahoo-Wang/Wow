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

import { MemoryViewStore, type ViewSource } from "@ahoo-wang/wow-view-engine";
import { describe, expect, it } from "vitest";
import { createExecutionEngine, executionEngineOptions } from "./engine.ts";
import { EXECUTION_FAILED } from "./executionFailed.ts";
import {
  EXECUTION_HISTORY,
  executionHistoryDefinition,
} from "./executionHistory.ts";

const LOCALES = ["en", "zh-CN"] as const;

function source(name: string): ViewSource {
  return {
    paged: () => Promise.resolve({ total: 0, list: [], name }),
    cursor: () => Promise.reject(new Error("not queried")),
    aggregate: () => Promise.reject(new Error("not queried")),
  } as ViewSource;
}

describe("executionHistoryDefinition", () => {
  it.each(LOCALES)("is admitted by the engine in %s", (locale) => {
    const engine = createExecutionEngine({
      locale,
      store: new MemoryViewStore(),
      source: source("snapshot"),
      historySource: source("events"),
    });
    try {
      expect(engine.definitionIssues(EXECUTION_HISTORY)).toEqual([]);
    } finally {
      engine.dispose();
    }
  });

  it("reads the streams from the event source, not the snapshot's", () => {
    const snapshot = source("snapshot");
    const events = source("events");
    const options = executionEngineOptions({
      locale: "en",
      store: new MemoryViewStore(),
      source: snapshot,
      historySource: events,
    });
    const sourceOf = (id: string) => {
      const definition = options.definitions?.find((each) => each.id === id);
      if (definition?.kind !== "data") throw new Error(`no ${id}`);
      return options.resolveSource(definition.source);
    };
    expect(sourceOf(EXECUTION_HISTORY)).toBe(events);
    expect(sourceOf(EXECUTION_FAILED)).toBe(snapshot);
  });

  it("names each event type in both languages", () => {
    const [en, zh] = LOCALES.map(executionHistoryDefinition);
    const typesOf = (definition: typeof en) =>
      definition.fields
        .find((field) => field.name === "body")
        ?.elements?.find((element) => element.name === "bodyType")?.options;
    expect(typesOf(en)?.map((option) => option.value)).toEqual(
      typesOf(zh)?.map((option) => option.value),
    );
    expect(typesOf(en)).toContainEqual({
      value: "me.ahoo.wow.compensation.api.ExecutionFailedApplied",
      label: "Retry failed",
    });
    expect(zh.title).toBe("执行历史");
  });
});
