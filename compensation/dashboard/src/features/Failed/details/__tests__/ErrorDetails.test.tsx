import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ErrorDetails as ErrorDetailsModel } from "../../../../generated";
import { ErrorDetails } from "../ErrorDetails.tsx";
import { TooltipProvider } from "@/components/ui/tooltip";

const mocks = vi.hoisted(() => ({
  exitFullscreen: vi.fn(),
  requestFullscreen: vi.fn(),
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
  writeText: vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: { error: mocks.toastError, success: mocks.toastSuccess },
}));

const error: ErrorDetailsModel = {
  errorCode: "TEST_ERROR",
  errorMsg: "Test message",
  stackTrace: "line one\nline two",
  succeeded: false,
  bindingErrors: [],
};

function renderErrorDetails() {
  return render(
    <TooltipProvider>
      <ErrorDetails error={error} />
    </TooltipProvider>,
  );
}

describe("ErrorDetails", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: mocks.writeText },
    });
    Object.defineProperty(document, "exitFullscreen", {
      configurable: true,
      value: mocks.exitFullscreen,
    });
    Object.defineProperty(HTMLElement.prototype, "requestFullscreen", {
      configurable: true,
      value: mocks.requestFullscreen,
    });
  });

  it("renders the error summary and accessible stack trace", () => {
    renderErrorDetails();

    expect(screen.getByText("TEST_ERROR: Test message")).toBeInTheDocument();
    const stackTrace = screen.getByRole("region", {
      name: "Stack trace content",
    });
    expect(
      screen.getByText("line one", { selector: "code *" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("line two", { selector: "code *" }),
    ).toBeInTheDocument();
    expect(stackTrace.querySelector("pre > code")).toBeInTheDocument();
    expect(
      stackTrace.querySelectorAll(".react-syntax-highlighter-line-number"),
    ).toHaveLength(2);
  });

  it("copies the stack trace", async () => {
    mocks.writeText.mockResolvedValue(undefined);
    renderErrorDetails();

    fireEvent.click(screen.getByRole("button", { name: "Copy stack trace" }));

    await waitFor(() => {
      expect(mocks.writeText).toHaveBeenCalledWith("line one\nline two");
      expect(mocks.toastSuccess).toHaveBeenCalledWith("Stack trace copied");
    });
  });

  it("reports clipboard failures", async () => {
    mocks.writeText.mockRejectedValue(new Error("permission denied"));
    renderErrorDetails();

    fireEvent.click(screen.getByRole("button", { name: "Copy stack trace" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Unable to copy stack trace",
      );
    });
  });

  it("reports fullscreen failures", async () => {
    mocks.requestFullscreen.mockRejectedValue(new Error("fullscreen denied"));
    renderErrorDetails();

    fireEvent.click(screen.getByRole("button", { name: "Open fullscreen" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Unable to change fullscreen mode",
      );
    });
  });

  it("collapses a historical failure by default and can reveal it", () => {
    render(
      <TooltipProvider>
        <ErrorDetails error={error} historical />
      </TooltipProvider>,
    );

    expect(
      screen.getByRole("heading", { name: "Last failure" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("region", { name: "Stack trace content" }),
    ).not.toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: "Expand last failure" }),
    );
    expect(
      screen.getByRole("region", { name: "Stack trace content" }),
    ).toBeInTheDocument();
  });

  it("searches matching stack lines and toggles line wrapping", () => {
    renderErrorDetails();

    fireEvent.change(
      screen.getByRole("searchbox", { name: "Search stack trace" }),
      {
        target: { value: "line" },
      },
    );
    expect(screen.getByText("2 matches")).toBeInTheDocument();
    expect(
      document.querySelectorAll('[data-search-match="true"]'),
    ).toHaveLength(2);

    const wrap = screen.getByRole("button", { name: "Disable line wrapping" });
    fireEvent.click(wrap);
    expect(
      screen.getByRole("button", { name: "Enable line wrapping" }),
    ).toBeInTheDocument();
  });
});
