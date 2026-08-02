import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RecoverableType } from "@ahoo-wang/fetcher-wow";
import { MarkRecoverable } from "../MarkRecoverable.tsx";

const mocks = vi.hoisted(() => ({
  abortController: new AbortController(),
  markRecoverable: vi.fn().mockResolvedValue({}),
  hookOptions: undefined as unknown,
  toastError: vi.fn(),
}));

vi.mock("@ahoo-wang/fetcher-react", () => ({
  useExecutePromise: (options: unknown) => {
    mocks.hookOptions = options;
    return {
      execute: (factory: (controller: AbortController) => Promise<unknown>) =>
        factory(mocks.abortController),
      loading: false,
    };
  },
}));

vi.mock("../../../services", () => ({
  executionFailedCommandClient: { markRecoverable: mocks.markRecoverable },
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: mocks.toastError },
}));

describe("MarkRecoverable", () => {
  beforeEach(() => vi.clearAllMocks());

  it("renders a controlled recoverability selector", () => {
    render(
      <MarkRecoverable
        id="test-id"
        recoverable={RecoverableType.UNRECOVERABLE}
      />,
    );

    expect(
      screen.getByRole("combobox", { name: "Recoverable" }),
    ).toHaveTextContent("Unrecoverable");
  });

  it("requires confirmation before changing recoverability", async () => {
    render(
      <MarkRecoverable
        id="test-id"
        recoverable={RecoverableType.RECOVERABLE}
      />,
    );

    fireEvent.click(screen.getByRole("combobox", { name: "Recoverable" }));
    fireEvent.click(
      await screen.findByRole("option", { name: "Unrecoverable" }),
    );

    expect(mocks.markRecoverable).not.toHaveBeenCalled();
    expect(
      screen.getByRole("alertdialog", { name: "Change recoverability?" }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm change" }));

    await waitFor(() => {
      expect(mocks.markRecoverable).toHaveBeenCalledWith("test-id", {
        body: { recoverable: RecoverableType.UNRECOVERABLE },
        abortController: mocks.abortController,
      });
    });
    expect(
      screen.getByRole("alertdialog", { name: "Change recoverability?" }),
    ).toBeInTheDocument();
  });

  it("keeps the proposed value available when the command is rejected", async () => {
    render(
      <MarkRecoverable
        id="test-id"
        recoverable={RecoverableType.RECOVERABLE}
      />,
    );

    fireEvent.click(screen.getByRole("combobox", { name: "Recoverable" }));
    fireEvent.click(
      await screen.findByRole("option", { name: "Unrecoverable" }),
    );
    const error = {
      message: "transport failed",
      exchange: {
        extractResult: vi
          .fn()
          .mockResolvedValue({ errorMsg: "command rejected" }),
      },
    };
    await (
      mocks.hookOptions as { onError: (error: unknown) => Promise<void> }
    ).onError(error);

    expect(mocks.toastError).toHaveBeenCalledWith(
      "Failed to update recoverability",
      { description: "command rejected" },
    );
    expect(
      screen.getByRole("alertdialog", { name: "Change recoverability?" }),
    ).toBeInTheDocument();
  });
});
