import { describe, it, expect } from "vitest";
import {
  DashboardNavItem,
  ExecutionsNavItem,
  NavItemPaths,
  PrimaryNavItems,
  QueueRoutes,
} from "../constants.tsx";

describe("routes/constants", () => {
  it("has correct path values", () => {
    expect(NavItemPaths).toEqual({
      Dashboard: "/",
      Analytics: "/analytics",
      Executions: "/executions",
    });
  });

  it("keeps each old queue address, sent to its system view", () => {
    expect(QueueRoutes.map(({ path, view }) => [path, view])).toEqual([
      ["/active", "system:execution-failed:active"],
      ["/to-retry", "system:execution-failed:to-retry"],
      ["/executing", "system:execution-failed:executing"],
      ["/next-retry", "system:execution-failed:next-retry"],
      ["/non-retryable", "system:execution-failed:non-retryable"],
      ["/succeeded", "system:execution-failed:succeeded"],
      ["/unrecoverable", "system:execution-failed:unrecoverable"],
    ]);
  });

  it("navigates to the overview and the failed executions, nothing else", () => {
    expect(DashboardNavItem).toEqual({ label: "Overview", path: "/" });
    expect(ExecutionsNavItem).toEqual({
      label: "Failed executions",
      path: "/executions",
    });
    expect(PrimaryNavItems).toEqual([DashboardNavItem, ExecutionsNavItem]);
  });
});
