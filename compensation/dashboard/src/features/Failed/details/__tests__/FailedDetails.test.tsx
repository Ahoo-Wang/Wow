import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { FailedDetails } from "../FailedDetails.tsx";


// Mock dependencies
vi.mock("@ahoo-wang/fetcher-wow", () => ({
  FunctionKind: { COMPENSATION: "COMPENSATION" },
  RecoverableType: { RECOVERABLE: "RECOVERABLE" },
}));
vi.mock("antd", () => ({
  Card: ({ children, title }: any) => <div data-testid="card">{title}{children}</div>,
  Descriptions: ({ items }: any) => <div>{items?.length} items</div>,
  Tag: () => <span>Tag</span>,
  Typography: { Text: ({ children }: any) => <span>{children}</span> },
  Statistic: { Countdown: () => <div>Countdown</div> },
  Flex: ({ children }: any) => <div data-testid="flex">{children}</div>,
}));

vi.mock("../../utils/dates.ts", () => ({
  formatDate: vi.fn(() => "formatted date"),
}));

vi.mock("dayjs", () => ({
  default: vi.fn(() => ({ format: vi.fn(() => "formatted date"), fromNow: () => "2 hours ago" })),
}));

describe("FailedDetails", () => {
  const mockState = {
    id: "test-id",
    status: "FAILED" as any,
    recoverable: "UNRECOVERABLE" as any,
    eventId: {
      id: "event-id",
      version: 1,
      aggregateId: {
        aggregateName: "agg-name",
        contextName: "test-context",
        aggregateId: "agg-id",
        tenantId: "tenant",
      },
    },
    retryState: {
      nextRetryAt: Date.now(),
      retries: 1,
      retryAt: Date.now(),
      timeoutAt: Date.now(),
    },
    retrySpec: { maxRetries: 3, minBackoff: 1000, executionTimeout: 30000 },
    error: {
      errorCode: "TEST_ERROR",
      errorMsg: "Test error",
      stackTrace: "stack trace",
      succeeded: false,
      bindingErrors: [],
    },
    function: { contextName: "test-context", processorName: "test-processor", name: "test-name", functionKind: "COMPENSATION" as any },
    executeAt: Date.now(),
    isBelowRetryThreshold: false,
    isRetryable: true,
  };

  it("renders flex with state information", () => {
    const { getByTestId } = render(<FailedDetails state={mockState} />);
    expect(getByTestId("flex")).toBeInTheDocument();
  });
});