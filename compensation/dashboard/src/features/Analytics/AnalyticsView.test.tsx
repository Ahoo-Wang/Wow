import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { TrendPoint } from "./analyticsQueries.ts";
import type {
  AnalyticsSection,
  SnapshotAnalyticsResult,
} from "./useSnapshotAnalytics.ts";
import AnalyticsView from "./AnalyticsView.tsx";

const mocks = vi.hoisted(() => ({
  eventResult: undefined as unknown as AnalyticsSection<TrendPoint[]>,
  snapshotResult: undefined as unknown as SnapshotAnalyticsResult,
  useEventTrend: vi.fn(),
  useSnapshotAnalytics: vi.fn(),
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
  DistributionChart: ({ title }: { title: string }) => <div>{title}</div>,
}));

beforeEach(() => {
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

describe("AnalyticsView", () => {
  it("renders current pressure and keeps range scoped to history", () => {
    render(<AnalyticsView />);

    expect(screen.getByText("128")).toBeInTheDocument();
    expect(
      screen.getByRole("table", { name: "Current failure pressure" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "7d" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    fireEvent.click(screen.getByRole("button", { name: "24h" }));

    expect(mocks.useEventTrend).toHaveBeenLastCalledWith("24h", 0);
    expect(mocks.useSnapshotAnalytics).toHaveBeenLastCalledWith(0);
  });

  it("refreshes both facts with one token and preserves regional errors", () => {
    mocks.snapshotResult.summary.error = new Error("snapshot unavailable");
    render(<AnalyticsView />);

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
    render(<AnalyticsView />);

    expect(document.querySelectorAll("[data-slot='skeleton']").length).toBeGreaterThan(0);
    expect(screen.getByRole("status")).toHaveTextContent("Refreshing");
    expect(screen.getAllByText(/Last updated/).length).toBeGreaterThan(0);
  });

  it("keeps empty pressure table accessible and does not draw truncated retry data", () => {
    mocks.snapshotResult.retries.data = {
      buckets: [{ key: "0", count: 3 }],
      truncated: true,
    };
    render(<AnalyticsView />);

    expect(screen.getByText("No active failure clusters")).toBeInTheDocument();
    expect(screen.getByText(/Retry distribution is truncated/)).toBeInTheDocument();
    expect(screen.queryByText("Retry distribution")).not.toBeInTheDocument();
  });

  it("distinguishes pressure identities and shows zero-safe status shares", () => {
    mocks.snapshotResult.pressure.data = [
      {
        contextName: "billing",
        currentCount: 10,
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
    render(<AnalyticsView />);

    expect(screen.getByText(/EVENT/)).toBeInTheDocument();
    expect(screen.getByText(/COMMAND/)).toBeInTheDocument();
    expect(screen.getByText("6 (60%)")).toBeInTheDocument();
    expect(screen.getByText("4 (40%)")).toBeInTheDocument();
    expect(screen.getAllByText("0 (0%)")).toHaveLength(2);
  });

  it("keeps summary, pressure, distributions, then history in reading order", () => {
    render(<AnalyticsView />);

    const content = document.body.textContent ?? "";
    expect(content.indexOf("Actionable now")).toBeLessThan(
      content.indexOf("Current failure pressure"),
    );
    expect(content.indexOf("Current failure pressure")).toBeLessThan(
      content.indexOf("Recoverability distribution"),
    );
    expect(content.indexOf("Recoverability distribution")).toBeLessThan(
      content.indexOf("Compensation history"),
    );
  });
});
