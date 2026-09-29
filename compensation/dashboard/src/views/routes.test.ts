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

import { resolveNavigation } from "@ahoo-wang/wow-view-engine/testing";
import { describe, expect, it } from "vitest";
import { EXECUTION_FAILED } from "./executionFailed.ts";
import { EXECUTION_HISTORY } from "./executionHistory.ts";
import { OVERVIEW, OVERVIEW_BOARD } from "./overview.ts";
import { boardPath, ROUTES, withView } from "./routes.ts";

const bindingOf = (id: string) =>
  ROUTES.find((binding) => binding.definitionId === id);

describe("the console's routes", () => {
  it("puts a view in its workbench's address, and a view nobody saved on the default", () => {
    expect(withView("/executions", "system:execution-failed:all")).toBe(
      "/executions?view=system%3Aexecution-failed%3Aall",
    );
    expect(withView("/events", null)).toBe("/events");
  });

  it("goes back to the overview on the home page, and to others' pages", () => {
    expect(boardPath(OVERVIEW_BOARD)).toBe("/");
    expect(boardPath("mine")).toBe("/boards?view=mine");
  });

  it("routes each resource's ways off to its page, what it opens with in the entry", () => {
    const view = {
      kind: "view",
      definitionId: EXECUTION_FAILED,
      instanceId: "system:execution-failed:all",
      scopeFilter: null,
      filter: null,
    } as const;
    expect(resolveNavigation(view, bindingOf)).toMatchObject({
      path: "/executions?view=system%3Aexecution-failed%3Aall",
      state: { handOver: view },
    });
    const unsaved = {
      kind: "unsaved",
      definitionId: EXECUTION_HISTORY,
      title: "These events",
      config: {} as never,
      scopeFilter: null,
    } as const;
    expect(resolveNavigation(unsaved, bindingOf)).toMatchObject({
      path: "/events",
      state: { handOver: unsaved },
    });
    const filters = { values: {} };
    expect(
      resolveNavigation(
        {
          kind: "dashboard",
          definitionId: OVERVIEW,
          instanceId: OVERVIEW_BOARD,
          filters,
          tab: "week",
        },
        bindingOf,
      ),
    ).toMatchObject({ path: "/", state: { filters, tab: "week" } });
  });
});
