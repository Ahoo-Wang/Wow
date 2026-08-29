import { fireEvent, render, screen } from "@testing-library/react";
import {
  AggregationDateUnit,
  RecoverableType,
} from "@ahoo-wang/fetcher-wow";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TrendPoint } from "./analyticsQueries.ts";
import type {
  AnalyticsSection,
  SnapshotAnalyticsResult,
} from "./useSnapshotAnalytics.ts";
import DashboardView from "./DashboardView.tsx";

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
vi.mock("@/components/ui/calendar", () => ({
  Calendar: ({
    onSelect,
  }: {
    onSelect?: (range: { from?: Date; to?: Date }) => void;
  }) => (
    <div role="grid" aria-label="Date range calendar">
      <button
        type="button"
        onClick={() => onSelect?.({ from: new Date(2026, 7, 10) })}
      >
        Select start only
      </button>
      <button
        type="button"
        onClick={() =>
          onSelect?.({
            from: new Date(2026, 7, 10),
            to: new Date(2026, 7, 12),
          })
        }
      >
        Select complete range
      </button>
    </div>
  ),
}));
vi.mock("./AnalyticsCharts.tsx", () => ({
  CompensationTrendChart: ({ points }: { points: TrendPoint[] }) => (
    <div data-testid="trend-chart">{points.length}</div>
  ),
  DistributionChart: ({
    data,
    description,
    title,
  }: {
    data: Array<{ color: string; count: number; label: string }>;
    description: string;
    title: string;
  }) => (
    <div>
      <h3>{title}</h3>
      <p>{description}</p>
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
  vi.spyOn(Date, "now").mockReturnValue(
    new Date(2026, 7, 29, 10, 37).getTime(),
  );
  mocks.useSnapshotAnalytics.mockClear();
  mocks.useEventTrend.mockClear();
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

afterEach(() => vi.restoreAllMocks());

describe("DashboardView", () => {
  it("applies one complete local date range to both facts", () => {
    render(<DashboardView />);

    expect(screen.getByText("128")).toBeInTheDocument();
    expect(
      screen.getByRole("table", { name: "Current failure pressure" }),
    ).toBeInTheDocument();
    const initialWindow = {
      buckets: Array.from({ length: 7 }, (_, index) =>
        new Date(2026, 7, 23 + index).getTime(),
      ),
      end: new Date(2026, 7, 30).getTime(),
      start: new Date(2026, 7, 23).getTime(),
      timeZone: expect.any(String),
      unit: AggregationDateUnit.DAY,
    };
    expect(mocks.useEventTrend).toHaveBeenLastCalledWith(initialWindow, 0);
    expect(mocks.useSnapshotAnalytics).toHaveBeenLastCalledWith(
      initialWindow,
      0,
    );
    const picker = screen.getByRole("button", {
      name: "Time range: 2026-08-23 – 2026-08-29",
    });
    expect(screen.queryByRole("button", { name: "7d" })).not.toBeInTheDocument();

    const initialWindowReference = mocks.useSnapshotAnalytics.mock.lastCall?.[0];
    fireEvent.click(picker);
    fireEvent.click(screen.getByRole("button", { name: "Select start only" }));
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();
    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[0]).toBe(
      initialWindowReference,
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[0]).toBe(
      initialWindowReference,
    );

    fireEvent.click(picker);
    fireEvent.click(
      screen.getByRole("button", { name: "Select complete range" }),
    );
    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[0]).toBe(
      initialWindowReference,
    );
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    const appliedWindow = {
      buckets: [
        new Date(2026, 7, 10).getTime(),
        new Date(2026, 7, 11).getTime(),
        new Date(2026, 7, 12).getTime(),
      ],
      end: new Date(2026, 7, 13).getTime(),
      start: new Date(2026, 7, 10).getTime(),
      timeZone: expect.any(String),
      unit: AggregationDateUnit.DAY,
    };
    expect(mocks.useSnapshotAnalytics).toHaveBeenLastCalledWith(
      appliedWindow,
      0,
    );
    expect(mocks.useEventTrend).toHaveBeenLastCalledWith(appliedWindow, 0);
    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[0]).toBe(
      mocks.useEventTrend.mock.lastCall?.[0],
    );
  });

  it.each([
    ["Today", 1, new Date(2026, 7, 29).getTime()],
    ["Last 7 days", 7, new Date(2026, 7, 23).getTime()],
    ["Last 30 days", 30, new Date(2026, 6, 31).getTime()],
  ] as const)(
    "applies %s immediately and closes the date picker",
    (label, days, start) => {
      render(<DashboardView />);

      fireEvent.click(screen.getByRole("button", { name: /^Time range:/ }));
      fireEvent.click(screen.getByRole("button", { name: label }));

      expect(
        screen.queryByRole("grid", { name: "Date range calendar" }),
      ).not.toBeInTheDocument();
      const window = mocks.useSnapshotAnalytics.mock.lastCall?.[0];
      expect(window).toMatchObject({
        start,
        end: new Date(2026, 7, 30).getTime(),
        unit: AggregationDateUnit.DAY,
      });
      expect(window.buckets).toHaveLength(days);
      expect(window).toBe(mocks.useEventTrend.mock.lastCall?.[0]);
    },
  );

  it("refreshes both facts with one token and preserves regional errors", () => {
    mocks.snapshotResult.summary.error = new Error("snapshot unavailable");
    render(<DashboardView />);

    expect(screen.getByRole("alert")).toHaveTextContent("snapshot unavailable");
    fireEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));

    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[1]).toBe(1);
    expect(mocks.useEventTrend.mock.lastCall?.[1]).toBe(1);
    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[0]).toBe(
      mocks.useEventTrend.mock.lastCall?.[0],
    );
  });

  it("uses the complete dashboard skeleton only for the first full load", () => {
    mocks.snapshotResult = {
      summary: { loading: true },
      pressure: { loading: true },
      recoverability: { loading: true },
      retries: { loading: true },
    };
    mocks.eventResult = { loading: true };
    const { rerender } = render(<DashboardView />);

    expect(
      screen.getByRole("status", { name: "Loading dashboard" }),
    ).toBeInTheDocument();
    expect(document.querySelectorAll("[data-slot='skeleton']")).toHaveLength(13);

    mocks.snapshotResult.summary = {
      data: { actionableNow: 128, timedOut: 34, unrecoverable: 9 },
      loading: true,
      updatedAt: 1_787_932_800_000,
    };
    mocks.snapshotResult.pressure = {
      data: [],
      loading: true,
      updatedAt: 1_787_932_800_000,
    };
    mocks.snapshotResult.recoverability = { data: [], loading: false };
    mocks.snapshotResult.retries = {
      data: { buckets: [], truncated: false },
      loading: false,
    };
    mocks.eventResult = { data: [], loading: true };
    rerender(<DashboardView />);

    expect(
      screen.queryByRole("status", { name: "Loading dashboard" }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByRole("status")[0]).toHaveTextContent("Refreshing");
    expect(
      screen.getByRole("table", { name: "Current failure pressure" }),
    ).toBeInTheDocument();
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
    expect(
      screen.getByLabelText("Failed 6 (30%); Prepared 4 (20%)"),
    ).toBeInTheDocument();
    expect(screen.getAllByText("0 (0%)")).toHaveLength(2);
    expect(
      screen.getByText("Current failure pressure — Top 5 clusters"),
    ).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("Now");
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/);
  });

  it("keeps summary, pressure, distributions, then history in reading order", () => {
    render(<DashboardView />);

    const content = document.body.textContent ?? "";
    expect(screen.getAllByText(/Updated /)).toHaveLength(1);
    expect(screen.queryByText(/Last updated/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Dashboard signals")).not.toHaveTextContent(
      /Time range|2026-08-23|2026-08-29/,
    );
    expect(content.indexOf("Actionable now")).toBeLessThan(
      content.indexOf("Current failure pressure"),
    );
    expect(content.indexOf("Current failure pressure")).toBeLessThan(
      content.indexOf("Recoverability"),
    );
    expect(content.indexOf("Recoverability")).toBeLessThan(
      content.indexOf("Retry distribution"),
    );
    expect(content.indexOf("Retry distribution")).toBeLessThan(
      content.indexOf("Compensation outcomes"),
    );
  });
});
