import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  FunctionKind,
  RecoverableType,
  eq,
  type Condition,
  type PagedList,
} from "@ahoo-wang/fetcher-wow";
import {
  ExecutionFailedStatus,
  type ExecutionFailedState,
} from "../../../generated";
import { FindCategory } from "../FindCategory.ts";
import FailedView from "../FailedView.tsx";

const mocks = vi.hoisted(() => ({
  search: "",
  setSearchParams: vi.fn(),
  setQuery: vi.fn(),
  run: vi.fn(),
  error: undefined as Error | undefined,
  hookOptions: undefined as unknown,
  desktop: true,
  drawerOpen: false,
  loading: false,
  pending: false,
  result: { list: [], total: 0 } as PagedList<ExecutionFailedState>,
}));

vi.mock("@/components/GlobalDrawer", () => ({
  useGlobalDrawer: () => ({ isOpen: mocks.drawerOpen }),
}));

vi.mock("react-router", () => ({
  useSearchParams: () => [
    new URLSearchParams(mocks.search),
    mocks.setSearchParams,
  ],
}));

vi.mock("../../../hooks/useMediaQuery", () => ({
  useMediaQuery: () => mocks.desktop,
}));

vi.mock("@ahoo-wang/fetcher-react", () => ({
  useDebouncedFetcherQuery: (options: unknown) => {
    mocks.hookOptions = options;
    return {
      loading: mocks.loading,
      isPending: () => mocks.pending,
      result: mocks.result,
      error: mocks.error,
      setQuery: mocks.setQuery,
      run: mocks.run,
    };
  },
}));

vi.mock("../../../components/ui/resizable", () => ({
  ResizablePanelGroup: ({
    children,
    defaultLayout,
    onLayoutChanged,
  }: {
    children: React.ReactNode;
    defaultLayout: Record<string, number>;
    onLayoutChanged: (
      layout: Record<string, number>,
      meta: { isUserInteraction: boolean },
    ) => void;
  }) => (
    <div data-layout={JSON.stringify(defaultLayout)}>
      {children}
      <button
        onClick={() =>
          onLayoutChanged(
            { "execution-list": 35, "execution-details": 65 },
            { isUserInteraction: true },
          )
        }
      >
        Save split layout
      </button>
    </div>
  ),
  ResizablePanel: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  ResizableHandle: () => <div />,
}));

vi.mock("../FailedSearch.tsx", () => ({
  FailedSearch: ({
    onSearch,
  }: {
    onSearch: (condition: Condition) => void;
  }) => (
    <button onClick={() => onSearch(eq("state.id", "failed"))}>Search</button>
  ),
}));

vi.mock("../FailedTable.tsx", () => ({
  FailedTable: ({
    pagedList,
    pageIndex,
    selectedId,
    loading,
    error,
    staleError,
    onSelect,
    onPaginationChange,
  }: {
    pagedList: PagedList<ExecutionFailedState>;
    pageIndex: number;
    selectedId?: string | null;
    loading?: boolean;
    error?: Error;
    staleError?: Error;
    onSelect: (state: ExecutionFailedState) => void;
    onPaginationChange: (page: number, size: number) => void;
  }) => (
    <div>
      <span>Rows: {pagedList.total}</span>
      <span>Page index: {pageIndex}</span>
      <span>Active selection: {selectedId ?? "none"}</span>
      <span>Transitioning: {loading ? "yes" : "no"}</span>
      <span>List error: {error?.message ?? "none"}</span>
      <span>Stale error: {staleError?.message ?? "none"}</span>
      <button onClick={() => onSelect(secondState)}>Select failed-2</button>
      <button onClick={() => onPaginationChange(pageIndex + 1, 10)}>
        Next page
      </button>
    </div>
  ),
}));

vi.mock("../details/FailedDetails.tsx", () => ({
  FailedDetails: ({
    state,
    onChanged,
    mutationsDisabled,
  }: {
    state: ExecutionFailedState;
    onChanged: () => void;
    mutationsDisabled?: boolean;
  }) => (
    <div>
      Selected: {state.id}
      <span>Mutations: {mutationsDisabled ? "disabled" : "enabled"}</span>
      <button onClick={onChanged}>Refresh</button>
    </div>
  ),
}));

vi.mock("../details/FetchingFailedDetails.tsx", () => ({
  FetchingFailedDetails: ({ id }: { id: string }) => <div>Fetching: {id}</div>,
}));

