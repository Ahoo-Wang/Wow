import { describe, expect, it, vi } from "vitest";

vi.mock("@ahoo-wang/fetcher-wow", () => {
  throw new Error("FindCategory must not load the query runtime");
});

describe("FindCategory module boundary", () => {
  it("can load route categories without loading the query runtime", async () => {
    const { FindCategory } = await import("../FindCategory.ts");

    expect(FindCategory.ToRetry).toBe("ToRetry");
  });
});
