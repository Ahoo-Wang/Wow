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
  SearchMode,
  type QueryDescriptorResult,
  type QueryModelDescriptor,
} from "@ahoo-wang/wow-client";
import {
  MemoryViewStore,
  ViewEngine,
  searchFieldOf,
  systemInstanceId,
  type Issue,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import { afterEach, describe, expect, it, vi } from "vitest";
import mongoEvent from "../../e2e/support/descriptors/mongo-event.json";
import mongoSnapshot from "../../e2e/support/descriptors/mongo-snapshot.json";
import {
  executionEngineOptions,
  executionFailedSource,
  executionHistorySource,
} from "./engine.ts";
import {
  EXECUTION_FAILED,
  executionFailedDefinition,
} from "./executionFailed.ts";
import { EXECUTION_HISTORY } from "./executionHistory.ts";
import { OVERVIEW_BOARD } from "./overview.ts";

const LOCALES = ["en", "zh-CN"] as const;

/** What a MongoDB compensation server answers: no full-text search (G15). */
const MONGO_SNAPSHOT = mongoSnapshot as unknown as QueryModelDescriptor;
const MONGO_EVENT = mongoEvent as unknown as QueryModelDescriptor;

/** The same model on an Elasticsearch snapshot store, which searches errors. */
const SEARCHING_SNAPSHOT: QueryModelDescriptor = {
  ...MONGO_SNAPSHOT,
  version: "sha256:searching",
  record: {
    ...MONGO_SNAPSHOT.record,
    search: {
      modes: [SearchMode.PHRASE, SearchMode.TERMS],
      fields: ["state.error.errorMsg", "state.error.stackTrace"],
    },
  },
};

function read(descriptor: QueryModelDescriptor): QueryDescriptorResult {
  return { notModified: false, descriptor, version: descriptor.version };
}

function described(descriptor: QueryModelDescriptor | null) {
  return {
    paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
    cursor: vi.fn(() => Promise.reject(new Error("not queried"))),
    aggregate: vi.fn(() => Promise.resolve([])),
    ...(descriptor
      ? { describe: vi.fn(() => Promise.resolve(read(descriptor))) }
      : {}),
  } satisfies ViewSource;
}

const engines: ViewEngine[] = [];
afterEach(() => {
  engines.splice(0).forEach((engine) => engine.dispose());
});

function engineOver(
  snapshot: QueryModelDescriptor | null,
  locale: (typeof LOCALES)[number] = "en",
) {
  const issues: Issue[] = [];
  const source = described(snapshot);
  const historySource = described(snapshot ? MONGO_EVENT : null);
  const engine = new ViewEngine({
    ...executionEngineOptions({
      locale,
      store: new MemoryViewStore(),
      source,
      historySource,
    }),
    onIssue: (found) => issues.push(found),
  });
  engines.push(engine);
  return { engine, issues, source, historySource };
}

async function openData(engine: ViewEngine, instanceId: string) {
  const runtime = await engine.open(instanceId);
  if (runtime.kind === "dashboard") throw new Error("expected a data view");
  return runtime;
}

describe("the console's sources", () => {
  it("describe each query model through wow-client's descriptor client", () => {
    // The clients bind their own methods, so the source hands them on as
    // they are; the snapshot's and the event stream's are the two routes.
    expect(executionFailedSource().describe?.name).toMatch(/describeSnapshot/);
    expect(executionHistorySource().describe?.name).toMatch(
      /describeEventStream/,
    );
  });
});

describe("the definitions against a MongoDB server's descriptors", () => {
  it.each(LOCALES)(
    "admit every system view with no error in %s",
    async (locale) => {
      const { engine, issues, source, historySource } = engineOver(
        MONGO_SNAPSHOT,
        locale,
      );
      const views = executionFailedDefinition(locale).views ?? [];
      for (const view of views)
        await openData(engine, systemInstanceId(EXECUTION_FAILED, view.id));
      for (const view of ["streams", "history"])
        await openData(engine, systemInstanceId(EXECUTION_HISTORY, view));
      await engine.open(OVERVIEW_BOARD);

      expect(issues.filter((found) => found.severity === "error")).toEqual([]);
      // The streams' events stay matchable: the overview's outcomes and the
      // history's conditions are written as ELEMENT_MATCH on `body`, which
      // the descriptor grants through `elements[]`, not the array's own list.
      expect(
        issues.filter(
          (found) => found.code === "capability.field.unfilterable",
        ),
      ).toEqual([]);
      // Read once per source, however many views open over it.
      expect(source.describe).toHaveBeenCalledTimes(1);
      expect(historySource.describe).toHaveBeenCalledTimes(1);
    },
  );

  it("offers no error search, and so sends none (G15)", async () => {
    const { engine, source } = engineOver(MONGO_SNAPSHOT);
    const runtime = await openData(
      engine,
      systemInstanceId(EXECUTION_FAILED, "active"),
    );

    expect(searchFieldOf(runtime.fields)).toBeNull();
    const sent = JSON.stringify(source.paged.mock.calls);
    expect(sent).not.toContain('"SEARCH"');
  });

  it("offers the error message the comparisons MongoDB answers", async () => {
    const { engine } = engineOver(MONGO_SNAPSHOT);
    const runtime = await openData(
      engine,
      systemInstanceId(EXECUTION_FAILED, "active"),
    );
    const errorMsg = runtime.definition.fields.find(
      (field) => field.name === "state.error.errorMsg",
    );
    // No operator list left: every one its kind offers is admitted.
    expect(errorMsg).toBeDefined();
    expect(errorMsg?.operators?.includes("CONTAINS") ?? true).toBe(true);
  });

  it("takes the server's page limits rather than stating its own", async () => {
    const { engine } = engineOver(MONGO_SNAPSHOT);
    const runtime = await openData(
      engine,
      systemInstanceId(EXECUTION_FAILED, "all"),
    );
    expect(runtime.limits.maxPageSize).toBe(
      MONGO_SNAPSHOT.limits.maxPageSize ?? undefined,
    );
    expect(runtime.limits.maxPageWindow).toBe(
      MONGO_SNAPSHOT.limits.maxPageWindow ?? undefined,
    );
  });
});

describe("the same definitions where the storage searches errors", () => {
  it("draws the error search by phrase", async () => {
    const { engine } = engineOver(SEARCHING_SNAPSHOT);
    const runtime = await openData(
      engine,
      systemInstanceId(EXECUTION_FAILED, "active"),
    );
    const search = searchFieldOf(runtime.fields);
    expect(search?.name).toBe("keyword");
    expect(search?.searchMode).toBe("PHRASE");
  });
});

describe("a server without descriptors", () => {
  it("runs on the definitions as declared, the error search included", async () => {
    const { engine, issues } = engineOver(null);
    const runtime = await openData(
      engine,
      systemInstanceId(EXECUTION_FAILED, "active"),
    );
    expect(searchFieldOf(runtime.fields)?.name).toBe("keyword");
    expect(issues.filter((found) => found.severity === "error")).toEqual([]);
  });
});
