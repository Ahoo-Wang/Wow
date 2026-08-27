import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RecoverableType } from "@ahoo-wang/fetcher-wow";
import { MarkRecoverable } from "../MarkRecoverable.tsx";

const mocks = vi.hoisted(() => ({
  markRecoverable: vi.fn().mockResolvedValue({}),
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
}));

vi.mock("../../../services", () => ({
  executionFailedCommandClient: { markRecoverable: mocks.markRecoverable },
}));

vi.mock("sonner", () => ({
  toast: { success: mocks.toastSuccess, error: mocks.toastError },
}));

describe("MarkRecoverable", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.markRecoverable.mockResolvedValue({});
  });

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
    const onChanged = vi.fn();
    render(
      <MarkRecoverable
        id="test-id"
        recoverable={RecoverableType.RECOVERABLE}
        onChanged={onChanged}
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
        abortController: expect.any(AbortController),
      });
    });
    await waitFor(() => {
      expect(
        screen.queryByRole("alertdialog", { name: "Change recoverability?" }),
      ).not.toBeInTheDocument();
    });
    expect(mocks.toastSuccess).toHaveBeenCalledWith("Recoverability updated");
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it("keeps the proposed value available when the command is rejected", async () => {
    mocks.markRecoverable.mockRejectedValueOnce({
      message: "transport failed",
      exchange: {
        extractResult: vi
          .fn()
          .mockResolvedValue({ errorMsg: "command rejected" }),
      },
    });
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
    fireEvent.click(screen.getByRole("button", { name: "Confirm change" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Failed to update recoverability",
        { description: "command rejected" },
      );
    });
    expect(
      screen.getByRole("alertdialog", { name: "Change recoverability?" }),
    ).toBeInTheDocument();
  });
});
