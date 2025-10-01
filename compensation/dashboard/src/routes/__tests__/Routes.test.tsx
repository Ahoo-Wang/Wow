import { describe, it, expect, vi } from "vitest";
import { AppRouter } from "../Routes.tsx";

// Mock all dependencies
vi.mock("react-router", () => ({
  createBrowserRouter: vi.fn(() => ({ router: "mocked" })),
  Navigate: () => <div>Navigate</div>,
}));

vi.mock("../../features/App/App.tsx", () => ({
  default: () => <div>App</div>,
}));

vi.mock("../constants.tsx", () => ({
  NavItems: [],
  NavItemPaths: {},
}));

describe("Routes", () => {
  it("creates a router", () => {
    expect(AppRouter).toBeDefined();
  });
});