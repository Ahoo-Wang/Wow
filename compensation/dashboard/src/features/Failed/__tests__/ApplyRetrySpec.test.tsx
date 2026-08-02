import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApplyRetrySpec } from "../ApplyRetrySpec.tsx";
import { TooltipProvider } from "@/components/ui/tooltip";

const mocks = vi.hoisted(() => ({
  abortController: new AbortController(),
  applyRetrySpec: vi.fn().mockResolvedValue({}),
  closeDrawer: vi.fn(),
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

vi.mock("@ahoo-wang/fetcher-react", () => ({
  useExecutePromise: () => ({
    execute: (factory: (controller: AbortController) => Promise<unknown>) =>
      factory(mocks.abortController),
    loading: false,
  }),
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

function renderForm() {
  return render(
    <TooltipProvider>
      <ApplyRetrySpec
        id="test-id"
        retrySpec={{ maxRetries: 3, minBackoff: 1000, executionTimeout: 30000 }}
      />
    </TooltipProvider>,
  );
}

describe("ApplyRetrySpec", () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
    expect(screen.getByText("1 second")).toBeInTheDocument();
    expect(screen.getByText("30 seconds")).toBeInTheDocument();
  });

  it("submits the generated command contract with id and body separated", async () => {
    renderForm();

    fireEvent.change(screen.getByLabelText("Max retries"), {
      target: { value: "5" },
    });
    fireEvent.change(screen.getByLabelText("Min backoff (ms)"), {
      target: { value: "2000" },
    });
    fireEvent.change(screen.getByLabelText("Execution timeout (ms)"), {
      target: { value: "45000" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Apply retry spec" }));

    await waitFor(() => {
      expect(mocks.applyRetrySpec).toHaveBeenCalledWith("test-id", {
        body: {
          maxRetries: 5,
          minBackoff: 2000,
          executionTimeout: 45000,
        },
        abortController: mocks.abortController,
      });
    });
  });
});
