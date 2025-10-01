import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { Actions } from "../Actions.tsx";


// Mock all dependencies
vi.mock("@ahoo-wang/fetcher-wow", () => ({
  FunctionKind: { COMPENSATION: "COMPENSATION" },
  RecoverableType: { RECOVERABLE: "RECOVERABLE" },
  ResourceAttributionPathSpec: { NONE: "NONE" },
  QueryClientFactory: class {
    createSnapshotQueryClient() {
      return {};
    }
  },
}));
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
    status: "FAILED" as any,
    recoverable: "UNRECOVERABLE" as any,
    error: {
      errorCode: "TEST_ERROR",
      errorMsg: "Test error",
      stackTrace: "stack trace",
      succeeded: false,
      bindingErrors: [],
    },
    eventId: {
      id: "event-id",
      version: 1,
      aggregateId: {
        aggregateName: "agg-name",
        contextName: "context",
        aggregateId: "agg-id",
        tenantId: "tenant",
      },
    },
    executeAt: Date.now(),
    function: {
      contextName: "context",
      processorName: "processor",
      name: "function",
      functionKind: "COMPENSATION" as any,
    },
    retrySpec: {
      maxRetries: 3,
      minBackoff: 1000,
      executionTimeout: 30000,
    },
    retryState: {
      nextRetryAt: Date.now(),
      retries: 0,
      retryAt: Date.now(),
      timeoutAt: Date.now(),
    },
    isBelowRetryThreshold: false,
    isRetryable: true,
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