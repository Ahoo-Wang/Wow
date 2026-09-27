import { describe, it, expect } from "vitest";
import { NavItems } from "../constants.tsx";

describe("routes/constants", () => {
  it("lists the four places, in the top bar's order, and nothing else", () => {
    expect(NavItems.map(({ label, path }) => [label, path])).toEqual([
      ["Overview", "/"],
      ["Failed executions", "/executions"],
      ["Event stream", "/events"],
      ["Boards", "/boards"],
    ]);
  });
});
