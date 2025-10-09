import React from "react";
import { describe, expect, it, vi } from "vitest";
import "@testing-library/jest-dom";
import App from "../App.tsx";
import { NavItem } from "../../../routes/constants.tsx";
import { FindCategory } from "../../Failed/FindCategory.ts";

// Mock all dependencies
vi.mock("antd", () => ({
  Layout: {
    Header: ({ children }: { children: React.ReactNode }) => (
      <header>{children}</header>
    ),
    Content: ({ children }: { children: React.ReactNode }) => (
      <main>{children}</main>
    ),
    Footer: ({ children }: { children: React.ReactNode }) => (
      <footer>{children}</footer>
    ),
  },
  Menu: ({ items }: { items?: unknown[] }) => <nav>{items?.length} items</nav>,
  Watermark: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  theme: { useToken: () => ({ token: {} }) },
  Typography: { Title: () => <h1>Title</h1> },
  Space: () => <div>Space</div>,
}));

vi.mock("@ant-design/icons", () => ({
  GithubOutlined: () => <span>Github</span>,
  CodepenOutlined: () => <span>Codepen</span>,
}));

vi.mock("../../components/ErrorBoundary/ErrorBoundary.tsx", () => ({
  ErrorBoundary: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
}));

vi.mock("react-router", () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
  Outlet: () => <div>Outlet</div>,
  useLocation: () => ({ pathname: "/" }),
}));

vi.mock("../../routes/constants.tsx", () => ({
  NavItem: {},
}));

describe("App", () => {
  it("is a function", () => {
    expect(typeof App).toBe("function");
  });

  it("can be called with navItems prop", () => {
    const mockNavItems: NavItem[] = [
      {
        label: "Test",
        path: "/test",
        category: FindCategory.ToRetry,
        component: () => <div>Test Component</div>,
      },
    ];
    expect(() => App({ navItems: mockNavItems })).not.toThrow();
  });
});
