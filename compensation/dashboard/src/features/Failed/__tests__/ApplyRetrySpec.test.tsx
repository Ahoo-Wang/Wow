import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { ApplyRetrySpec } from "../ApplyRetrySpec.tsx";

// Mock dependencies
vi.mock("../../components/GlobalDrawer/useGlobalDrawer", () => ({
  useGlobalDrawer: () => ({ closeDrawer: vi.fn() }),
}));

vi.mock("../../services", () => ({
  executionFailedCommandClient: {
    applyRetrySpec: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock("../../utils/useExecutePromise.ts", () => ({
  useExecutePromise: vi.fn(() => ({
    execute: vi.fn(),
    state: { loading: false },
  })),
}));

vi.mock("antd", () => {
  const MockForm = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  MockForm.useForm = () => [{}];
  MockForm.Item = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  return {
    Form: MockForm,
    Input: () => <input data-testid="input" />,
    InputNumber: () => <input data-testid="input-number" />,
    Button: ({ children }: { children?: React.ReactNode }) => <button>{children}</button>,
    Space: ({ children }: { children?: React.ReactNode }) => <div>{children}</div>,
    App: { useApp: () => ({ notification: { info: vi.fn(), error: vi.fn() } }) },
  };
});

vi.mock("react", async () => {
  const actual = await vi.importActual("react");
  return {
    ...actual,
    useEffect: vi.fn(),
    useContext: vi.fn(() => ({ closeDrawer: vi.fn() })),
  };
});

describe("ApplyRetrySpec", () => {
  it("renders form with inputs", () => {
    const { getAllByTestId } = render(
      <ApplyRetrySpec
        id="test-id"
        retrySpec={{ maxRetries: 3, minBackoff: 1000, executionTimeout: 30000 }}
        onChanged={vi.fn()}
      />
    );
    expect(getAllByTestId("input-number")).toHaveLength(3);
  });
});