import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("react", () => ({
  StrictMode: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="strict-mode">{children}</div>
  ),
}));

const mocks = vi.hoisted(() => ({
  createRoot: vi.fn(),
  render: vi.fn(),
}));

vi.mock("react-dom/client", () => ({
  createRoot: mocks.createRoot,
}));
vi.mock("./index.css", () => ({}));
vi.mock("@/i18n.tsx", () => ({
  I18nProvider: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("react-router", () => ({
  RouterProvider: ({ router }: { router: unknown }) => (
    <div data-testid="router-provider">
      {router ? "has-router" : "no-router"}
    </div>
  ),
}));
vi.mock("./routes/Routes.tsx", () => ({
  AppRouter: { test: "router" },
}));
vi.mock("./components/GlobalDrawer", () => ({
  GlobalDrawerProvider: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="global-drawer-provider">{children}</div>
  ),
}));
vi.mock("@/components/ui/tooltip", () => ({
  TooltipProvider: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
}));
vi.mock("@/components/ui/sonner", () => ({
  Toaster: () => <div data-testid="toaster" />,
}));
describe("main.tsx", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    mocks.createRoot.mockReturnValue({ render: mocks.render });
    document.body.innerHTML = "";
  });

  it("mounts the dashboard into the required root element", async () => {
    const root = document.createElement("div");
    root.id = "root";
    document.body.append(root);

    await import("./main.tsx");

    expect(mocks.createRoot).toHaveBeenCalledWith(root);
    expect(mocks.render).toHaveBeenCalledOnce();
  });

  it("fails fast with an actionable error when the root is missing", async () => {
    await expect(import("./main.tsx")).rejects.toThrow(
      "Dashboard root element #root was not found",
    );

    expect(mocks.createRoot).not.toHaveBeenCalled();
  });
});
