import { describe, it, expect } from "vitest";
import * as services from "./index.ts";

describe("services/index", () => {
  it("exports executionFailedCommandClient", () => {
    expect(services).toHaveProperty("executionFailedCommandClient");
  });

  it("exports executionFailedSnapshotQueryClient", () => {
    expect(services).toHaveProperty("executionFailedSnapshotQueryClient");
  });
});