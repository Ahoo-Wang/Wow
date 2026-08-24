import { fireEvent, render, screen } from "@testing-library/react";
import { filter, singleQuery } from "@ahoo-wang/fetcher-wow";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ExecutionFailedState } from "../../../../generated";
import { FetchingFailedDetails } from "../FetchingFailedDetails.tsx";

const mocks = vi.hoisted(() => ({
  error: undefined as Error | undefined,
  execute: vi.fn(),
  hookOptions: undefined as unknown,
  loading: false,
  queryExecutionFailedState: vi.fn(),
  result: { id: "remote-A" } as ExecutionFailedState | null | undefined,
}));

vi.mock("@ahoo-wang/fetcher-react", () => ({
  useSingleQuery: (options: unknown) => {
    mocks.hookOptions = options;
    return {
      result: mocks.result,
      error: mocks.error,
      loading: mocks.loading,
      execute: mocks.execute,
    };
  },
}));

vi.mock("../../../../services", () => ({
  queryExecutionFailedState: mocks.queryExecutionFailedState,
}));

vi.mock("../FailedDetails.tsx", () => ({
  FailedDetails: ({
    state,
    onChanged,
  }: {
    state: ExecutionFailedState;
    onChanged?: () => void;
  }) => (
    <div>
      <span>Remote: {state.id}</span>
      <button onClick={onChanged}>Refresh selected execution</button>
    </div>
  ),
}));

describe("FetchingFailedDetails", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.error = undefined;
    mocks.loading = false;
    mocks.result = { id: "remote-A" } as ExecutionFailedState;
  });

  it("keeps the remote query controlled by the current deep-link id", () => {
    const { rerender } = render(<FetchingFailedDetails id="remote-A" />);

    expect((mocks.hookOptions as { query?: unknown }).query).toEqual(
      singleQuery({ filter: filter.aggregateId("remote-A") }),
    );
    expect((mocks.hookOptions as { execute?: unknown }).execute).toBe(
      mocks.queryExecutionFailedState,
    );

    mocks.result = { id: "remote-B" } as ExecutionFailedState;
    rerender(<FetchingFailedDetails id="remote-B" />);

    expect((mocks.hookOptions as { query?: unknown }).query).toEqual(
      singleQuery({ filter: filter.aggregateId("remote-B") }),
    );
    expect(screen.getByText("Remote: remote-B")).toBeInTheDocument();
  });

  it("never exposes stale execution actions while a new deep link loads", () => {
    const { rerender } = render(<FetchingFailedDetails id="remote-A" />);

    mocks.loading = true;
    rerender(<FetchingFailedDetails id="remote-B" />);

    expect(
      screen.getByRole("status", { name: "Loading execution details" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Remote: remote-A")).not.toBeInTheDocument();
  });

  it("refreshes both the remote detail and the parent list after a change", () => {
    const onChanged = vi.fn();
    render(<FetchingFailedDetails id="remote-A" onChanged={onChanged} />);

    fireEvent.click(
      screen.getByRole("button", { name: "Refresh selected execution" }),
    );

    expect(onChanged).toHaveBeenCalledOnce();
    expect(mocks.execute).toHaveBeenCalledOnce();
  });

  it("renders an explicit not-found state after an empty response", () => {
    mocks.result = null;

    render(<FetchingFailedDetails id="missing" />);

    expect(screen.getByText("Execution not found")).toBeInTheDocument();
    expect(screen.getByText(/missing/)).toBeInTheDocument();
  });

  it("offers a retry after loading the selected execution fails", () => {
    mocks.result = undefined;
    mocks.error = new Error("network unavailable");

    render(<FetchingFailedDetails id="remote-A" />);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(screen.getByRole("alert")).toHaveTextContent("network unavailable");
    expect(mocks.execute).toHaveBeenCalledOnce();
  });
});
