import { describe, it, expect, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { useQueryParams } from "./useQuery.ts";
import { MemoryRouter } from "react-router";

// Mock useLocation
const mockUseLocation = vi.fn();

vi.mock("react-router", async () => {
  const actual = await vi.importActual("react-router");
  return {
    ...actual,
    useLocation: () => mockUseLocation(),
  };
});

describe("useQueryParams", () => {
  it("returns URLSearchParams when no name provided", () => {
    mockUseLocation.mockReturnValue({
      search: "?key1=value1&key2=value2",
    });

    const { result } = renderHook(() => useQueryParams(), {
      wrapper: MemoryRouter,
    });

    expect(result.current).toBeInstanceOf(URLSearchParams);
    expect(result.current.get("key1")).toBe("value1");
    expect(result.current.get("key2")).toBe("value2");
  });

  it("returns specific query param value when name provided", () => {
    mockUseLocation.mockReturnValue({
      search: "?id=123&name=test",
    });

    const { result } = renderHook(() => useQueryParams("id"), {
      wrapper: MemoryRouter,
    });

    expect(result.current).toBe("123");
  });

  it("returns null when specific param not found", () => {
    mockUseLocation.mockReturnValue({
      search: "?other=value",
    });

    const { result } = renderHook(() => useQueryParams("missing"), {
      wrapper: MemoryRouter,
    });

    expect(result.current).toBeNull();
  });

  it("handles empty search string", () => {
    mockUseLocation.mockReturnValue({
      search: "",
    });

    const { result } = renderHook(() => useQueryParams(), {
      wrapper: MemoryRouter,
    });

    expect(result.current).toBeInstanceOf(URLSearchParams);
  });
});