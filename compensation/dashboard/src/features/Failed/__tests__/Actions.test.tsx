import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FunctionKind, RecoverableType } from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../generated";
import { Actions } from "../Actions.tsx";

const mocks = vi.hoisted(() => ({
  abortController: new AbortController(),
  forcePrepareCompensation: vi.fn().mockResolvedValue({}),
  forceLoading: false,
  hookCall: 0,
  prepareCompensation: vi.fn().mockResolvedValue({}),
  prepareLoading: false,
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
  writeText: vi.fn(),
}));

vi.mock("../../../services", () => ({
  executionFailedCommandClient: {
    forcePrepareCompensation: mocks.forcePrepareCompensation,
    prepareCompensation: mocks.prepareCompensation,
  },
}));

vi.mock("@ahoo-wang/fetcher-react", () => ({
  useExecutePromise: () => {
    const prepareState = mocks.hookCall++ % 2 === 0;
    return {
      execute: (factory: (controller: AbortController) => Promise<unknown>) =>
        factory(mocks.abortController),
      loading: prepareState ? mocks.prepareLoading : mocks.forceLoading,
    };
  },
}));

vi.mock("sonner", () => ({
  toast: { success: mocks.toastSuccess, error: mocks.toastError },
}));

const state: ExecutionFailedState = {
  id: "failed-1",
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
    id: "event-1",
    version: 1,
    aggregateId: {
      aggregateName: "payment",
      contextName: "billing",
      aggregateId: "payment-1",
      tenantId: "tenant-alpha",
    },
  },
  executeAt: Date.now(),
  function: {
    contextName: "billing",
    processorName: "billing-orchestrator",
    name: "onPaymentAuthorized",
    functionKind: FunctionKind.EVENT,
  },
  retrySpec: { maxRetries: 10, minBackoff: 1000, executionTimeout: 120000 },
  retryState: {
    nextRetryAt: Date.now() + 60_000,
    retries: 3,
    retryAt: Date.now(),
    timeoutAt: Date.now() + 120_000,
  },
  isBelowRetryThreshold: true,
  isRetryable: true,
};

describe("Actions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.forceLoading = false;
    mocks.hookCall = 0;
    mocks.prepareLoading = false;
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: mocks.writeText },
    });
  });

  it("requires confirmation before force prepare", async () => {
    render(<Actions state={state} />);

    fireEvent.pointerDown(
      screen.getByRole("button", { name: "More actions" }),
      {
        button: 0,
        ctrlKey: false,
      },
    );
    fireEvent.click(
      await screen.findByRole("menuitem", { name: "Force prepare" }),
    );

    expect(mocks.forcePrepareCompensation).not.toHaveBeenCalled();
    expect(
      screen.getByRole("alertdialog", {
        name: "Force prepare this execution?",
      }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Force prepare" }));

    await waitFor(() => {
      expect(mocks.forcePrepareCompensation).toHaveBeenCalledWith("failed-1", {
        abortController: mocks.abortController,
      });
    });
  });

  it("does not force prepare when confirmation is cancelled", async () => {
    render(<Actions state={state} />);

    fireEvent.pointerDown(
      screen.getByRole("button", { name: "More actions" }),
      {
        button: 0,
        ctrlKey: false,
      },
    );
    fireEvent.click(
      await screen.findByRole("menuitem", { name: "Force prepare" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(mocks.forcePrepareCompensation).not.toHaveBeenCalled();
  });

  it("prevents concurrent prepare and force-prepare commands", () => {
    mocks.prepareLoading = true;
    render(<Actions state={state} />);

    expect(
      screen.getByRole("button", { name: "Prepare compensation" }),
    ).toBeDisabled();
    expect(screen.getByRole("button", { name: "More actions" })).toBeDisabled();
  });

  it("explains and disables compensation actions after success", async () => {
    render(
      <Actions state={{ ...state, status: ExecutionFailedStatus.SUCCEEDED }} />,
    );

    expect(
      screen.getByRole("button", { name: "Already succeeded" }),
    ).toBeDisabled();
    expect(
      screen.getByText("This execution has already succeeded."),
    ).toBeInTheDocument();

    fireEvent.pointerDown(
      screen.getByRole("button", { name: "More actions" }),
      {
        button: 0,
        ctrlKey: false,
      },
    );
    expect(
      await screen.findByRole("menuitem", { name: "Force prepare" }),
    ).toHaveAttribute("data-disabled");
  });

  it("keeps force prepare available after the retry limit is reached", async () => {
    render(<Actions state={{ ...state, isBelowRetryThreshold: false }} />);

    expect(
      screen.getByRole("button", { name: "Retry limit reached" }),
    ).toBeDisabled();
    expect(
      screen.getByText("Retry limit reached; force prepare remains available."),
    ).toBeInTheDocument();

    fireEvent.pointerDown(
      screen.getByRole("button", { name: "More actions" }),
      {
        button: 0,
        ctrlKey: false,
      },
    );
    expect(
      await screen.findByRole("menuitem", { name: "Force prepare" }),
    ).not.toHaveAttribute("data-disabled");
  });

  it("reports execution ID copy failures", async () => {
    mocks.writeText.mockRejectedValue(new Error("permission denied"));
    render(<Actions state={state} />);

    fireEvent.pointerDown(
      screen.getByRole("button", { name: "More actions" }),
      {
        button: 0,
        ctrlKey: false,
      },
    );
    fireEvent.click(
      await screen.findByRole("menuitem", { name: "Copy execution ID" }),
    );

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Unable to copy execution ID",
      );
    });
    expect(mocks.toastSuccess).not.toHaveBeenCalledWith("Execution ID copied");
  });
});
