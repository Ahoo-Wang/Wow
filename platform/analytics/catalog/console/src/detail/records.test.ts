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

import { describe, expect, it } from "vitest";
import type { SourceState } from "@/generated/index.ts";
import { datasetOf, definitionText, parseDefinition } from "./datasetRecord.ts";
import {
  changedSensitivity,
  keyOf,
  parseReferences,
  referencesText,
  sensitivityRows,
  sourceOf,
} from "./sourceRecord.ts";

describe("a source's record", () => {
  it("writes and reads references a line each", () => {
    const references = [
      { path: "customer_id", context: "crm", aggregate: "customer" },
      { path: "order_id", context: "shop", aggregate: "order" },
    ];
    const text = referencesText(references);
    expect(text).toBe("customer_id=crm.customer\norder_id=shop.order");
    expect(parseReferences(`${text}\n\n`)).toEqual({ references });
    expect(parseReferences(" a = b.c ")).toEqual({
      references: [{ path: "a", context: "b", aggregate: "c" }],
    });
    expect(parseReferences("a=b.c\nnot a reference")).toEqual({ line: 2 });
    expect(parseReferences("a=b")).toEqual({ line: 1 });
  });

  it("keys a Wow table's value by its event, an analyst's by its column", () => {
    expect(keyOf({ path: "buyer" })).toBe("buyer");
    expect(keyOf({ event: "OrderCreated", path: "address" })).toBe(
      "OrderCreated/address",
    );
  });

  it("reviews each column of an analyst's table, each value of a Wow table", () => {
    const analyst = {
      kind: "ANALYST",
      columns: [
        { name: "id", type: "String" },
        { name: "buyer", type: "String" },
      ],
      sensitivity: [{ path: "buyer", level: "display", keepPrefix: 1 }],
    } as SourceState;
    const rows = sensitivityRows(analyst);
    expect(rows.map((row) => [row.key, row.type, row.field.level])).toEqual([
      ["id", "String", null],
      ["buyer", "String", "display"],
    ]);
    expect(
      sensitivityRows({
        kind: "WOW",
        sensitivity: [{ event: "E", path: "p", level: "confidential" }],
      } as SourceState).map((row) => row.key),
    ).toEqual(["E/p"]);
    expect(sensitivityRows({} as SourceState)).toEqual([]);
  });

  it("sends the protection a review changes, and every column not reviewed yet", () => {
    const rows = sensitivityRows({
      kind: "ANALYST",
      columns: [
        { name: "id", type: "String" },
        { name: "buyer", type: "String" },
      ],
      sensitivity: [{ path: "buyer", level: "display", keepPrefix: 1 }],
    } as SourceState);
    expect(rows.map((row) => row.reviewed)).toEqual([false, true]);
    expect(changedSensitivity(rows, {})).toEqual([{ path: "id", level: null }]);
    expect(
      changedSensitivity(rows, {
        buyer: {
          path: "buyer",
          level: "display",
          keepPrefix: 1,
          keepSuffix: 0,
        },
      }),
    ).toEqual([{ path: "id", level: null }]);
    expect(
      changedSensitivity(rows, {
        id: { path: "id", level: "confidential" },
        buyer: { path: "buyer", level: "display", keepPrefix: 2 },
      }),
    ).toEqual([
      { path: "id", level: "confidential" },
      { path: "buyer", level: "display", keepPrefix: 2 },
    ]);
    const reviewed = sensitivityRows({
      kind: "ANALYST",
      columns: [{ name: "buyer", type: "String" }],
      sensitivity: [{ path: "buyer", level: "display", keepPrefix: 1 }],
    } as SourceState);
    expect(changedSensitivity(reviewed, {})).toEqual([]);
  });

  it("is the record's state, or an empty one", () => {
    expect(sourceOf({})).toEqual({});
    expect(datasetOf({ state: { name: "n" } })).toEqual({ name: "n" });
  });
});

describe("a dataset's definition", () => {
  it("is written as indented JSON, and read back", () => {
    const definition = { subject: "o", sources: [], columns: [] };
    expect(definitionText(null)).toBe("");
    expect(parseDefinition(definitionText(definition))).toEqual({ definition });
  });

  it("is refused when it is not a JSON object", () => {
    expect(parseDefinition("[]")).toEqual({
      error: "a definition is an object",
    });
    expect(parseDefinition("null")).toEqual({
      error: "a definition is an object",
    });
    expect(parseDefinition("{")).toHaveProperty("error");
  });
});
