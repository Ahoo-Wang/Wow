import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { Actions } from "../Actions.tsx";

// Mock all dependencies
vi.mock("antd", () => ({
  App: {
    useApp: () => ({ notification: { success: vi.fn(), error: vi.fn() } }),
  },
  Dropdown: {
    Button: ({ children, menu }: any) => <div data-testid="dropdown">{children}{menu?.items?.length} items</div>,
  },
  Typography: { Text: () => <span>Text</span> },
  Space: () => <div>Space</div>,
  Statistic: { Timer: () => <div>Timer</div> },
}));

// Mock dependencies
vi.mock("../../components/GlobalDrawer/useGlobalDrawer", () => ({
  useGlobalDrawer: () => ({ openDrawer: vi.fn() }),
}));

vi.mock("../../services", () => ({
  executionFailedCommandClient: {
    prepareCompensation: vi.fn().mockResolvedValue({}),
    markRecoverable: vi.fn().mockResolvedValue({}),
    markUnrecoverable: vi.fn().mockResolvedValue({}),
    applyRetrySpec: vi.fn().mockResolvedValue({}),
    changeFunction: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock("./details/FailedDetails.tsx", () => ({
  FailedDetails: () => <div>FailedDetails</div>,
}));

// Mock React hooks
vi.mock("react", async () => {
  const actual = await vi.importActual("react");
  return {
    ...actual,
    useState: vi.fn(() => [null, vi.fn()]),
    useContext: vi.fn(() => ({ openDrawer: vi.fn() })),
  };
});

describe("Actions", () => {
  const mockState = {
    id: "test-id",
    status: "FAILED",
    recoverable: "RECOVERABLE",
  };

  it("renders actions dropdown", () => {
    const { container } = render(<Actions state={mockState} />);
    expect(container.querySelector('[data-testid="dropdown"]')).toBeInTheDocument();
  });

  it("renders with onChanged callback", () => {
    const onChanged = vi.fn();
    const { container } = render(<Actions state={mockState} onChanged={onChanged} />);
    expect(container.querySelector('[data-testid="dropdown"]')).toBeInTheDocument();
  });
});