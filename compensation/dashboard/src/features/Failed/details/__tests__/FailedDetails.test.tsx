import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { FailedDetails } from "../FailedDetails.tsx";

// Mock dependencies
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
  default: vi.fn(() => ({ fromNow: () => "2 hours ago" })),
}));

describe("FailedDetails", () => {
  const mockState = {
    id: "test-id",
    status: "FAILED",
    recoverable: "RECOVERABLE",
    eventId: { id: "event-id", aggregateId: { contextName: "test-context" } },
    retryState: { nextRetryAt: Date.now(), retries: 1 },
    retrySpec: { maxRetries: 3, minBackoff: 1000, executionTimeout: 30000 },
    error: { errorCode: "TEST_ERROR", message: "Test error" },
    function: { contextName: "test-context", processorName: "test-processor", name: "test-name", functionKind: "test-kind" },
  };

  it("renders flex with state information", () => {
    const { getByTestId } = render(<FailedDetails state={mockState} />);
    expect(getByTestId("flex")).toBeInTheDocument();
  });
});