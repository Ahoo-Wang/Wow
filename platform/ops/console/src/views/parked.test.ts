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

import type { QueryModelDescriptor } from "@ahoo-wang/wow-client";
import {
  BUILTIN_FIELD_KINDS,
  compileFilter,
  createFieldKindRegistry,
  type RecordViewConfig,
} from "@ahoo-wang/wow-view-engine";
import { admit, matches } from "@ahoo-wang/wow-view-engine/testing";
import { describe, expect, it } from "vitest";
import type { ParkedStateField } from "@/generated/index.ts";
import descriptor from "./fixtures/parked.descriptor.json";
import { definitionWords } from "./messages.ts";
import { PARKED, PARKED_SOURCE, PARKED_WORDS, parked } from "./parked.ts";
import { PARKED_ACTION_WORDS } from "./parkedActions.ts";
import { overview, OVERVIEW_WORDS } from "./overview.ts";
import { LOCALES } from "./words.ts";

/**
 * The descriptor the ops service answers for its `parked` snapshots
 * (`GET /ops/parked/snapshot/query`), as the memory backend's reference
 * query engine describes them: the paths, what each filters, sorts and
 * groups by.
 */
const DESCRIPTORS = {
  [PARKED_SOURCE]: descriptor as unknown as QueryModelDescriptor,
};

function entry(status: string, extra: Record<string, unknown> = {}) {
  return { aggregateId: status, state: { status, ...extra } };
}

describe("the parked entries' definition", () => {
  it.each(LOCALES)(
    "is admitted over the service's descriptor in %s",
    (locale) => {
      const words = definitionWords(locale);
      // The overview's board places the definition's views and its own:
      // admitted with it, nothing on either is narrowed — the state's times
      // are declared epoch milliseconds (`@QueryTemporal`), so they compare
      // with the service's clock and bucket by day.
      const findings = admit([parked, overview], DESCRIPTORS, {
        text: (key) => words[key],
      });
      expect(findings).toEqual([]);
    },
  );

  it("names only paths the generated client knows", () => {
    // `ParkedStateField` is the Manifest's list of `state.*` paths.
    const paths: ParkedStateField[] = parked.fields
      .map((field) => field.name)
      .filter((name): name is ParkedStateField => name.startsWith("state."));
    const described = new Set(
      (descriptor.fields as { path: string }[]).map((field) => field.path),
    );
    for (const path of paths) expect(described, path).toContain(path);
  });

  it("says every word in both languages", () => {
    for (const words of [PARKED_WORDS, PARKED_ACTION_WORDS, OVERVIEW_WORDS])
      expect(Object.keys(words["zh-CN"]).sort()).toEqual(
        Object.keys(words.en).sort(),
      );
  });

  it("keeps an operator's queues apart by status", () => {
    const views = Object.fromEntries(
      (parked.views ?? []).map((view) => [view.id, view.config]),
    );
    const documents = [
      "SCHEDULED",
      "EXHAUSTED",
      "UNRECOVERABLE",
      "RESOLVED",
    ].map((status) => entry(status));
    const kept = (id: string) =>
      documents
        .filter((document) =>
          matches(
            document,
            compileFilter(
              parked.fields,
              (views[id] as RecordViewConfig).filter,
              createFieldKindRegistry(BUILTIN_FIELD_KINDS),
              { now: new Date(0), timeZone: "UTC" },
            ),
          ),
        )
        .map((document) => document.aggregateId);
    expect(kept("attention")).toEqual(["EXHAUSTED", "UNRECOVERABLE"]);
    expect(kept("scheduled")).toEqual(["SCHEDULED"]);
    expect(kept("unrecoverable")).toEqual(["UNRECOVERABLE"]);
    expect(kept("resolved")).toEqual(["RESOLVED"]);
    expect(kept("all")).toHaveLength(4);
    expect(kept("active")).toEqual(["SCHEDULED", "EXHAUSTED", "UNRECOVERABLE"]);
    for (const id of ["by-context", "clusters", "by-subscription", "over-time"])
      expect(views[id].kind).toBe("analysis");
  });

  it("reads the entries again on its own, and every view by when it failed", () => {
    expect(parked.id).toBe(PARKED);
    expect(parked.timeField).toBe("state.failedAt");
    for (const view of parked.views ?? [])
      if (view.config.kind === "record")
        expect(view.config.refresh.interval).toBe(15);
  });
});
