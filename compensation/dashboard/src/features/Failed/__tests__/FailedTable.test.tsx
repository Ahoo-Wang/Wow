import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../generated";
import { FailedTable } from "../FailedTable.tsx";

function failedState(id: string): ExecutionFailedState {
  return {
    id,
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
      id: `event-${id}`,
      version: 1,
      aggregateId: {
        aggregateName: "payment",
        contextName: "billing",
        aggregateId: `aggregate-${id}`,
        tenantId: "tenant-alpha",
      },
    },
    executeAt: Date.now() - 60_000,
    function: {
      contextName: "billing",
      processorName: "billing-orchestrator",
      name: "onPaymentAuthorized",
      functionKind: FunctionKind.EVENT,
    },
    retrySpec: { maxRetries: 10, minBackoff: 180, executionTimeout: 120 },
    retryState: {
      nextRetryAt: Date.now() + 60_000,
      retries: 3,
      retryAt: Date.now(),
      timeoutAt: Date.now() + 120_000,
    },
    isBelowRetryThreshold: true,
    isRetryable: true,
  };
}

describe("FailedTable", () => {
  it("renders selected execution context and paged totals", () => {
    render(
      <FailedTable
        pagedList={{ total: 22, list: [failedState("failed-11")] }}
        pageIndex={2}
        pageSize={10}
        selectedId="failed-11"
      />,
    );

    expect(screen.getByRole("row", { name: /failed-11/ })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByText("11–11 of 22")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Page 2" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("supports row selection and exposes a named execution action", () => {
    const onSelect = vi.fn();
    const state = failedState("failed-1");
    render(
      <FailedTable
        pagedList={{ total: 1, list: [state] }}
        pageIndex={1}
        pageSize={10}
        onSelect={onSelect}
      />,
    );

    const row = screen.getByRole("row", { name: /failed-1/ });
    fireEvent.click(row);
    fireEvent.click(
      screen.getByRole("button", { name: "View execution failed-1" }),
    );

    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(onSelect).toHaveBeenLastCalledWith(state);
  });

  it("emits bounded pagination changes", () => {
    const onPaginationChange = vi.fn();
    render(
      <FailedTable
        pagedList={{ total: 30, list: [failedState("failed-11")] }}
        pageIndex={2}
        pageSize={10}
        onPaginationChange={onPaginationChange}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Previous page" }));
    fireEvent.click(screen.getByRole("button", { name: "Page 3" }));
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));

    expect(onPaginationChange).toHaveBeenNthCalledWith(1, 1, 10);
    expect(onPaginationChange).toHaveBeenNthCalledWith(2, 3, 10);
    expect(onPaginationChange).toHaveBeenNthCalledWith(3, 3, 10);
  });

  it("keeps settled rows visible but non-interactive during page loading", () => {
    const onPaginationChange = vi.fn();
    const onSelect = vi.fn();
    render(
      <FailedTable
        loading
        pagedList={{ total: 30, list: [failedState("failed-11")] }}
        pageIndex={2}
        pageSize={10}
        onPaginationChange={onPaginationChange}
        onSelect={onSelect}
      />,
    );

    expect(screen.getByRole("row", { name: /failed-11/ })).toBeInTheDocument();
    expect(
      screen.getByRole("status", { name: "Loading page" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "View execution failed-11" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Previous page" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();

    fireEvent.click(screen.getByRole("row", { name: /failed-11/ }));
    expect(onSelect).not.toHaveBeenCalled();
    expect(onPaginationChange).not.toHaveBeenCalled();
  });

  it("renders a useful empty state", () => {
    const onClearFilters = vi.fn();
    render(
      <FailedTable
        hasActiveFilters
        onClearFilters={onClearFilters}
        pagedList={{ total: 0, list: [] }}
        pageIndex={1}
        pageSize={10}
      />,
    );

    expect(screen.getByText("No failed executions found")).toBeInTheDocument();
    expect(screen.getByText("0–0 of 0")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Page 1" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Previous page" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Clear search filters" }),
    );
    expect(onClearFilters).toHaveBeenCalledOnce();
  });

  it("supports page jumps and page-size changes without crowding the footer", () => {
    const onPaginationChange = vi.fn();
    render(
      <FailedTable
        pagedList={{ total: 100, list: [failedState("failed-1")] }}
        pageIndex={1}
        pageSize={10}
        onPaginationChange={onPaginationChange}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Pagination options" }));
    fireEvent.change(screen.getByRole("spinbutton", { name: "Go to page" }), {
      target: { value: "7" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Go" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Rows per page" }), {
      target: { value: "20" },
    });

    expect(onPaginationChange).toHaveBeenNthCalledWith(1, 7, 10);
    expect(onPaginationChange).toHaveBeenNthCalledWith(2, 1, 20);
  });

  it("keeps request failures distinct from a successful empty result", () => {
    const onRetry = vi.fn();
    render(
      <FailedTable
        error={new Error("network unavailable")}
        pagedList={{ total: 0, list: [] }}
        pageIndex={1}
        pageSize={10}
        onRetry={onRetry}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("network unavailable");
    expect(
      screen.queryByText("No failed executions found"),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("keeps last-known-good rows visible for a stale refresh error", () => {
    const onRetry = vi.fn();
    render(
      <FailedTable
        staleError={new Error("network unavailable")}
        pagedList={{ total: 1, list: [failedState("failed-1")] }}
        pageIndex={1}
        pageSize={10}
        onRetry={onRetry}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Showing the last loaded page",
    );
    expect(screen.getByRole("row", { name: /failed-1/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
