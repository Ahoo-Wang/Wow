import type { ReactElement } from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppRouter } from "../Routes.tsx";

interface TestRoute {
  children?: TestRoute[];
  element?: ReactElement<Record<string, unknown>>;
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

vi.mock("../LazyDashboardView.tsx", () => ({
  default: () => {
    throw new Promise(() => undefined);
  },
}));

vi.mock("../LazyExecutionsPage.tsx", () => ({
  default: () => null,
}));

vi.mock("../constants.tsx", () => ({
  NavItemPaths: {
    Analytics: "/analytics",
    Dashboard: "/",
    Executions: "/executions",
  },
  QueueRoutes: [
    { path: "/to-retry", view: "system:execution-failed:to-retry" },
    { path: "/executing", view: "system:execution-failed:executing" },
  ],
  PrimaryNavItems: [],
}));

describe("AppRouter", () => {
  it("renders Dashboard at the root and redirects compatibility routes", () => {
    expect(AppRouter).toBeDefined();

    const root = mocks.routerConfig?.[0];
    expect(root?.children?.map(({ index, path }) => ({ index, path }))).toEqual(
      [
        { index: true, path: undefined },
        { index: undefined, path: "/executions" },
        { index: undefined, path: "/to-retry" },
        { index: undefined, path: "/executing" },
        { index: undefined, path: "/dashboard" },
        { index: undefined, path: "/analytics" },
        { index: undefined, path: "*" },
      ],
    );

    expect(root?.children?.[0].element?.props).not.toHaveProperty("replace");
    expect(root?.children?.[0].element?.props.children).toBeDefined();

    // An old queue address sends its view on, with what it came with.
    expect(root?.children?.[2].element?.props).toEqual({
      view: "system:execution-failed:to-retry",
    });
    expect(root?.children?.[3].element?.props).toEqual({
      view: "system:execution-failed:executing",
    });

    for (const index of [4, 5, 6]) {
      expect(root?.children?.[index].element?.props).toMatchObject({
        replace: true,
        to: "/",
      });
    }
  });

  it("shows the complete dashboard skeleton while the lazy route is pending", () => {
    const dashboardRoute = mocks.routerConfig?.[0].children?.[0];

    render(dashboardRoute?.element);

    expect(
      screen.getByRole("status", { name: "Loading dashboard" }),
    ).toBeInTheDocument();
    expect(document.querySelector("[data-slot='skeleton']")).not.toBeNull();
    expect(document.querySelectorAll("[data-slot='card']")).toHaveLength(4);
  });
});
