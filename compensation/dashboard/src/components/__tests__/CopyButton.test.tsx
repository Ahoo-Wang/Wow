import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CopyButton } from "../CopyButton.tsx";
import { TooltipProvider } from "@/components/ui/tooltip";

const mocks = vi.hoisted(() => ({
  toastError: vi.fn(),
  writeText: vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: { error: mocks.toastError },
}));

function renderCopyButton() {
  return render(
    <TooltipProvider>
      <CopyButton value="execution-1" label="execution ID" />
    </TooltipProvider>,
  );
}

describe("CopyButton", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: mocks.writeText },
    });
  });

  it("copies the requested value", async () => {
    mocks.writeText.mockResolvedValue(undefined);
    renderCopyButton();

    fireEvent.click(screen.getByRole("button", { name: "Copy execution ID" }));

    await waitFor(() => {
      expect(mocks.writeText).toHaveBeenCalledWith("execution-1");
    });
    expect(mocks.toastError).not.toHaveBeenCalled();
  });

  it("reports clipboard failures instead of leaking an unhandled rejection", async () => {
    mocks.writeText.mockRejectedValue(new Error("permission denied"));
    renderCopyButton();

    fireEvent.click(screen.getByRole("button", { name: "Copy execution ID" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Unable to copy execution ID",
      );
    });
  });
});
