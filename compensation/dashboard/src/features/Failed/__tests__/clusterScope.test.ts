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
import {
  createClusterHref,
  parseClusterScope,
  clusterCondition,
} from "../clusterScope.ts";

const scope = {
  errorCode: "E & 1",
  contextName: "billing",
  processorName: "Processor",
  functionName: "run",
  functionKind: "EVENT",
  start: 1000,
  end: 2000,
};

describe("cluster drill-down", () => {
  it("preserves the full cluster identity and exclusive time bounds", () => {
    const url = new URL(createClusterHref(scope, scope), "http://localhost");
    expect(url.pathname).toBe("/active");
    expect(parseClusterScope(url.searchParams.get("cluster"))).toEqual(scope);
    const query = JSON.stringify(clusterCondition(scope));
    for (const value of [
      "state.error.errorCode",
      "state.function.name",
      "state.function.functionKind",
      '"op":"GTE"',
      '"op":"LT"',
    ]) {
      expect(query).toContain(value);
    }
  });
  it("rejects malformed or inverted scope instead of querying all records", () => {
    expect(parseClusterScope(null)).toBeNull();
    for (const value of [
      "{",
      "{}",
      JSON.stringify({ ...scope, end: 500 }),
      JSON.stringify({ ...scope, start: null }),
    ]) {
      expect(parseClusterScope(value)).toBeUndefined();
    }
  });
});
