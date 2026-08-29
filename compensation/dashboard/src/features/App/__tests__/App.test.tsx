import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FindCategory } from "../../Failed/FindCategory.ts";
import App from "../App.tsx";

const mocks = vi.hoisted(() => ({
  outletContext: undefined as unknown,
  outletRender: vi.fn(),
  pathname: "/executing",
}));

vi.mock("react-router", () => ({
  Link: ({ children, to, ...props }: { children: ReactNode; to: string }) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
  NavLink: ({
    children,
    className,
    end,
    to,
    ...props
  }: {
    children: ReactNode;
    className: (state: { isActive: boolean }) => string;
    end?: boolean;
    to: string;
    "aria-label"?: string;
  }) => {
    const isActive = end
      ? to === mocks.pathname
      : to === mocks.pathname ||
        mocks.pathname.startsWith(to.endsWith("/") ? to : `${to}/`);
    return (
      <a
        aria-current={isActive ? "page" : undefined}
        className={className({ isActive })}
        data-end={end ? "true" : undefined}
        href={to}
        {...props}
      >
        {children}
      </a>
    );
  },
  Outlet: ({ context }: { context?: unknown }) => {
    mocks.outletContext = context;
    mocks.outletRender();
    return <div>Route content</div>;
  },
  useLocation: () => ({ pathname: mocks.pathname }),
}));

const navItems = [
  {
    label: "Dashboard",
    path: "/",
  },
  {
    label: "To Retry",
    path: "/to-retry",
    category: FindCategory.ToRetry,
    component: () => null,
  },
  {
    label: "Executing",
    path: "/executing",
    category: FindCategory.Executing,
    component: () => null,
  },
];

describe("App", () => {
  beforeEach(() => {
    mocks.outletContext = undefined;
    mocks.pathname = "/executing";
    mocks.outletRender.mockClear();
  });

  it("renders the current workspace title and navigation state", () => {
    render(<App navItems={navItems} />);

    expect(
      screen.getByRole("heading", { name: "Executing" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Executing" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "Executing" })).toHaveAttribute(
      "aria-label",
      "Executing",
    );
    expect(
      screen.getByRole("link", { name: "Wow compensation dashboard" }),
    ).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "Dashboard" })).not.toHaveAttribute(
      "aria-current",
    );
    expect(screen.getByRole("link", { name: "Dashboard" })).toHaveAttribute(
      "data-end",
      "true",
    );
    const projectLinks = screen.getByRole("navigation", {
      name: "Project repositories",
    });
    const githubLink = screen.getByRole("link", { name: "GitHub" });
    const giteeLink = screen.getByRole("link", { name: "Gitee" });
    expect(projectLinks).toContainElement(githubLink);
    expect(projectLinks).toContainElement(giteeLink);
    expect(githubLink).toHaveAttribute(
      "href",
      "https://github.com/Ahoo-Wang/Wow",
    );
    expect(giteeLink).toHaveAttribute("href", "https://gitee.com/AhooWang/Wow");
    expect(githubLink.querySelector("img")).toHaveAttribute(
      "src",
      "/github.svg",
    );
    expect(giteeLink.querySelector("img")).toHaveAttribute("src", "/gitee.svg");
    expect(
      screen.getByRole("link", { name: "Skip to main content" }),
    ).toHaveAttribute("href", "#main-content");
    expect(document.querySelector("#main-content")).toHaveAttribute(
      "tabindex",
      "-1",
    );
    expect(screen.getByText("Route content")).toBeInTheDocument();
  });

  it("shows the build version and GitHub commit instead of a clock", () => {
    render(<App navItems={navItems} />);

    expect(
      screen.queryByLabelText("Current local time"),
    ).not.toBeInTheDocument();
    expect(screen.getByText("v8.16.1")).toBeInTheDocument();
    const commitLink = screen.getByRole("link", {
      name: /^GitHub commit [0-9a-f]{40}$/,
    });
    expect(commitLink).toHaveTextContent(/^[0-9a-f]{7}$/);
    expect(commitLink).toHaveAttribute(
      "href",
      expect.stringMatching(
        /^https:\/\/github\.com\/Ahoo-Wang\/Wow\/commit\/[0-9a-f]{40}$/,
      ),
    );
  });

  it("collapses and expands the desktop navigation", () => {
    render(<App navItems={navItems} />);

    const sidebar = screen.getByRole("complementary", {
      name: "Primary navigation",
    });
    const collapse = screen.getByRole("button", {
      name: "Collapse navigation",
    });
    expect(sidebar).not.toHaveClass("is-collapsed");
    expect(collapse).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(collapse);

    expect(sidebar).toHaveClass("is-collapsed");
    expect(
      screen.getByRole("button", { name: "Expand navigation" }),
    ).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(screen.getByRole("button", { name: "Expand navigation" }));

    expect(sidebar).not.toHaveClass("is-collapsed");
  });

  it("uses Dashboard as the workspace and logo destination", () => {
    mocks.pathname = "/";
    render(<App navItems={navItems} />);

    expect(screen.getByRole("heading", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Dashboard" })).toHaveAttribute(
      "href",
      "/",
    );
    expect(screen.getByRole("link", { name: "Dashboard" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(
      screen.getByRole("link", { name: "Wow compensation dashboard" }),
    ).toHaveAttribute(
      "href",
      "/",
    );
  });

  it("keeps Dashboard range state and controls out of the App shell", () => {
    mocks.pathname = "/";
    render(<App navItems={navItems} />);

    expect(screen.queryByText("Outcomes window")).not.toBeInTheDocument();
    expect(screen.queryByText("Applies to outcomes only")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "24h" })).not.toBeInTheDocument();
    expect(mocks.outletContext).toBeUndefined();
    expect(document.querySelector(".app-topbar")).not.toHaveClass(
      "has-dashboard-controls",
    );
  });
});
