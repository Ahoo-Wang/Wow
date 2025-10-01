import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { FetchingFailedDetails } from "../FetchingFailedDetails.tsx";

// Mock dependencies
vi.mock("swr", () => ({
  default: vi.fn(() => ({ data: null, error: null, isLoading: true })),
}));

vi.mock("../../services", () => ({
  executionFailedSnapshotQueryClient: {
    singleState: vi.fn(),
  },
}));

vi.mock("@ahoo-wang/fetcher-wow", () => ({
  aggregateId: vi.fn(),
  singleQuery: vi.fn(),
  ResourceAttributionPathSpec: { NONE: "NONE" },
  QueryClientFactory: class {
    createSnapshotQueryClient() {
      return {};
    }
  },
}));

vi.mock("antd", () => ({
  Flex: ({ children }: any) => <div data-testid="flex">{children}</div>,
  Skeleton: () => <div data-testid="skeleton">Skeleton</div>,
  Typography: { Text: ({ children }: any) => <span>{children}</span> },
  Statistic: { Timer: () => <div>Timer</div> },
}));

vi.mock("./FailedDetails.tsx", () => ({
  FailedDetails: () => <div data-testid="failed-details">FailedDetails</div>,
}));

describe("FetchingFailedDetails", () => {
  it("renders skeleton when loading", () => {
    const { getAllByTestId } = render(<FetchingFailedDetails id="test-id" />);
    expect(getAllByTestId("skeleton")).toHaveLength(3);
  });

  it("renders flex container", () => {
    const { getByTestId } = render(<FetchingFailedDetails id="test-id" />);
    expect(getByTestId("flex")).toBeInTheDocument();
  });

  it("can be called with id prop", () => {
    expect(() => FetchingFailedDetails({ id: "test-id" })).not.toThrow();
  });
});