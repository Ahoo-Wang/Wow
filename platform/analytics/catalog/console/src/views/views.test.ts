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

import type { RecordViewConfig, SystemView } from "@ahoo-wang/wow-view-engine";
import { describe, expect, it } from "vitest";
import { datasets } from "./datasets.ts";
import { REFRESH_SECONDS, sources, sourcesOf } from "./sources.ts";

const view = (views: readonly SystemView[] | undefined, id: string) =>
  views?.find((each) => each.id === id)?.config as RecordViewConfig;

const columns = (config: RecordViewConfig) =>
  config.table.columns.map((column) => column.field);

describe("the catalog's views", () => {
  it("read their lists again on their own, the analyses not", () => {
    for (const definition of [sources, datasets])
      for (const each of definition.views ?? [])
        expect(each.config.refresh.interval).toBe(
          each.config.kind === "record" ? REFRESH_SECONDS : null,
        );
  });

  it("say whether a dataset still serves only where it needs revision", () => {
    expect(columns(view(datasets.views, "all"))).not.toContain(
      "state.stillServing",
    );
    expect(columns(view(datasets.views, "needs-revision"))).toContain(
      "state.stillServing",
    );
  });

  it("list the publications awaiting an approver by the reasons kept for them", () => {
    expect(view(datasets.views, "awaiting-approval").filter).toEqual({
      op: "and",
      children: [
        { field: "state.requestReasons", operator: "is-not-null", value: null },
      ],
    });
  });

  it("list the sources awaiting an approver: reviewed, not approved, and only where one is taken", () => {
    expect(view(sources.views, "unapproved").filter).toEqual({
      op: "and",
      children: [
        {
          field: "state.status",
          operator: "in",
          value: ["REVIEWED", "ADOPTED"],
        },
        { field: "state.approved", operator: "eq", value: false },
      ],
    });
    const ids = (requireSourceApproval: boolean) =>
      sourcesOf({ requireSourceApproval }).views?.map((each) => each.id);
    expect(ids(true)).toContain("unapproved");
    expect(ids(false)).not.toContain("unapproved");
    expect(ids(false)).toContain("drafts");
  });
});
