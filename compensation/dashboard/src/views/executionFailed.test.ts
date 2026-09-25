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

import { filter, type FilterExpression } from "@ahoo-wang/wow-client";
import {
  BUILTIN_FIELD_KINDS,
  createFieldKindRegistry,
  compileFilter,
  MemoryViewStore,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import { describe, expect, it } from "vitest";
import { FindCategory } from "../../e2e/support/legacy/FindCategory.ts";
import { RetryConditions } from "../../e2e/support/legacy/RetryConditions.ts";
import { createExecutionEngine } from "./engine.ts";
import {
  EXECUTION_FAILED,
  executionFailedDefinition,
} from "./executionFailed.ts";

const LOCALES = ["en", "zh-CN"] as const;

const unusedSource: ViewSource = {
  paged: () => Promise.reject(new Error("not queried")),
  cursor: () => Promise.reject(new Error("not queried")),
  aggregate: () => Promise.reject(new Error("not queried")),
};

/**
 * Spelled one way: a single-operand AND says the same as its operand, and
 * `IN` one value the same as `EQ` it (the enum editor writes `IN`, so a
 * reader can pick a second value).
 */
function normalize(expression: FilterExpression): FilterExpression {
  if (expression.op === "IN" && expression.values.length === 1)
    return {
      op: "EQ",
      field: expression.field,
      value: expression.values[0],
    } as FilterExpression;
  if ("operands" in expression) {
    const operands = expression.operands.map(normalize);
    if (expression.op === "AND" && operands.length === 1) return operands[0];
    return { ...expression, operands } as FilterExpression;
  }
  return expression;
}

/** The moment the old queues were asked at, which only they carry. */
const NOW = 1_789_000_000_000;

/**
 * The old queue's reading of the moment, said against the service's clock:
 * `timeoutAt < now` is `BEFORE_NOW`, `timeoutAt >= now` is not that, and
 * `nextRetryAt <= now` is not `AFTER_NOW`. What is left must be the same
 * condition, operand by operand.
 */
function onServerClock(expression: FilterExpression): FilterExpression {
  if ("operands" in expression)
    return {
      ...expression,
      operands: expression.operands.map(onServerClock),
    } as FilterExpression;
  if (
    "field" in expression &&
    "value" in expression &&
    expression.value === NOW
  ) {
    const field = expression.field;
    if (expression.op === "LT") return filter.beforeNow(field);
    if (expression.op === "GTE") return filter.nor([filter.beforeNow(field)]);
    if (expression.op === "LTE") return filter.nor([filter.afterNow(field)]);
  }
  return expression;
}

describe("executionFailedDefinition", () => {
  it.each(LOCALES)("is admitted by the engine in %s", (locale) => {
    const engine = createExecutionEngine({
      locale,
      store: new MemoryViewStore(),
      source: unusedSource,
    });
    try {
      expect(engine.definitionIssues(EXECUTION_FAILED)).toEqual([]);
    } finally {
      engine.dispose();
    }
  });

  it("declares the same fields, groups and views in every language", () => {
    const [en, zh] = LOCALES.map(executionFailedDefinition);
    expect(zh.fields.map((field) => field.name)).toEqual(
      en.fields.map((field) => field.name),
    );
    expect(zh.fieldGroups).toHaveLength(en.fieldGroups?.length ?? 0);
    expect(zh.views?.map((view) => view.id)).toEqual(
      en.views?.map((view) => view.id),
    );
    // Each language has its own words.
    expect(zh.title).toBe("失败执行");
    expect(en.title).toBe("Failed executions");
  });

  it("offers the system views: seven queues, all, three analyses", () => {
    const views = executionFailedDefinition("en").views ?? [];
    expect(views.map((view) => [view.id, view.config.kind])).toEqual([
      ["active", "record"],
      ["to-retry", "record"],
      ["executing", "record"],
      ["next-retry", "record"],
      ["non-retryable", "record"],
      ["unrecoverable", "record"],
      ["succeeded", "record"],
      ["all", "record"],
      ["by-status", "analysis"],
      ["by-processor", "analysis"],
      ["daily", "analysis"],
    ]);
  });

  it.each([
    ["active", FindCategory.Active],
    ["to-retry", FindCategory.ToRetry],
    ["executing", FindCategory.Executing],
    ["next-retry", FindCategory.NextRetry],
    ["non-retryable", FindCategory.NonRetryable],
    ["unrecoverable", FindCategory.Unrecoverable],
    ["succeeded", FindCategory.Succeeded],
  ])("selects what the old %s queue selects", (viewId, category) => {
    const definition = executionFailedDefinition("en");
    const config = definition.views?.find(({ id }) => id === viewId)?.config;
    if (config?.kind !== "record") throw new Error(`No record view ${viewId}`);
    const compiled = compileFilter(
      definition.fields,
      config.filter,
      createFieldKindRegistry(BUILTIN_FIELD_KINDS),
      { now: new Date(0), timeZone: "UTC" },
    );
    expect(normalize(compiled)).toEqual(
      normalize(
        onServerClock(RetryConditions.categoryToCondition(category, NOW)),
      ),
    );
  });
});
