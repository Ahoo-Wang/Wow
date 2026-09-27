import {
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.tsx";
import { I18nProvider } from "../../../i18n.tsx";
import type { NavItem } from "../../../routes/constants.tsx";

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
    className?: string | ((state: { isActive: boolean }) => string);
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
        className={
          typeof className === "function" ? className({ isActive }) : className
        }
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

const navItems: readonly NavItem[] = [
  { label: "Overview", path: "/" },
  { label: "Failed executions", path: "/executions" },
  { label: "Event stream", path: "/events" },
  { label: "Boards", path: "/boards" },
];

/** A system that prefers `dark`, and the listeners it hands its changes to. */
function systemPrefers(dark: boolean) {
  const listeners = new Set<() => void>();
  const media = {
    matches: dark,
    media: "(prefers-color-scheme: dark)",
    addEventListener: (_: string, listener: () => void) =>
      listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) =>
      listeners.delete(listener),
  };
  vi.mocked(window.matchMedia).mockImplementation(
    () => media as unknown as MediaQueryList,
  );
  return {
    change(next: boolean) {
      media.matches = next;
      for (const listener of listeners) listener();
    },
  };
}

describe("App", () => {
  beforeEach(() => {
    mocks.outletContext = undefined;
    mocks.pathname = "/executions";
    mocks.outletRender.mockClear();
    localStorage.clear();
    document.documentElement.classList.remove("dark");
    systemPrefers(false);
  });

  it("puts the product and its four places in one top bar, the current one marked", () => {
    render(<App navItems={navItems} />);

    const places = screen.getByRole("navigation", {
      name: "Primary navigation",
    });
    expect(places.closest("header")).not.toBeNull();
    expect(
      [...places.querySelectorAll("a")].map((link) => link.textContent),
    ).toEqual(["Overview", "Failed executions", "Event stream", "Boards"]);
    expect(
      screen.getByRole("link", { name: "Failed executions" }),
    ).toHaveAttribute("aria-current", "page");
    // The overview is `/` exactly, not every address.
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute(
      "data-end",
      "true",
    );
    // No sidebar of the console's own (W15).
    expect(screen.queryByRole("complementary")).toBeNull();
    expect(
      screen.getByRole("link", { name: "Compensation console" }),
    ).toHaveAttribute("href", "/");
    // The page writes its own heading (the workbench's view list, the
    // overview's header); the bar names the place, not the page.
    expect(screen.queryByRole("heading")).toBeNull();
    expect(
      screen.getByRole("link", { name: "Skip to main content" }),
    ).toHaveAttribute("href", "#main-content");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
    expect(screen.getByText("Route content")).toBeInTheDocument();
  });

  it("links the build version to its commit", () => {
    render(<App navItems={navItems} />);

    const version = screen.getByRole("link", {
      name: /^Version \d+\.\d+\.\d+/,
    });
    expect(version).toHaveTextContent(/^v\d+\.\d+\.\d+[0-9a-f]{7}$/);
    expect(version).toHaveAttribute(
      "href",
      expect.stringMatching(
        /^https:\/\/github\.com\/Ahoo-Wang\/Wow\/commit\/[0-9a-f]{40}$/,
      ),
    );
  });

  it("folds the places into a menu the phone opens, the current one marked", async () => {
    render(<App navItems={navItems} />);

    // One navigation landmark: the phone's button opens a menu of the same
    // places rather than a second landmark.
    expect(
      screen.getAllByRole("navigation", { name: "Primary navigation" }),
    ).toHaveLength(1);
    fireEvent.mouseDown(
      screen.getByRole("button", { name: "Open navigation" }),
      { button: 0, ctrlKey: false },
    );
    const items = await screen.findAllByRole("menuitem");
    expect(items.map((item) => item.textContent)).toEqual([
      "Overview",
      "Failed executions",
      "Event stream",
      "Boards",
    ]);
    expect(items[1]).toHaveAttribute("aria-current", "page");
    expect(items[1]).toHaveAttribute("href", "/executions");
  });

  it("switches the interface language from the top bar", async () => {
    localStorage.setItem("wow-dashboard-locale", "en");
    render(
      <I18nProvider>
        <App navItems={navItems} />
      </I18nProvider>,
    );

    const languageButton = screen.getByRole("button", {
      name: "Current language: English",
    });
    fireEvent.mouseDown(languageButton, { button: 0, ctrlKey: false });
    expect(
      await screen.findByRole("menuitemradio", { name: "English" }),
    ).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByRole("menuitemradio", { name: "中文" }));

    expect(
      screen.getByRole("link", { name: "失败执行" }),
    ).toHaveAttribute("aria-current", "page");
    expect(
      screen.getByRole("link", { name: "补偿控制台" }),
    ).toBeInTheDocument();
    expect(localStorage.getItem("wow-dashboard-locale")).toBe("zh-CN");
    await waitFor(() =>
      expect(screen.queryByRole("menuitemradio", { name: "中文" })).toBeNull(),
    );
  });

  /**
   * console-redesign.md §6: the system's light or dark by default, pinned
   * from the top bar and kept on this machine. The old console stayed light
   * on a dark system (W14).
   */
  it("follows the system's light or dark, and pins one when picked", async () => {
    const system = systemPrefers(true);
    render(<App navItems={navItems} />);
    const root = document.documentElement;
    expect(root).toHaveClass("dark");
    system.change(false);
    expect(root).not.toHaveClass("dark");

    fireEvent.mouseDown(
      screen.getByRole("button", { name: "Appearance: Follow system" }),
      { button: 0, ctrlKey: false },
    );
    fireEvent.click(await screen.findByRole("menuitemradio", { name: "Dark" }));
    expect(root).toHaveClass("dark");
    expect(root.style.colorScheme).toBe("dark");
    // Pinned: the system no longer moves it.
    system.change(false);
    expect(root).toHaveClass("dark");
    expect(
      screen.getByRole("button", { name: "Appearance: Dark" }),
    ).toBeInTheDocument();
    expect(localStorage.getItem("compensation-console.color-mode")).toBe(
      "dark",
    );

    fireEvent.mouseDown(
      screen.getByRole("button", { name: "Appearance: Dark" }),
      {
        button: 0,
        ctrlKey: false,
      },
    );
    fireEvent.click(
      await screen.findByRole("menuitemradio", { name: "Follow system" }),
    );
    expect(root).not.toHaveClass("dark");
    expect(localStorage.getItem("compensation-console.color-mode")).toBeNull();
  });
});
