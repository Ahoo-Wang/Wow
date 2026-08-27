import { describe, expect, it } from "vitest";
import { clearExecutionSelection, selectExecution } from "../selection.ts";

describe("execution selection query params", () => {
  it("writes the selected execution while preserving other params", () => {
    const params = selectExecution(
      new URLSearchParams("filter=failed"),
      "failed-2",
    );

    expect(params.toString()).toBe("filter=failed&id=failed-2");
  });

  it("clears only the selected execution", () => {
    const params = clearExecutionSelection(
      new URLSearchParams("filter=failed&id=failed-2"),
    );

    expect(params.toString()).toBe("filter=failed");
  });
});
