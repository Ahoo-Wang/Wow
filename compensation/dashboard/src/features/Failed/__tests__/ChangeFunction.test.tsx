import React from "react";
import { describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { ChangeFunction } from "../ChangeFunction.tsx";
import { FunctionKind } from "@ahoo-wang/fetcher-wow";

// Mock dependencies
vi.mock("../../components/GlobalDrawer/useGlobalDrawer", () => ({
  useGlobalDrawer: () => ({ closeDrawer: vi.fn() }),
}));

vi.mock("../../services", () => ({
  executionFailedCommandClient: {
    changeFunction: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock("../../utils/useExecutePromise.ts", () => ({
  useExecutePromise: vi.fn(() => ({
    execute: vi.fn(),
    state: { loading: false },
  })),
}));

vi.mock("antd", () => {
  const MockForm = ({ children }: { children?: React.ReactNode }) => (
    <div>{children}</div>
  );
  MockForm.useForm = () => [{}];
  MockForm.Item = ({ children }: { children?: React.ReactNode }) => (
    <div>{children}</div>
  );
  return {
    Form: MockForm,
    Select: () => <select data-testid="select" />,
    Input: () => <input data-testid="input" />,
    Button: ({ children }: { children?: React.ReactNode }) => (
      <button>{children}</button>
    ),
    Space: ({ children }: { children?: React.ReactNode }) => (
      <div>{children}</div>
    ),
    App: {
      useApp: () => ({ notification: { success: vi.fn(), error: vi.fn() } }),
    },
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

describe("ChangeFunction", () => {
  it("renders form with select and input", () => {
    const { getByTestId, getAllByTestId } = render(
      <ChangeFunction
        id="test-id"
        functionInfo={{
          contextName: "test",
          processorName: "test",
          name: "test",
          functionKind: FunctionKind.EVENT,
        }}
        onChanged={vi.fn()}
      />,
    );
    expect(getByTestId("select")).toBeInTheDocument();
    expect(getAllByTestId("input")).toHaveLength(4);
  });
});