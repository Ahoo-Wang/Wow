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

vi.mock("../../features/App/ConsoleHost.tsx", () => ({
  ConsoleHost: () => null,
}));

vi.mock("../lazyPages.ts", () => ({
  LazyOverviewPage: () => {
    throw new Promise(() => undefined);
  },
  LazyBoardsPage: () => null,
  LazyExecutionsPage: () => null,
  LazyEventsPage: () => null,
}));

vi.mock("../constants.tsx", () => ({ Places: [] }));

describe("AppRouter", () => {
  it("routes the four places under one host of views, and sends any other address home", () => {
    expect(AppRouter).toBeDefined();

    const root = mocks.routerConfig?.[0];
    // The shell under the console's host of views: its places are the
    // engine's navigation.
    expect(root?.element?.props.children).toBeDefined();
    const places = root?.children ?? [];
    const other = places.at(-1);
    expect(
      places.slice(0, -1).map(({ index, path }) => ({ index, path })),
    ).toEqual([
      { index: true, path: undefined },
      { index: undefined, path: "/executions" },
      { index: undefined, path: "/events" },
      { index: undefined, path: "/boards" },
    ]);

    expect(places[0].element?.props).not.toHaveProperty("replace");
    expect(places[0].element?.props.children).toBeDefined();
    expect(other?.path).toBe("*");
    expect(other?.element?.props).toMatchObject({
      replace: true,
      to: "/",
    });
  });

  it("shows the page's skeleton while its chunk is pending", () => {
    const home = mocks.routerConfig?.[0].children?.[0];

    render(home?.element);

    expect(document.querySelector("[data-slot='skeleton']")).not.toBeNull();
  });
});
