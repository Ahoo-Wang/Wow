import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FunctionKind } from "@ahoo-wang/fetcher-wow";
import { ChangeFunction } from "../ChangeFunction.tsx";
import { TooltipProvider } from "@/components/ui/tooltip";

const mocks = vi.hoisted(() => ({
  changeFunction: vi.fn().mockResolvedValue({}),
  closeDrawer: vi.fn(),
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
  writeText: vi.fn(),
}));

vi.mock("@/components/GlobalDrawer", () => ({
  useGlobalDrawer: () => ({ closeDrawer: mocks.closeDrawer }),
}));

vi.mock("../../../services", () => ({
  executionFailedCommandClient: {
    changeFunction: mocks.changeFunction,
  },
}));

vi.mock("sonner", () => ({
  toast: { success: mocks.toastSuccess, error: mocks.toastError },
}));

function renderForm(onChanged = vi.fn()) {
  return render(
    <TooltipProvider>
      <ChangeFunction
        id="test-id"
        functionInfo={{
          contextName: "context",
          processorName: "processor",
          name: "handler",
          functionKind: FunctionKind.EVENT,
        }}
        onChanged={onChanged}
      />
    </TooltipProvider>,
  );
}

describe("ChangeFunction", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.changeFunction.mockResolvedValue({});
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: mocks.writeText },
    });
  });

  it("renders the editable function contract", () => {
    renderForm();

    expect(screen.getByLabelText("Context name")).toHaveValue("context");
    expect(screen.getByLabelText("Processor name")).toHaveValue("processor");
    expect(screen.getByLabelText("Function name")).toHaveValue("handler");
    expect(
      screen.getByRole("combobox", { name: "Function kind" }),
    ).toHaveTextContent("EVENT");
    expect(
      screen.getByRole("button", { name: "Save function" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("textbox", { name: "Execution ID" }),
    ).not.toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Copy execution ID" }),
    ).toBeInTheDocument();
  });

  it("trims and submits only a changed, valid function contract", async () => {
    const onChanged = vi.fn();
    renderForm(onChanged);

    fireEvent.change(screen.getByLabelText("Context name"), {
      target: { value: "  new-context  " },
    });
    fireEvent.change(screen.getByLabelText("Processor name"), {
      target: { value: "new-processor" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save function" }));

    await waitFor(() => {
      expect(mocks.changeFunction).toHaveBeenCalledWith("test-id", {
        body: {
          contextName: "new-context",
          processorName: "new-processor",
          name: "handler",
          functionKind: FunctionKind.EVENT,
        },
        abortController: expect.any(AbortController),
      });
    });
    expect(mocks.toastSuccess).toHaveBeenCalledWith("Function updated");
    expect(mocks.closeDrawer).toHaveBeenCalledOnce();
    expect(onChanged).toHaveBeenCalledOnce();
  });

  it("keeps the action disabled when a required value is blank", () => {
    renderForm();

    fireEvent.change(screen.getByLabelText("Function name"), {
      target: { value: "   " },
    });

    expect(
      screen.getByRole("button", { name: "Save function" }),
    ).toBeDisabled();
  });

  it("keeps the draft open and reports a rejected command", async () => {
    mocks.changeFunction.mockRejectedValueOnce({
      message: "transport failed",
      exchange: {
        extractResult: vi
          .fn()
          .mockResolvedValue({ errorMsg: "function rejected" }),
      },
    });
    const onChanged = vi.fn();
    renderForm(onChanged);
    fireEvent.change(screen.getByLabelText("Function name"), {
      target: { value: "new-handler" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save function" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Failed to change function",
        { description: "function rejected" },
      );
    });
    expect(mocks.closeDrawer).not.toHaveBeenCalled();
    expect(onChanged).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Function name")).toHaveValue("new-handler");
  });
});
