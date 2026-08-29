import { fireEvent, render, screen } from "@testing-library/react";
import { RecoverableType } from "@ahoo-wang/fetcher-wow";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AnalyticsRange, TrendPoint } from "./analyticsQueries.ts";
import type {
  AnalyticsSection,
  SnapshotAnalyticsResult,
} from "./useSnapshotAnalytics.ts";
import DashboardView from "./DashboardView.tsx";

const mocks = vi.hoisted(() => ({
  eventResult: undefined as unknown as AnalyticsSection<TrendPoint[]>,
  outcomesRange: "7d" as AnalyticsRange,
  snapshotResult: undefined as unknown as SnapshotAnalyticsResult,
  useEventTrend: vi.fn(),
  useSnapshotAnalytics: vi.fn(),
}));

vi.mock("react-router", () => ({
  useOutletContext: () => ({
    outcomesRange: mocks.outcomesRange,
    setOutcomesRange: vi.fn(),
  }),
}));
vi.mock("./useSnapshotAnalytics.ts", () => ({
  useSnapshotAnalytics: mocks.useSnapshotAnalytics,
}));
vi.mock("./useEventTrend.ts", () => ({
  useEventTrend: mocks.useEventTrend,
}));
vi.mock("./AnalyticsCharts.tsx", () => ({
  CompensationTrendChart: ({ points }: { points: TrendPoint[] }) => (
    <div data-testid="trend-chart">{points.length}</div>
  ),
  DistributionChart: ({
    data,
    title,
  }: {
    data: Array<{ color: string; count: number; label: string }>;
    title: string;
  }) => (
    <div>
      <h3>{title}</h3>
      {data.map(({ color, count, label }) => (
        <span data-color={color} key={label}>
          {label}: {count}
        </span>
      ))}
    </div>
  ),
  RetryDistributionChart: ({
    data,
  }: {
    data: Array<{ color: string; count: number; label: string }>;
  }) => (
    <div>
      <h3>Retry distribution</h3>
      {data.map(({ color, count, label }) => (
        <span data-color={color} key={label}>
          {label}: {count}
        </span>
      ))}
    </div>
  ),
}));

beforeEach(() => {
  mocks.outcomesRange = "7d";
  mocks.snapshotResult = {
    summary: {
      data: { actionableNow: 128, timedOut: 34, unrecoverable: 9 },
      loading: false,
      updatedAt: 1_787_932_800_000,
    },
    pressure: { data: [], loading: false, updatedAt: 1_787_932_800_000 },
    recoverability: { data: [], loading: false, updatedAt: 1_787_932_800_000 },
    retries: {
      data: { buckets: [], truncated: false },
      loading: false,
      updatedAt: 1_787_932_800_000,
    },
  };
  mocks.eventResult = {
    data: [],
    loading: false,
    updatedAt: 1_787_932_800_000,
  };
  mocks.useSnapshotAnalytics.mockImplementation(() => mocks.snapshotResult);
  mocks.useEventTrend.mockImplementation(() => mocks.eventResult);
});

