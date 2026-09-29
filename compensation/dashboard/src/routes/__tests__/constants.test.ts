import { describe, it, expect } from "vitest";
import { Places } from "../constants.tsx";

describe("routes/constants", () => {
  it("lists the four places, in the top bar's order, and nothing else", () => {
    expect(
      Places.map(({ label, resource, view }) => [label, resource, view]),
    ).toEqual([
      ["Overview", "overview", "system:overview:home"],
      ["Failed executions", "execution-failed", undefined],
      ["Event stream", "execution-history", undefined],
      ["Boards", "overview", undefined],
    ]);
  });
});
