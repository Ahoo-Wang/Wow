import type { ReactElement } from "react";
import { render } from "@testing-library/react";
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

vi.mock("../lazyPages.ts", () => ({
  LazyOverviewPage: () => {
    throw new Promise(() => undefined);
  },
  LazyBoardsPage: () => null,
  LazyExecutionsPage: () => null,
  LazyEventsPage: () => null,
}));

vi.mock("../constants.tsx", () => ({
  NavItemPaths: {
    Analytics: "/analytics",
    Boards: "/boards",
    Dashboard: "/",
    Events: "/executions/events",
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
        { index: undefined, path: "/boards" },
        { index: undefined, path: "/executions" },
        { index: undefined, path: "/executions/events" },
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
    expect(root?.children?.[4].element?.props).toEqual({
      view: "system:execution-failed:to-retry",
    });
    expect(root?.children?.[5].element?.props).toEqual({
      view: "system:execution-failed:executing",
    });

    for (const index of [6, 7, 8]) {
      expect(root?.children?.[index].element?.props).toMatchObject({
        replace: true,
        to: "/",
      });
    }
  });

  it("shows the page's skeleton while its chunk is pending", () => {
    const home = mocks.routerConfig?.[0].children?.[0];

    render(home?.element);

    expect(document.querySelector("[data-slot='skeleton']")).not.toBeNull();
  });
});
