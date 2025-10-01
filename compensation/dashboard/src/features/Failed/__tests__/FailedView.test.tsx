import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import FailedView from "../FailedView.tsx";

vi.mock("../../components/GlobalDrawer/useGlobalDrawer", () => ({
  useGlobalDrawer: () => ({ openDrawer: vi.fn() }),
}));

vi.mock("../../utils/useQuery.ts", () => ({
  useQueryParams: vi.fn(() => null),
}));

vi.mock("react", async () => {
  const actual = await vi.importActual("react");
  return {
    ...actual,
    useEffect: vi.fn(),
    useContext: vi.fn(() => ({ openDrawer: vi.fn() })),
  };
});

vi.mock("@ahoo-wang/fetcher-wow", () => ({
  all: vi.fn(),
  and: vi.fn(),
  ne: vi.fn(),
  eq: vi.fn(),
  pagination: vi.fn(() => ({ page: 1, size: 10 })),
  pagedList: vi.fn(() => ({ list: [], total: 0 })),
  ResourceAttributionPathSpec: { NONE: "NONE" },
  RecoverableType: { UNRECOVERABLE: "UNRECOVERABLE" },
  QueryClientFactory: class {
    createSnapshotQueryClient() {
      return {};
    }
  },
}));

vi.mock("../../services", () => ({
  executionFailedSnapshotQueryClient: {
    pagedState: vi.fn().mockResolvedValue({ list: [], total: 0 }),
  },
}));

vi.mock("../FailedSearch.tsx", () => ({
  FailedSearch: () => <div data-testid="failed-search">FailedSearch</div>,
}));

vi.mock("../FailedTable.tsx", () => ({
  FailedTable: () => <div data-testid="failed-table">FailedTable</div>,
}));

vi.mock("./FindCategory.ts", () => ({
  FindCategory: { ToRetry: "ToRetry" },
}));

vi.mock("react-router", () => ({
  useLocation: vi.fn(() => ({ search: "" })),
}));

describe("FailedView", () => {
  it("renders FailedSearch and FailedTable", () => {
    const { getByTestId } = render(<FailedView category="ToRetry" />);
    expect(getByTestId("failed-search")).toBeInTheDocument();
    expect(getByTestId("failed-table")).toBeInTheDocument();
  });
});