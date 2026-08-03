import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApplyRetrySpec } from "../ApplyRetrySpec.tsx";
import { TooltipProvider } from "@/components/ui/tooltip";

const mocks = vi.hoisted(() => ({
  applyRetrySpec: vi.fn().mockResolvedValue({}),
  closeDrawer: vi.fn(),
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
  writeText: vi.fn(),
}));

vi.mock("../../../components/GlobalDrawer", () => ({
  useGlobalDrawer: () => ({ closeDrawer: mocks.closeDrawer }),
}));

vi.mock("../../../services", () => ({
  executionFailedCommandClient: {
    applyRetrySpec: mocks.applyRetrySpec,
  },
}));

vi.mock("sonner", () => ({
  toast: { success: mocks.toastSuccess, error: mocks.toastError },
}));

function renderForm(onChanged = vi.fn()) {
  return render(
    <TooltipProvider>
      <ApplyRetrySpec
        id="test-id"
        retrySpec={{ maxRetries: 3, minBackoff: 180, executionTimeout: 120 }}
        onChanged={onChanged}
      />
    </TooltipProvider>,
  );
}

describe("ApplyRetrySpec", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.applyRetrySpec.mockResolvedValue({});
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: mocks.writeText },
    });
  });

  it("keeps the unchanged form disabled and makes the execution ID copyable", () => {
    renderForm();

    expect(
      screen.getByRole("button", { name: "Apply retry spec" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("textbox", { name: "Execution ID" }),
    ).not.toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Copy execution ID" }),
    ).toBeInTheDocument();
    expect(screen.getByText("3 minutes")).toBeInTheDocument();
    expect(screen.getByText("2 minutes")).toBeInTheDocument();
  });

  it("submits the generated command contract with id and body separated", async () => {
    const onChanged = vi.fn();
    renderForm(onChanged);

    fireEvent.change(screen.getByLabelText("Max retries"), {
      target: { value: "5" },
    });
    fireEvent.change(screen.getByLabelText("Min backoff (s)"), {
      target: { value: "240" },
    });
    fireEvent.change(screen.getByLabelText("Execution timeout (s)"), {
      target: { value: "300" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Apply retry spec" }));

    await waitFor(() => {
      expect(mocks.applyRetrySpec).toHaveBeenCalledWith("test-id", {
        body: {
          maxRetries: 5,
          minBackoff: 240,
          executionTimeout: 300,
        },
        abortController: expect.any(AbortController),
      });
    });
    expect(mocks.toastSuccess).toHaveBeenCalledWith(
      "Retry specification updated",
    );
    expect(mocks.closeDrawer).toHaveBeenCalledOnce();
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it("rejects values outside the generated int32 contract", () => {
    renderForm();

    const submit = screen.getByRole("button", { name: "Apply retry spec" });
    fireEvent.change(screen.getByLabelText("Max retries"), {
      target: { value: String(2_147_483_648) },
    });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Max retries"), {
      target: { value: "1.5" },
    });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText("Max retries"), {
      target: { value: "-1" },
    });
    expect(submit).toBeDisabled();
  });

  it("keeps the draft open and reports a rejected command", async () => {
    mocks.applyRetrySpec.mockRejectedValueOnce({
      message: "transport failed",
      exchange: {
        extractResult: vi.fn().mockResolvedValue({ errorMsg: "invalid spec" }),
      },
    });
    const onChanged = vi.fn();
    renderForm(onChanged);
    fireEvent.change(screen.getByLabelText("Max retries"), {
      target: { value: "5" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Apply retry spec" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Failed to apply retry specification",
        { description: "invalid spec" },
      );
    });
    expect(mocks.closeDrawer).not.toHaveBeenCalled();
    expect(onChanged).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Max retries")).toHaveValue(5);
  });
});
