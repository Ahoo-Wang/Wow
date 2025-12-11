import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import FailedView from "../FailedView.tsx";
import { FindCategory } from "../FindCategory.ts";

// Mock all dependencies
vi.mock("../../../utils/useQueryParams.ts", () => ({
  useQueryParams: vi.fn(),
}));

vi.mock("../../../components/GlobalDrawer", () => ({
  useGlobalDrawer: vi.fn(),
}));

vi.mock("@ahoo-wang/fetcher-react", () => ({
  usePagedQuery: vi.fn(),
  useDebouncedFetcherQuery: vi.fn(),
  PromiseStatus: {
    IDLE: "idle"
  },
}));

vi.mock("../../../services", () => ({
  executionFailedSnapshotQueryClient: {
    pagedState: vi.fn(),
  },
}));

vi.mock("../FailedSearch.tsx", () => ({
  FailedSearch: ({
    onSearch,
  }: {
    onSearch: (condition: Condition) => void;
  }) => (
    <div data-testid="failed-search">
      <button
        data-testid="search-button"
        onClick={() => onSearch(eq("id", "test-id"))}
      >
        Search
      </button>
    </div>
  ),
}));

vi.mock("../FailedTable.tsx", () => ({
  FailedTable: ({
    loading,
    pagedList,
    onPaginationChange,
    onChanged,
  }: {
    loading: boolean;
    pagedList: PagedList<ExecutionFailedState>;
    onPaginationChange: (page: number, pageSize: number) => void;
    onChanged: () => void;
  }) => (
    <div data-testid="failed-table">
      <div data-testid="loading-state">{loading ? "loading" : "loaded"}</div>
      <div data-testid="paged-list">{JSON.stringify(pagedList)}</div>
      <button
        data-testid="pagination-button"
        onClick={() => onPaginationChange(2, 10)}
      >
        Change Page
      </button>
      <button data-testid="refresh-button" onClick={onChanged}>
        Refresh
      </button>
    </div>
  ),
}));

vi.mock("../details/FetchingFailedDetails.tsx", () => ({
  FetchingFailedDetails: ({ id }: { id: string }) => (
    <div data-testid="fetching-failed-details">Details for {id}</div>
  ),
}));

vi.mock("antd", () => ({
  App: {
    useApp: vi.fn(),
  },
}));

// Import mocks for manipulation
import { useQueryParams } from "../../../utils/useQueryParams.ts";
import { useGlobalDrawer } from "../../../components/GlobalDrawer";
import { PromiseStatus, useDebouncedFetcherQuery } from "@ahoo-wang/fetcher-react";
import { App } from "antd";
import { Condition, eq, pagedList, type PagedList } from "@ahoo-wang/fetcher-wow";
import type { ExecutionFailedState } from "../../../generated";

const mockUseQueryParams = vi.mocked(useQueryParams);
const mockUseGlobalDrawer = vi.mocked(useGlobalDrawer);
const mockUseDebouncedFetcherQuery = vi.mocked(useDebouncedFetcherQuery);
const mockUseApp = vi.mocked(App.useApp);

describe("FailedView", () => {
  const mockOpenDrawer = vi.fn();
  const mockSetQuery = vi.fn();
  const mockExecute = vi.fn();
  const mockGetQuery = vi.fn();
  const mockMessage = {
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    loading: vi.fn(),
    open: vi.fn(),
    destroy: vi.fn(),
  };
  const mockModal = {
    confirm: vi.fn(),
    destroyAll: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  };
  const mockNotification = {
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    open: vi.fn(),
    destroy: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();

    // Setup default mocks
    mockUseQueryParams.mockReturnValue(null);
    mockUseGlobalDrawer.mockReturnValue({
      openDrawer: mockOpenDrawer,
      closeDrawer: vi.fn(),
      updateDrawer: vi.fn(),
    });
    mockUseApp.mockReturnValue({
      notification: mockNotification,
      message: mockMessage,
      modal: mockModal,
    });
    mockUseDebouncedFetcherQuery.mockReturnValue({
      loading: false,
      result: { list: [], total: 0 },
      getQuery: mockGetQuery,
      setQuery: mockSetQuery,
      run: mockExecute,
      status: PromiseStatus.IDLE,
      error: null,
    });
    mockGetQuery.mockReturnValue({ pagination: { index: 1, size: 10 } });
  });

  it("renders FailedSearch and FailedTable components", () => {
    render(<FailedView category={FindCategory.All} />);

    expect(screen.getByTestId("failed-search")).toBeInTheDocument();
    expect(screen.getByTestId("failed-table")).toBeInTheDocument();
  });

  it("opens drawer when id query parameter is present", () => {
    mockUseQueryParams.mockReturnValue("test-id");

    render(<FailedView category={FindCategory.All} />);

    expect(mockOpenDrawer).toHaveBeenCalledWith({
      title: "Execution Failed Details",
      children: expect.any(Object),
    });
  });

  it("does not open drawer when id query parameter is not present", () => {
    mockUseQueryParams.mockReturnValue(null);

    render(<FailedView category={FindCategory.All} />);

    expect(mockOpenDrawer).not.toHaveBeenCalled();
  });

  it("passes loading state to FailedTable", () => {
    mockUseDebouncedFetcherQuery.mockReturnValue({
      loading: true,
      result: pagedList(),
      getQuery: mockGetQuery,
      setQuery: mockSetQuery,
      run: mockExecute,
      status: PromiseStatus.IDLE,
      error: null,
    });

    render(<FailedView category={FindCategory.All} />);

    expect(screen.getByTestId("loading-state")).toHaveTextContent("loading");
  });

  it("passes pagedList result to FailedTable", () => {
    const mockResult = { list: [{ id: "1" }], total: 1 };
    mockUseDebouncedFetcherQuery.mockReturnValue({
      loading: false,
      result: mockResult,
      getQuery: mockGetQuery,
      setQuery: mockSetQuery,
      run: mockExecute,
      status: PromiseStatus.IDLE,
      error: null,
    });

    render(<FailedView category={FindCategory.All} />);

    expect(screen.getByTestId("paged-list")).toHaveTextContent(
      JSON.stringify(mockResult),
    );
  });

  it("handles search callback", () => {
    render(<FailedView category={FindCategory.All} />);

    fireEvent.click(screen.getByTestId("search-button"));

    expect(mockSetQuery).toHaveBeenCalledWith(
      expect.objectContaining({
        condition: expect.any(Object), // Combined condition
      }),
    );
  });

  it("handles pagination change callback", () => {
    render(<FailedView category={FindCategory.All} />);

    fireEvent.click(screen.getByTestId("pagination-button"));

    expect(mockSetQuery).toHaveBeenCalledWith({
      ...mockGetQuery(),
      pagination: { index: 2, size: 10 },
    });
  });

  it("handles refresh callback", () => {
    render(<FailedView category={FindCategory.All} />);

    fireEvent.click(screen.getByTestId("refresh-button"));

    expect(mockExecute).toHaveBeenCalled();
  });

  it("renders with different categories", () => {
    const { rerender } = render(<FailedView category={FindCategory.ToRetry} />);

    expect(screen.getByTestId("failed-search")).toBeInTheDocument();

    rerender(<FailedView category={FindCategory.NonRetryable} />);

    expect(screen.getByTestId("failed-search")).toBeInTheDocument();
  });
});
