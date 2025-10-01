import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { FailedTable } from "../FailedTable.tsx";

// Mock all dependencies
vi.mock("antd", () => ({
  Table: ({ dataSource }: any) => <div data-testid="table">Table with {dataSource?.length || 0} items</div>,
  Typography: { Text: () => <span>Text</span> },
  Statistic: { Timer: () => <div>Timer</div> },
  Tag: () => <span>Tag</span>,
  Space: () => <div>Space</div>,
  Pagination: () => <div>Pagination</div>,
}));

vi.mock("@ant-design/icons", () => ({
  EditTwoTone: () => <span>EditIcon</span>,
}));

vi.mock("../../components/GlobalDrawer/useGlobalDrawer", () => ({
  useGlobalDrawer: () => ({ openDrawer: vi.fn() }),
}));

vi.mock("../../utils/dates.ts", () => ({
  formatDate: vi.fn(() => "formatted date"),
}));

vi.mock("dayjs", () => ({
  default: {
    extend: vi.fn(),
  },
}));

vi.mock("./Actions.tsx", () => ({
  Actions: () => <div>Actions</div>,
}));

vi.mock("./ApplyRetrySpec.tsx", () => ({
  ApplyRetrySpec: () => <div>ApplyRetrySpec</div>,
}));

vi.mock("./ChangeFunction.tsx", () => ({
  ChangeFunction: () => <div>ChangeFunction</div>,
}));

vi.mock("./MarkRecoverable.tsx", () => ({
  MarkRecoverable: () => <div>MarkRecoverable</div>,
}));

vi.mock("react", async () => {
  const actual = await vi.importActual("react");
  return {
    ...actual,
    useMemo: vi.fn((fn) => fn()),
    useContext: vi.fn(() => ({ openDrawer: vi.fn() })),
  };
});

describe("FailedTable", () => {
  const mockPagedList = {
    data: [],
    total: 0,
    page: 1,
    size: 10,
  };

  it("renders table with empty data", () => {
    const { getByTestId } = render(<FailedTable pagedList={mockPagedList} />);
    expect(getByTestId("table")).toHaveTextContent("Table with 0 items");
  });

  it("renders table with onPaginationChange", () => {
    const onPaginationChange = vi.fn();
    const { getByTestId } = render(
      <FailedTable pagedList={mockPagedList} onPaginationChange={onPaginationChange} />
    );
    expect(getByTestId("table")).toBeInTheDocument();
  });

  it("renders table with onChanged", () => {
    const onChanged = vi.fn();
    const { getByTestId } = render(
      <FailedTable pagedList={mockPagedList} onChanged={onChanged} />
    );
    expect(getByTestId("table")).toBeInTheDocument();
  });
});