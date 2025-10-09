import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { FailedDetails } from "../FailedDetails.tsx";
import {
  ExecutionFailedState,
  ExecutionFailedStatus,
} from "../../../../generated";
import {
  ErrorInfo,
  FunctionKind,
  RecoverableType,
} from "@ahoo-wang/fetcher-wow";

// Mock ErrorDetails component
vi.mock("../ErrorDetails.tsx", () => ({
  ErrorDetails: ({ error }: { error: ErrorInfo }) => (
    <div data-testid="error-details">Error: {error.errorCode}</div>
  ),
}));

// Mock formatDate utility
vi.mock("../../../../utils/dates.ts", () => ({
  formatDate: vi.fn((timestamp: number) => `formatted-${timestamp}`),
}));

describe("FailedDetails", () => {
  const mockState: ExecutionFailedState = {
    id: "test-id",
    status: ExecutionFailedStatus.FAILED,
    recoverable: RecoverableType.RECOVERABLE,
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
      functionKind: FunctionKind.EVENT,
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

  it("renders basic information correctly", () => {
    render(<FailedDetails state={mockState} />);

    // Check ID is displayed with copyable text
    expect(screen.getByText(mockState.id)).toBeInTheDocument();

    // Check recoverable status - look for the tag specifically
    const recoverableTags = screen.getAllByText("Recoverable");
    expect(recoverableTags.length).toBeGreaterThan(0);

    // Check isRetryable
    expect(screen.getByText("Yes")).toBeInTheDocument();
  });

  it("renders error details component", () => {
    render(<FailedDetails state={mockState} />);

    expect(screen.getByTestId("error-details")).toHaveTextContent(
      "Error: TEST_ERROR",
    );
  });

  it("handles different status values", () => {
    const preparedState = {
      ...mockState,
      status: ExecutionFailedStatus.PREPARED,
    };
    const { rerender } = render(<FailedDetails state={preparedState} />);

    expect(screen.getByText("Prepared")).toBeInTheDocument();

    const succeededState = {
      ...mockState,
      status: ExecutionFailedStatus.SUCCEEDED,
    };
    rerender(<FailedDetails state={succeededState} />);

    expect(screen.getByText("Succeeded")).toBeInTheDocument();
  });

  it("handles different recoverable values", () => {
    const unrecoverableState = {
      ...mockState,
      recoverable: RecoverableType.UNRECOVERABLE,
    };
    const { rerender } = render(<FailedDetails state={unrecoverableState} />);

    expect(screen.getByText("Unrecoverable")).toBeInTheDocument();

    const unknownState = { ...mockState, recoverable: RecoverableType.UNKNOWN };
    rerender(<FailedDetails state={unknownState} />);

    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });

  it("handles isRetryable false", () => {
    const nonRetryableState = { ...mockState, isRetryable: false };
    render(<FailedDetails state={nonRetryableState} />);

    expect(screen.getByText("No")).toBeInTheDocument();
  });

  it("renders all description sections", () => {
    render(<FailedDetails state={mockState} />);

    // Should have multiple Descriptions components (basic, function, eventId, retry)
    const descriptions = screen.getAllByRole("table"); // antd Descriptions renders as tables
    expect(descriptions.length).toBeGreaterThanOrEqual(4);
  });
});
