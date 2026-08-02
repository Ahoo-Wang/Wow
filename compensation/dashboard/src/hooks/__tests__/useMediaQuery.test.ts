import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useMediaQuery } from "../useMediaQuery.ts";

describe("useMediaQuery", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("subscribes to modern media-query changes and removes the listener", () => {
    let matches = false;
    let listener: (() => void) | undefined;
    const addEventListener = vi.fn(
      (_event: string, nextListener: () => void) => {
        listener = nextListener;
      },
    );
    const removeEventListener = vi.fn();

    vi.spyOn(window, "matchMedia").mockImplementation(
      (query) =>
        ({
          addEventListener,
          dispatchEvent: vi.fn(),
          get matches() {
            return matches;
          },
          media: query,
          onchange: null,
          removeEventListener,
        }) as unknown as MediaQueryList,
    );

    const { result, unmount } = renderHook(() =>
      useMediaQuery("(min-width: 960px)"),
    );

    expect(result.current).toBe(false);
    expect(addEventListener).toHaveBeenCalledWith(
      "change",
      expect.any(Function),
    );

    act(() => {
      matches = true;
      listener?.();
    });

    expect(result.current).toBe(true);

    unmount();
    expect(removeEventListener).toHaveBeenCalledWith(
      "change",
      expect.any(Function),
    );
  });
});
