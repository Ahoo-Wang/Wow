import { describe, it, expect, vi } from "vitest";
import { FailedSearch } from "../FailedSearch.tsx";

// Mock all dependencies to avoid complex setup
vi.mock("antd", () => ({
  Form: {
    useForm: () => [{}],
  },
  Input: () => null,
  Button: () => null,
  Space: () => null,
  Row: () => null,
  Col: () => null,
}));

vi.mock("@ant-design/icons", () => ({
  SearchOutlined: () => null,
  ClearOutlined: () => null,
}));

vi.mock("react", async () => {
  const actual = await vi.importActual("react");
  return {
    ...actual,
    useCallback: vi.fn((fn) => fn),
  };
});

vi.mock("@ahoo-wang/fetcher-wow", () => ({
  and: vi.fn(),
  eq: vi.fn(),
}));

describe("FailedSearch", () => {
  it("is a function", () => {
    expect(typeof FailedSearch).toBe("function");
  });

  it("can be called with onSearch prop", () => {
    const mockOnSearch = vi.fn();
    expect(() => FailedSearch({ onSearch: mockOnSearch })).not.toThrow();
  });
});