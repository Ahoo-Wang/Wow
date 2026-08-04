import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../../generated";
import { FailedDetails } from "../FailedDetails.tsx";
import { TooltipProvider } from "@/components/ui/tooltip";

const mocks = vi.hoisted(() => ({
  openDrawer: vi.fn(),
}));

vi.mock("@/components/GlobalDrawer", () => ({
  useGlobalDrawer: () => ({ openDrawer: mocks.openDrawer }),
}));

vi.mock("../../Actions.tsx", () => ({
  Actions: () => <div>Actions</div>,
}));

vi.mock("../../MarkRecoverable.tsx", () => ({
  MarkRecoverable: ({ recoverable }: { recoverable: string }) => (
    <span>{recoverable.charAt(0) + recoverable.slice(1).toLowerCase()}</span>
  ),
}));

vi.mock("../ErrorDetails.tsx", () => ({
  ErrorDetails: ({
    error,
    historical,
    defaultExpanded,
  }: {
    error: { errorCode: string };
    historical?: boolean;
    defaultExpanded?: boolean;
  }) => (
    <div role="region" aria-label="Stack trace">
      Error: {error.errorCode}
      {historical ? " (historical)" : ""}
      {defaultExpanded === false ? " (collapsed)" : ""}
    </div>
  ),
}));

vi.mock("../../history/ExecutionHistory.tsx", () => ({
  ExecutionHistory: ({ executionId }: { executionId: string }) => (
    <div role="region" aria-label="Execution history">
      History: {executionId}
    </div>
  ),
}));

const state: ExecutionFailedState = {
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
  retrySpec: { maxRetries: 3, minBackoff: 180, executionTimeout: 120 },
  retryState: {
    nextRetryAt: Date.now(),
    retries: 0,
    retryAt: Date.now(),
    timeoutAt: Date.now(),
  },
  isBelowRetryThreshold: false,
  isRetryable: true,
};

function renderDetails(currentState: ExecutionFailedState = state) {
  return render(
    <TooltipProvider>
      <FailedDetails state={currentState} />
    </TooltipProvider>,
  );
}

describe("FailedDetails", () => {
  beforeEach(() => {
    mocks.openDrawer.mockClear();
  });

  it("orders content by operational importance", () => {
    renderDetails();

    const failureSummary = screen.getByRole("region", {
      name: "Failure summary",
    });
    const recoveryStatus = screen.getByRole("region", {
      name: "Recovery status",
    });
    const history = screen.getByRole("region", {
      name: "Execution history",
    });
    const executionContext = screen.getByRole("region", {
      name: "Execution context",
    });
    const stackTrace = screen.getByRole("region", { name: "Stack trace" });

    expect(within(failureSummary).getByText("TEST_ERROR")).toBeInTheDocument();
    expect(within(failureSummary).getByText("Test error")).toBeInTheDocument();
    expect(
      failureSummary.compareDocumentPosition(recoveryStatus) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      recoveryStatus.compareDocumentPosition(history) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      history.compareDocumentPosition(executionContext) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      executionContext.compareDocumentPosition(stackTrace) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(stackTrace).toHaveTextContent("(collapsed)");
  });

  it("renders the selected execution history in the detail flow", () => {
    renderDetails();

    expect(screen.getByText("History: test-id")).toBeInTheDocument();
  });

  it("keeps edit flows owned by their corresponding detail sections", () => {
    renderDetails();

    fireEvent.click(
      screen.getByRole("button", { name: "Edit retry specification" }),
    );
    expect(mocks.openDrawer).toHaveBeenLastCalledWith(
      expect.objectContaining({
        title: "Apply retry specification",
        width: 440,
      }),
    );

    fireEvent.click(screen.getByRole("button", { name: "Edit function" }));
    expect(mocks.openDrawer).toHaveBeenLastCalledWith(
      expect.objectContaining({
        title: "Change function",
        width: 500,
      }),
    );
  });

  it("renders execution timestamps in the browser local time", () => {
    renderDetails({
      ...state,
      executeAt: new Date(2026, 7, 2, 20, 30, 40).getTime(),
      retryState: {
        ...state.retryState,
        retryAt: new Date(2026, 7, 2, 19, 20, 30).getTime(),
        nextRetryAt: new Date(2026, 7, 2, 21, 40, 50).getTime(),
      },
    });

    expect(screen.getByText("2026-08-02 20:30:40")).toBeInTheDocument();
    expect(screen.getByText("Last: 2026-08-02 19:20:30")).toBeInTheDocument();
    expect(screen.getByText("2026-08-02 21:40:50")).toBeInTheDocument();
    expect(screen.queryByText(/ UTC$/)).not.toBeInTheDocument();
  });

  it("renders the selected execution context", () => {
    renderDetails();

    expect(
      screen.getByRole("heading", { name: "function" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("processor")).toBeInTheDocument();
    expect(screen.getByText("context / EVENT")).toBeInTheDocument();
    expect(screen.getByText("context / agg-name")).toBeInTheDocument();
    const eventVersionLabel = screen.getByText("Event version");
    expect(eventVersionLabel.nextElementSibling).toHaveTextContent(/^1$/);
    expect(screen.queryByText("v1")).not.toBeInTheDocument();
    expect(screen.getByText(/Last:/)).toBeInTheDocument();
    expect(screen.getByText("Yes")).toBeInTheDocument();
    expect(
      screen.getByText("Error: TEST_ERROR (collapsed)"),
    ).toBeInTheDocument();
  });

  it("renders tenant as read-only identity information", () => {
    renderDetails();

    expect(screen.getByText("tenant")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /tenant/i }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("textbox", { name: /tenant/i }),
    ).not.toBeInTheDocument();
  });

  it("renders the other status and recoverability labels", () => {
    const { rerender } = renderDetails({
      ...state,
      status: ExecutionFailedStatus.PREPARED,
      recoverable: RecoverableType.UNKNOWN,
    });
    expect(screen.getByText("Prepared")).toBeInTheDocument();
    expect(screen.getByText("Unknown")).toBeInTheDocument();

    rerender(
      <TooltipProvider>
        <FailedDetails
          state={{
            ...state,
            status: ExecutionFailedStatus.SUCCEEDED,
            recoverable: RecoverableType.UNRECOVERABLE,
          }}
        />
      </TooltipProvider>,
    );
    expect(screen.getByText("Succeeded")).toBeInTheDocument();
    expect(screen.getByText("Unrecoverable")).toBeInTheDocument();
    expect(
      screen.getByText("Error: TEST_ERROR (historical)"),
    ).toBeInTheDocument();
  });
});
