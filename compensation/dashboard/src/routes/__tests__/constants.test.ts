import { describe, it, expect } from "vitest";
import {
  DashboardNavItem,
  NavItemPaths,
  NavItems,
  PrimaryNavItems,
} from "../constants.tsx";

describe("routes/constants", () => {
  describe("NavItemPaths", () => {
    it("has correct path values", () => {
      expect(NavItemPaths.Analytics).toBe("/analytics");
      expect(NavItemPaths.ToRetry).toBe("/to-retry");
      expect(NavItemPaths.Executing).toBe("/executing");
      expect(NavItemPaths.NextRetry).toBe("/next-retry");
      expect(NavItemPaths.NonRetryable).toBe("/non-retryable");
      expect(NavItemPaths.Succeeded).toBe("/succeeded");
      expect(NavItemPaths.Unrecoverable).toBe("/unrecoverable");
    });
  });

  describe("NavItems", () => {
    it("has 6 navigation items", () => {
      expect(NavItems).toHaveLength(6);
    });

    it("each item has required properties", () => {
      NavItems.forEach((item) => {
        expect(item).toHaveProperty("label");
        expect(item).toHaveProperty("path");
        expect(item).toHaveProperty("category");
        expect(item).toHaveProperty("component");
        expect(item.component).toBeDefined();
      });
    });

    it("paths match NavItemPaths", () => {
      const paths = NavItems.map(item => item.path);
      expect(paths).toEqual([
        NavItemPaths.ToRetry,
        NavItemPaths.Executing,
        NavItemPaths.NextRetry,
        NavItemPaths.NonRetryable,
        NavItemPaths.Succeeded,
        NavItemPaths.Unrecoverable,
      ]);
    });
  });

  it("puts Dashboard first while keeping queue navigation separate", () => {
    expect(NavItemPaths.Dashboard).toBe("/dashboard");
    expect(NavItemPaths.Analytics).toBe("/analytics");
    expect(DashboardNavItem).toEqual({ label: "Dashboard", path: "/dashboard" });
    expect(PrimaryNavItems.map(({ label }) => label)).toEqual([
      "Dashboard",
      "To Retry",
      "Executing",
      "Next Retry",
      "Non Retryable",
      "Succeeded",
      "Unrecoverable",
    ]);
    expect(NavItems).toHaveLength(6);
  });
});
