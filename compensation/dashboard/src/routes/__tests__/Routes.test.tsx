import type { ReactElement } from "react";
import { describe, expect, it, vi } from "vitest";
import { AppRouter } from "../Routes.tsx";

interface TestRoute {
  children?: TestRoute[];
  element?: ReactElement;
  index?: boolean;
  path?: string;
}

const mocks = vi.hoisted(() => ({
  routerConfig: undefined as TestRoute[] | undefined,
}));

vi.mock("react-router", () => ({
  createBrowserRouter: vi.fn((config: TestRoute[]) => {
    mocks.routerConfig = config;
    return { config };
  }),
  Navigate: () => null,
}));

vi.mock("../../features/App/App.tsx", () => ({
  default: () => null,
}));

vi.mock("../constants.tsx", () => ({
  NavItemPaths: { Analytics: "/analytics", Dashboard: "/dashboard" },
  DashboardNavItem: { label: "Dashboard", path: "/dashboard" },
  NavItems: [
    {
      category: "ToRetry",
      component: () => null,
      label: "To Retry",
      path: "/to-retry",
    },
    {
      category: "Executing",
      component: () => null,
      label: "Executing",
      path: "/executing",
    },
  ],
  PrimaryNavItems: [],
}));

describe("AppRouter", () => {
  it("maps every navigation item and keeps Dashboard redirect guards", () => {
    expect(AppRouter).toBeDefined();

    const root = mocks.routerConfig?.[0];
    expect(root?.children?.map(({ index, path }) => ({ index, path }))).toEqual(
      [
        { index: true, path: undefined },
        { index: undefined, path: "/dashboard" },
        { index: undefined, path: "/to-retry" },
        { index: undefined, path: "/executing" },
        { index: undefined, path: "/analytics" },
        { index: undefined, path: "*" },
      ],
    );

    for (const index of [0, 4, 5]) {
      expect(root?.children?.[index].element?.props).toMatchObject({
        replace: true,
        to: "/dashboard",
      });
    }
  });
});