const firstState: ExecutionFailedState = {
  id: "failed-1",
  status: ExecutionFailedStatus.FAILED,
  recoverable: RecoverableType.RECOVERABLE,
  error: {
    errorCode: "E1",
    errorMsg: "failed",
    stackTrace: "trace",
    bindingErrors: [],
    succeeded: false,
  },
  eventId: {
    id: "event-1",
    version: 1,
    aggregateId: {
      aggregateName: "payment",
      contextName: "billing",
      aggregateId: "payment-1",
      tenantId: "tenant",
    },
  },
  executeAt: 1,
  function: {
    contextName: "billing",
    processorName: "processor",
    name: "handle",
    functionKind: FunctionKind.EVENT,
  },
  retrySpec: { maxRetries: 3, minBackoff: 1, executionTimeout: 2 },
  retryState: { nextRetryAt: 2, retries: 1, retryAt: 1, timeoutAt: 2 },
  isBelowRetryThreshold: true,
  isRetryable: true,
};

const secondState: ExecutionFailedState = { ...firstState, id: "failed-2" };

describe("FailedView", () => {
  beforeEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
    mocks.search = "";
    mocks.desktop = true;
    mocks.drawerOpen = false;
    mocks.loading = false;
    mocks.pending = false;
    mocks.error = undefined;
    mocks.result = { list: [firstState, secondState], total: 2 };
    window.localStorage.clear();
  });

  it("selects the first row for the desktop master-detail workspace", () => {
    render(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Selected: failed-1")).toBeInTheDocument();
    expect(mocks.setSearchParams).toHaveBeenCalledWith(
      new URLSearchParams("id=failed-1"),
      { replace: true },
    );
  });

  it("does not highlight a row before mobile details are opened", () => {
    mocks.desktop = false;

    render(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Active selection: none")).toBeInTheDocument();
    expect(mocks.setSearchParams).not.toHaveBeenCalled();
  });

  it("focuses the mobile details panel instead of an incidental copy action", async () => {
    mocks.desktop = false;
    mocks.search = "id=failed-1";

    render(<FailedView category={FindCategory.ToRetry} />);

    await waitFor(() => {
      expect(document.activeElement).toHaveAttribute(
        "aria-label",
        "Execution details panel",
      );
    });
  });

  it("persists a user-resized desktop split layout", () => {
    render(<FailedView category={FindCategory.ToRetry} />);

    fireEvent.click(screen.getByRole("button", { name: "Save split layout" }));

    expect(
      JSON.parse(
        window.localStorage.getItem(
          "compensation-dashboard:failed-view-layout",
        ) ?? "{}",
      ),
    ).toEqual({ "execution-list": 35, "execution-details": 65 });
  });

  it("keeps resizing usable when layout persistence is unavailable", () => {
    const setItem = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementationOnce(() => {
        throw new DOMException("Storage disabled", "SecurityError");
      });
    render(<FailedView category={FindCategory.ToRetry} />);

    expect(() =>
      fireEvent.click(
        screen.getByRole("button", { name: "Save split layout" }),
      ),
    ).not.toThrow();

    setItem.mockRestore();
  });

  it("loads a deep-linked execution that is outside the current page", () => {
    mocks.search = "id=deep-linked";
    mocks.result = { list: [], total: 0 };

    render(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Fetching: deep-linked")).toBeInTheDocument();
  });

  it("writes list selection back to the id query parameter", () => {
    render(<FailedView category={FindCategory.ToRetry} />);

    fireEvent.click(screen.getByRole("button", { name: "Select failed-2" }));

    expect(mocks.setSearchParams).toHaveBeenCalledWith(
      new URLSearchParams("id=failed-2"),
    );
  });

  it("keeps all paged queries deterministically sorted by execution id", () => {
    render(<FailedView category={FindCategory.ToRetry} />);

    const initialQuery = (mocks.hookOptions as { query: { sort?: unknown[] } })
      .query;
    expect(initialQuery.sort).toEqual([
      { field: "aggregateId", direction: "DESC" },
    ]);

    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));

    for (const [query] of mocks.setQuery.mock.calls) {
      expect(query.sort).toEqual([{ field: "aggregateId", direction: "DESC" }]);
    }
  });

  it("rerenders pagination controls with the current page", () => {
    mocks.result = { list: [firstState], total: 15 };
    render(<FailedView category={FindCategory.ToRetry} />);

    fireEvent.click(screen.getByRole("button", { name: "Next page" }));

    expect(screen.getByText("Page index: 2")).toBeInTheDocument();
  });

  it("keeps the settled page visible while the next page is loading", () => {
    mocks.result = { list: [firstState, secondState], total: 15 };
    const view = render(<FailedView category={FindCategory.ToRetry} />);

    act(() => {
      (
        mocks.hookOptions as {
          onSuccess: (result: PagedList<ExecutionFailedState>) => void;
        }
      ).onSuccess(mocks.result);
    });

    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    mocks.loading = true;
    view.rerender(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Rows: 15")).toBeInTheDocument();
    expect(screen.getByText("Page index: 1")).toBeInTheDocument();
  });

  it("locks the settled page while a debounced page request is pending", () => {
    mocks.result = { list: [firstState, secondState], total: 15 };
    const view = render(<FailedView category={FindCategory.ToRetry} />);

    act(() => {
      (
        mocks.hookOptions as {
          onSuccess: (result: PagedList<ExecutionFailedState>) => void;
        }
      ).onSuccess(mocks.result);
    });

    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    mocks.pending = true;
    view.rerender(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Page index: 1")).toBeInTheDocument();
    expect(screen.getByText("Transitioning: yes")).toBeInTheDocument();
    expect(screen.queryByText("Selected: failed-1")).not.toBeInTheDocument();
  });

  it("refreshes after an operation changes an execution", () => {
    render(<FailedView category={FindCategory.ToRetry} />);

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    expect(mocks.setQuery).toHaveBeenCalledOnce();
    expect(mocks.setQuery).toHaveBeenCalledWith({
      condition: expect.anything(),
      pagination: expect.anything(),
      projection: undefined,
      sort: [{ field: "aggregateId", direction: "DESC" }],
    });
  });

  it("preserves the selected detail while a background refresh is pending", () => {
    const view = render(<FailedView category={FindCategory.ToRetry} />);

    act(() => {
      (
        mocks.hookOptions as {
          onSuccess: (result: PagedList<ExecutionFailedState>) => void;
        }
      ).onSuccess(mocks.result);
    });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    mocks.pending = true;
    view.rerender(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Selected: failed-1")).toBeInTheDocument();
    expect(screen.getByText("Transitioning: yes")).toBeInTheDocument();
    expect(screen.getByText("Mutations: disabled")).toBeInTheDocument();
  });

  it("keeps last-known-good data read-only after a background refresh fails", () => {
    const view = render(<FailedView category={FindCategory.ToRetry} />);
    act(() => {
      (
        mocks.hookOptions as {
          onSuccess: (result: PagedList<ExecutionFailedState>) => void;
        }
      ).onSuccess(mocks.result);
    });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    mocks.result = undefined as unknown as PagedList<ExecutionFailedState>;
    mocks.error = new Error("network unavailable");
    view.rerender(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Rows: 2")).toBeInTheDocument();
    expect(
      screen.getByText("Stale error: network unavailable"),
    ).toBeInTheDocument();
    expect(screen.getByText("List error: none")).toBeInTheDocument();
    expect(screen.getByText("Selected: failed-1")).toBeInTheDocument();
    expect(screen.getByText("Mutations: disabled")).toBeInTheDocument();
  });

  it("refreshes time-sensitive queues as their time window advances", () => {
    vi.useFakeTimers();
    render(<FailedView category={FindCategory.NextRetry} />);
    mocks.setQuery.mockClear();

    act(() => vi.advanceTimersByTime(30_000));

    expect(mocks.setQuery).toHaveBeenCalledOnce();
  });

  it("hides intentional aborts but keeps actionable list failures", () => {
    const view = render(<FailedView category={FindCategory.ToRetry} />);

    mocks.error = new DOMException(
      "signal is aborted without reason",
      "AbortError",
    );
    view.rerender(<FailedView category={FindCategory.ToRetry} />);
    expect(screen.getByText("List error: none")).toBeInTheDocument();

    mocks.error = new Error("network unavailable");
    view.rerender(<FailedView category={FindCategory.ToRetry} />);
    expect(
      screen.getByText("List error: network unavailable"),
    ).toBeInTheDocument();
  });

  it("keeps last-known-good data usable when a background refresh is aborted", () => {
    const view = render(<FailedView category={FindCategory.ToRetry} />);
    act(() => {
      (
        mocks.hookOptions as {
          onSuccess: (result: PagedList<ExecutionFailedState>) => void;
        }
      ).onSuccess(mocks.result);
    });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    mocks.result = undefined as unknown as PagedList<ExecutionFailedState>;
    mocks.error = new DOMException(
      "signal is aborted without reason",
      "AbortError",
    );
    view.rerender(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByText("Rows: 2")).toBeInTheDocument();
    expect(screen.getByText("List error: none")).toBeInTheDocument();
    expect(screen.getByText("Stale error: none")).toBeInTheDocument();
    expect(screen.getByText("Selected: failed-1")).toBeInTheDocument();
    expect(screen.getByText("Mutations: enabled")).toBeInTheDocument();
  });

  it("clamps an empty trailing page after the result total shrinks", () => {
    mocks.result = { list: [], total: 15 };

    render(<FailedView category={FindCategory.ToRetry} />);

    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    fireEvent.click(screen.getByRole("button", { name: "Next page" }));
    act(() => {
      (
        mocks.hookOptions as {
          onSuccess: (result: PagedList<ExecutionFailedState>) => void;
        }
      ).onSuccess(mocks.result);
    });

    expect(screen.getByText("Page index: 2")).toBeInTheDocument();
    expect(mocks.setQuery).toHaveBeenLastCalledWith({
      condition: expect.anything(),
      pagination: { index: 2, size: 10 },
      projection: undefined,
      sort: [{ field: "aggregateId", direction: "DESC" }],
    });
  });
});
