import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { MarkRecoverable } from "../MarkRecoverable.tsx";
import { RecoverableType } from "@ahoo-wang/fetcher-wow";

// Mock dependencies
vi.mock("@ahoo-wang/fetcher-wow", () => ({
  RecoverableType: {
    UNRECOVERABLE: "UNRECOVERABLE",
    RECOVERABLE: "RECOVERABLE",
  },
  ResourceAttributionPathSpec: { NONE: "NONE" },
  QueryClientFactory: class {
    createSnapshotQueryClient() {
      return {};
    }
  },
}));
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
  Button: ({ children }: { children?: React.ReactNode }) => (
    <button data-testid="button">{children}</button>
  ),
  Select: () => <select data-testid="select" />,
  Space: ({ children }: { children?: React.ReactNode }) => (
    <div>{children}</div>
  ),
  App: {
    useApp: () => ({ notification: { success: vi.fn(), error: vi.fn() } }),
  },
}));

describe("MarkRecoverable", () => {
  it("renders select for recoverable actions", () => {
    const { getByTestId } = render(
      <MarkRecoverable
        id="test-id"
        recoverable={RecoverableType.UNRECOVERABLE}
        onChanged={vi.fn()}
      />,
    );
    expect(getByTestId("select")).toBeInTheDocument();
  });
});
