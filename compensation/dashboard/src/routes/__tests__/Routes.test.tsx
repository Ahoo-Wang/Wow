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
  LazyViewsLayout: () => null,
  LazyOverviewPage: () => {
    throw new Promise(() => undefined);
  },
  LazyBoardsPage: () => null,
  LazyExecutionsPage: () => null,
  LazyEventsPage: () => null,
}));

vi.mock("../constants.tsx", () => ({ NavItems: [] }));

describe("AppRouter", () => {
  it("routes the four places under one host of views, and sends any other address home", () => {
    expect(AppRouter).toBeDefined();

    const root = mocks.routerConfig?.[0];
    const [views, other] = root?.children ?? [];
    expect(views?.path).toBeUndefined();
    expect(views?.element?.props.children).toBeDefined();
    expect(
      views?.children?.map(({ index, path }) => ({ index, path })),
    ).toEqual([
      { index: true, path: undefined },
      { index: undefined, path: "/executions" },
      { index: undefined, path: "/events" },
      { index: undefined, path: "/boards" },
    ]);

    expect(views?.children?.[0].element?.props).not.toHaveProperty("replace");
    expect(views?.children?.[0].element?.props.children).toBeDefined();
    expect(other?.path).toBe("*");
    expect(other?.element?.props).toMatchObject({
      replace: true,
      to: "/",
    });
  });

  it("shows the page's skeleton while its chunk is pending", () => {
    const home = mocks.routerConfig?.[0].children?.[0].children?.[0];

    render(home?.element);

    expect(document.querySelector("[data-slot='skeleton']")).not.toBeNull();
  });
});
