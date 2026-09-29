import { MemoryViewStore, type ViewSource } from "@ahoo-wang/wow-view-engine";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.tsx";
import { ConsoleHost } from "../ConsoleHost.tsx";
import { I18nProvider } from "../../../i18n.tsx";
import { Places } from "../../../routes/constants.tsx";
import { createExecutionEngine } from "@/views/engine.ts";

/** A source that answers nothing: the shell never asks it. */
const silent: ViewSource = {
  paged: () => new Promise(() => undefined),
  cursor: () => new Promise(() => undefined),
  aggregate: () => new Promise(() => undefined),
};

/**
 * The shell as the console's route draws it: under the console's host of
 * views, on a router at `path`, its places the engine's navigation.
 */
function renderAt(path = "/executions") {
  const engine = createExecutionEngine({
    store: new MemoryViewStore(),
    source: silent,
    historySource: silent,
  });
  const router = createMemoryRouter(
    [
      {
        element: (
          <ConsoleHost engine={engine}>
            <App places={Places} />
          </ConsoleHost>
        ),
        children: [{ path: "*", element: <div>Route content</div> }],
      },
    ],
    { initialEntries: [path] },
  );
  render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
  return router;
}

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
    localStorage.clear();
    localStorage.setItem("wow-dashboard-locale", "en");
    document.documentElement.classList.remove("dark");
    systemPrefers(false);
  });

  it("puts the product and its four places in one top bar, the current one marked", () => {
    renderAt("/executions");

    const places = screen.getByRole("navigation", {
      name: "Primary navigation",
    });
    expect(places.closest("header")).not.toBeNull();
    expect(
      [...places.querySelectorAll("a")].map((link) => link.textContent),
    ).toEqual(["Overview", "Failed executions", "Event stream", "Boards"]);
    expect(
      [...places.querySelectorAll("a")].map((link) =>
        link.getAttribute("href"),
      ),
    ).toEqual(["/", "/executions", "/events", "/boards"]);
    expect(
      screen.getByRole("link", { name: "Failed executions" }),
    ).toHaveAttribute("aria-current", "page");
    // The overview is its board on `/`, not every address.
    expect(screen.getByRole("link", { name: "Overview" })).not.toHaveAttribute(
      "aria-current",
    );
    // The engine's theme on the shell, not on `<body>` (4.1).
    expect(places.closest(".fve-tokens")).not.toBeNull();
    expect(document.body).not.toHaveClass("fve-tokens");
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

  it("marks the overview on the home page, and the boards on theirs", () => {
    renderAt("/?id=EF-1");
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "Boards" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("links the build version to its commit", () => {
    renderAt();

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
    renderAt();

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
    renderAt();

    const languageButton = screen.getByRole("button", {
      name: "Current language: English",
    });
    fireEvent.mouseDown(languageButton, { button: 0, ctrlKey: false });
    expect(
      await screen.findByRole("menuitemradio", { name: "English" }),
    ).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByRole("menuitemradio", { name: "中文" }));

    expect(screen.getByRole("link", { name: "失败执行" })).toHaveAttribute(
      "aria-current",
      "page",
    );
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
    renderAt();
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
