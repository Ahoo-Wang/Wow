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
  NavItemPaths: { Analytics: "/analytics", ToRetry: "/to-retry" },
  AnalyticsNavItem: { label: "Analytics", path: "/analytics" },
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
  it("maps every navigation item and keeps both redirect guards", () => {
    expect(AppRouter).toBeDefined();

    const root = mocks.routerConfig?.[0];
    expect(root?.children?.map(({ index, path }) => ({ index, path }))).toEqual(
      [
        { index: true, path: undefined },
        { index: undefined, path: "/to-retry" },
        { index: undefined, path: "/executing" },
        { index: undefined, path: "/analytics" },
        { index: undefined, path: "*" },
      ],
    );

    const indexRedirect = root?.children?.[0].element;
    const fallbackRedirect = root?.children?.[4].element;
    expect(indexRedirect?.props).toMatchObject({
      replace: true,
      to: "/to-retry",
    });
    expect(fallbackRedirect?.props).toMatchObject({
      replace: true,
      to: "/to-retry",
    });
  });
});
