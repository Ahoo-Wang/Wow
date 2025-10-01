import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { MarkRecoverable } from "../MarkRecoverable.tsx";

// Mock dependencies
vi.mock("../../services", () => ({
  executionFailedCommandClient: {
    markRecoverable: vi.fn().mockResolvedValue({}),
    markUnrecoverable: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock("../../utils/useExecutePromise.ts", () => ({
  useExecutePromise: vi.fn(() => ({
    execute: vi.fn(),
    state: { loading: false },
  })),
}));

vi.mock("antd", () => ({
  Button: ({ children }: any) => <button data-testid="button">{children}</button>,
  Select: () => <select data-testid="select" />,
  Space: ({ children }: any) => <div>{children}</div>,
  App: { useApp: () => ({ notification: { success: vi.fn(), error: vi.fn() } }) },
}));

describe("MarkRecoverable", () => {
  it("renders select for recoverable actions", () => {
    const { getByTestId } = render(
      <MarkRecoverable
        id="test-id"
        recoverable="RECOVERABLE"
        onChanged={vi.fn()}
      />
    );
    expect(getByTestId("select")).toBeInTheDocument();
  });
});