describe("DashboardView", () => {
  it("uses the App-owned outcomes range without changing Snapshot", () => {
    const { rerender } = render(<DashboardView />);

    expect(screen.getByText("128")).toBeInTheDocument();
    expect(
      screen.getByRole("table", { name: "Current failure pressure" }),
    ).toBeInTheDocument();
    expect(mocks.useEventTrend).toHaveBeenLastCalledWith("7d", 0);
    expect(mocks.useSnapshotAnalytics).toHaveBeenLastCalledWith(0);
    expect(screen.queryByRole("button", { name: "24h" })).not.toBeInTheDocument();

    mocks.outcomesRange = "24h";
    rerender(<DashboardView />);

    expect(mocks.useEventTrend).toHaveBeenLastCalledWith("24h", 0);
    expect(mocks.useSnapshotAnalytics).toHaveBeenLastCalledWith(0);
  });

  it("refreshes both facts with one token and preserves regional errors", () => {
    mocks.snapshotResult.summary.error = new Error("snapshot unavailable");
    render(<DashboardView />);

    expect(screen.getByRole("alert")).toHaveTextContent("snapshot unavailable");
    fireEvent.click(screen.getByRole("button", { name: "Refresh analytics" }));

    expect(mocks.useSnapshotAnalytics).toHaveBeenLastCalledWith(1);
    expect(mocks.useEventTrend).toHaveBeenLastCalledWith("7d", 1);
  });

  it("uses skeletons for first loads and preserves stale data with status", () => {
    mocks.snapshotResult.summary = { loading: true };
    mocks.snapshotResult.pressure = {
      data: [],
      loading: true,
      updatedAt: 1_787_932_800_000,
    };
    render(<DashboardView />);

    expect(document.querySelectorAll("[data-slot='skeleton']").length).toBeGreaterThan(0);
    expect(screen.getByRole("status")).toHaveTextContent("Refreshing");
    expect(screen.getAllByText(/Last updated/).length).toBeGreaterThan(0);
  });

  it("keeps empty pressure table accessible and does not draw truncated retry data", () => {
    mocks.snapshotResult.retries.data = {
      buckets: [{ key: "0", count: 3 }],
      truncated: true,
    };
    render(<DashboardView />);

    expect(screen.getByText("No active failure clusters")).toBeInTheDocument();
    expect(screen.getByText(/Retry distribution is truncated/)).toBeInTheDocument();
    expect(screen.queryByText("Retry distribution")).not.toBeInTheDocument();
  });

  it("maps all recoverability enum values to visible labels and counts", () => {
    mocks.snapshotResult.recoverability.data = [
      { recoverable: RecoverableType.RECOVERABLE, count: 7 },
      { recoverable: RecoverableType.UNKNOWN, count: 3 },
      { recoverable: RecoverableType.UNRECOVERABLE, count: 2 },
    ];

    render(<DashboardView />);

    expect(screen.getByText("Recoverable: 7")).toHaveAttribute(
      "data-color",
      "#16a34a",
    );
    expect(screen.getByText("Unknown: 3")).toHaveAttribute(
      "data-color",
      "#f59e0b",
    );
    expect(screen.getByText("Unrecoverable: 2")).toHaveAttribute(
      "data-color",
      "#dc2626",
    );
  });

  it("distinguishes pressure identities and shows zero-safe status shares", () => {
    mocks.snapshotResult.pressure.data = [
      {
        contextName: "billing",
        currentCount: 20,
        errorCode: "TIMEOUT",
        failedCount: 6,
        functionKind: "EVENT",
        functionName: "charge",
        nextRetryAt: null,
        oldestExecuteAt: null,
        preparedCount: 4,
        processorName: "PaymentProcessor",
      },
      {
        contextName: "billing",
        currentCount: 0,
        errorCode: "TIMEOUT",
        failedCount: 0,
        functionKind: "COMMAND",
        functionName: "charge",
        nextRetryAt: null,
        oldestExecuteAt: null,
        preparedCount: 0,
        processorName: "PaymentProcessor",
      },
    ];
    render(<DashboardView />);

    const pressureTable = screen.getByRole("table", {
      name: "Current failure pressure",
    });
    expect(pressureTable).toHaveTextContent("TIMEOUT");
    expect(pressureTable).toHaveTextContent("billing");
    expect(pressureTable).toHaveTextContent("PaymentProcessor");
    expect(pressureTable).toHaveTextContent("charge");
    expect(pressureTable).toHaveTextContent("EVENT");
    expect(pressureTable).toHaveTextContent("COMMAND");
    expect(screen.getByText("6 (30%)")).toBeInTheDocument();
    expect(screen.getByText("4 (20%)")).toBeInTheDocument();
    expect(screen.getAllByText("0 (0%)")).toHaveLength(2);
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/);
  });

  it("keeps summary, pressure, distributions, then history in reading order", () => {
    render(<DashboardView />);

    const content = document.body.textContent ?? "";
    expect(content.indexOf("Actionable now")).toBeLessThan(
      content.indexOf("Current failure pressure"),
    );
    expect(content.indexOf("Current failure pressure")).toBeLessThan(
      content.indexOf("Recoverability distribution"),
    );
    expect(content.indexOf("Recoverability distribution")).toBeLessThan(
      content.indexOf("Compensation outcomes"),
    );
  });
});
