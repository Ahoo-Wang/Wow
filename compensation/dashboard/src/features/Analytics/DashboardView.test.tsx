import { fireEvent, render, screen, within } from "@testing-library/react";
import { AggregationDateUnit, RecoverableType } from "@ahoo-wang/fetcher-wow";
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
    max,
    onSelect,
  }: {
    max?: number;
    onSelect?: (range: { from?: Date; to?: Date }) => void;
  }) => (
    <div role="grid" aria-label="Date range calendar" data-max={max}>
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
      data: {
        actionableNow: 128,
        activeTotal: 1_000,
        olderThanRange: 968,
        timedOut: 34,
        unrecoverable: 9,
      },
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
  it("composes the dashboard from shadcn cards", () => {
    render(<DashboardView />);

    expect(document.querySelectorAll("[data-slot='card']")).toHaveLength(4);
    expect(
      screen.getByRole("region", { name: "Compensation overview" }),
    ).toHaveAttribute("data-slot", "card");
    expect(
      screen.getByRole("region", {
        name: "Current failure pressure — Top 5 clusters",
      }),
    ).toHaveAttribute("data-slot", "card");
  });

  it("makes stock, flow, and pressure concentration the primary signals", () => {
    mocks.snapshotResult.recoverability.data = [
      { recoverable: RecoverableType.UNKNOWN, count: 2 },
      { recoverable: RecoverableType.UNRECOVERABLE, count: 30 },
    ];
    mocks.snapshotResult.pressure.data = [
      {
        contextName: "billing",
        currentCount: 20,
        errorCode: "TIMEOUT",
        failedCount: 20,
        functionKind: "EVENT",
        functionName: "charge",
        nextRetryAt: null,
        oldestExecuteAt: null,
        preparedCount: 0,
        processorName: "PaymentProcessor",
      },
      {
        contextName: "billing",
        currentCount: 12,
        errorCode: "REJECTED",
        failedCount: 12,
        functionKind: "EVENT",
        functionName: "refund",
        nextRetryAt: null,
        oldestExecuteAt: null,
        preparedCount: 0,
        processorName: "PaymentProcessor",
      },
    ];
    mocks.eventResult.data = [
      {
        bucket: new Date(2026, 7, 28).getTime(),
        newFailures: 12,
        prepared: 6,
        retriedFailed: 4,
        succeeded: 2,
      },
      {
        bucket: new Date(2026, 7, 29).getTime(),
        newFailures: 24,
        prepared: 12,
        retriedFailed: 8,
        succeeded: 4,
      },
    ];

    render(<DashboardView />);

    const stock = screen.getByRole("region", { name: "Backlog exposure" });
    expect(
      within(stock).getByRole("heading", { name: "STOCK / Backlog exposure" }),
    ).toBeInTheDocument();
    expect(within(stock).getByText("Selected active")).toBeInTheDocument();
    expect(within(stock).getByText("32")).toBeInTheDocument();
    expect(within(stock).getByText("Older backlog")).toBeInTheDocument();
    expect(within(stock).getByText("968")).toBeInTheDocument();
    expect(within(stock).getByText("Coverage")).toBeInTheDocument();
    expect(within(stock).getByText("3.2%")).toBeInTheDocument();
    expect(
      within(stock).getByRole("progressbar", {
        name: "Selected active coverage",
      }),
    ).toHaveAttribute("aria-valuenow", "3.2");

    const flow = screen.getByRole("region", {
      name: "Compensation effectiveness",
    });
    expect(
      within(flow).getByRole("heading", {
        name: "FLOW / Compensation effectiveness",
      }),
    ).toBeInTheDocument();
    expect(within(flow).getByText("New failures")).toBeInTheDocument();
    expect(within(flow).getByText("36")).toBeInTheDocument();
    expect(within(flow).getByText("Net backlog")).toBeInTheDocument();
    expect(within(flow).getByText("+30")).toBeInTheDocument();
    expect(within(flow).getByText("Retry success")).toBeInTheDocument();
    expect(within(flow).getByText("33.3%")).toBeInTheDocument();
    expect(within(flow).getByText("Prepared")).toBeInTheDocument();
    expect(within(flow).getByText("18")).toBeInTheDocument();
    expect(within(flow).getByText("Retried failed")).toBeInTheDocument();
    expect(within(flow).getByText("12")).toBeInTheDocument();
    expect(within(flow).getByText("Succeeded")).toBeInTheDocument();
    expect(within(flow).getByText("6")).toBeInTheDocument();

    expect(
      screen.getByRole("heading", {
        name: "Failure concentration · Top cluster 62.5%",
      }),
    ).toBeInTheDocument();
    expect(screen.getByText("2 clusters · Top cluster 62.5%")).toHaveAttribute(
      "data-slot",
      "badge",
    );
    expect(
      screen
        .getByRole("table", { name: "Current failure pressure" })
        .querySelector("tbody tr:first-child"),
    ).toHaveAttribute("data-dominant", "true");
  });

  it("shows an unavailable retry success rate when the selected range has no retry outcomes", () => {
    mocks.eventResult.data = [
      {
        bucket: new Date(2026, 7, 29).getTime(),
        newFailures: 12,
        prepared: 0,
        retriedFailed: 0,
        succeeded: 0,
      },
    ];

    render(<DashboardView />);

    expect(
      within(
        screen.getByRole("region", { name: "Compensation effectiveness" }),
      ).getByText("—"),
    ).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/);
  });

  it("hides cross-section insights until their data belongs to one request batch", () => {
    mocks.snapshotResult.recoverability = {
      data: [{ recoverable: RecoverableType.UNRECOVERABLE, count: 32 }],
      loading: false,
      updatedAt: 1,
    };
    mocks.snapshotResult.pressure = {
      data: [
        {
          contextName: "billing",
          currentCount: 32,
          errorCode: "TIMEOUT",
          failedCount: 32,
          functionKind: "EVENT",
          functionName: "charge",
          nextRetryAt: null,
          oldestExecuteAt: null,
          preparedCount: 0,
          processorName: "PaymentProcessor",
        },
      ],
      loading: false,
      updatedAt: 2,
    };

    render(<DashboardView />);

    expect(
      screen.queryByRole("progressbar", { name: "Selected active coverage" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(/Top cluster/)).not.toBeInTheDocument();
  });

  it("groups the quick date presets without changing their behavior", () => {
    render(<DashboardView />);
    fireEvent.click(screen.getByRole("button", { name: /^Time range:/ }));

    const presets = screen.getByRole("group", { name: "Date range presets" });
    expect(presets).toHaveAttribute("data-slot", "toggle-group");
    fireEvent.click(
      within(presets).getByRole("button", { name: "Last 7 days" }),
    );
    expect(
      screen.queryByRole("grid", { name: "Date range calendar" }),
    ).not.toBeInTheDocument();
  });

  it("renders a shared dependency error once with the shadcn alert", () => {
    mocks.snapshotResult.recoverability.error = new Error(
      "snapshot unavailable",
    );
    render(<DashboardView />);

    const alerts = screen.getAllByRole("alert");
    expect(alerts).toHaveLength(1);
    expect(alerts[0]).toHaveAttribute("data-slot", "alert");
    expect(alerts[0]).toHaveTextContent("snapshot unavailable");
    const stock = screen.getByRole("region", { name: "Backlog exposure" });
    expect(within(stock).getByText("Backlog exposure unavailable.")).toBeInTheDocument();
    expect(stock.querySelector("[data-slot='skeleton']")).toBeNull();
  });

  it("does not leave a settled trend error in a loading state", () => {
    mocks.eventResult = {
      error: new Error("trend unavailable"),
      loading: false,
    };
    render(<DashboardView />);

    const flow = screen.getByRole("region", {
      name: "Compensation effectiveness",
    });
    expect(within(flow).getByRole("alert")).toHaveTextContent(
      "trend unavailable",
    );
    expect(flow.querySelector("[data-slot='skeleton']")).toBeNull();
  });

  it("accounts for active failures newer than the selected range", () => {
    mocks.snapshotResult.summary.data = {
      actionableNow: 2,
      activeTotal: 100,
      olderThanRange: 60,
      timedOut: 1,
      unrecoverable: 4,
    };
    mocks.snapshotResult.recoverability.data = [
      { recoverable: RecoverableType.UNRECOVERABLE, count: 25 },
    ];
    render(<DashboardView />);

    const stock = screen.getByRole("region", { name: "Backlog exposure" });
    expect(within(stock).getByText("60 older")).toBeInTheDocument();
    expect(within(stock).getByText("15 newer")).toBeInTheDocument();
    expect(within(stock).getByText("100 total")).toBeInTheDocument();
  });

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
    expect(
      screen.queryByRole("button", { name: "7d" }),
    ).not.toBeInTheDocument();

    const initialWindowReference =
      mocks.useSnapshotAnalytics.mock.lastCall?.[0];
    fireEvent.click(picker);
    expect(
      screen.getByRole("grid", { name: "Date range calendar" }),
    ).toHaveAttribute("data-max", "999");
    expect(
      screen.queryByText("Select up to 1000 days."),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Select start only" }));
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();
    expect(screen.getByText("Select an end date.")).toBeInTheDocument();
    expect(
      screen.queryByText("Select up to 1000 days."),
    ).not.toBeInTheDocument();
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

  it("keeps range controls available during the first full load", () => {
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
    expect(
      screen.getByRole("button", { name: /^Time range:/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Refresh dashboard" }),
    ).toBeInTheDocument();
    expect(
      document.querySelectorAll("[data-slot='skeleton']").length,
    ).toBeGreaterThanOrEqual(6);

    mocks.snapshotResult.summary = {
      data: {
        actionableNow: 128,
        activeTotal: 1_000,
        olderThanRange: 968,
        timedOut: 34,
        unrecoverable: 9,
      },
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
    expect(screen.queryAllByText("Refreshing…")).toHaveLength(0);
    const refresh = screen.getByRole("button", {
      name: "Refreshing dashboard",
    });
    expect(refresh).toHaveAttribute("aria-busy", "true");
    expect(refresh.querySelector("svg")).toHaveClass("animate-spin");
    expect(
      screen.getByRole("table", { name: "Current failure pressure" }),
    ).toBeInTheDocument();
  });

  it("does not present incoherent stock counts", () => {
    mocks.snapshotResult.summary.data = {
      actionableNow: 0,
      activeTotal: 20,
      olderThanRange: 0,
      timedOut: 0,
      unrecoverable: 0,
    };
    mocks.snapshotResult.recoverability.data = [
      { recoverable: RecoverableType.UNRECOVERABLE, count: 25 },
    ];

    render(<DashboardView />);

    const stock = screen.getByRole("region", { name: "Backlog exposure" });
    expect(
      within(stock).getByText("Backlog exposure unavailable."),
    ).toBeInTheDocument();
    expect(
      within(stock).queryByRole("progressbar", {
        name: "Selected active coverage",
      }),
    ).not.toBeInTheDocument();
  });

  it("uses the oldest successful section timestamp for the dashboard", () => {
    const oldestUpdatedAt = new Date(2026, 7, 29).getTime();
    mocks.snapshotResult.summary.updatedAt = oldestUpdatedAt + 60_000;
    mocks.snapshotResult.pressure.updatedAt = oldestUpdatedAt;
    mocks.snapshotResult.recoverability.updatedAt = oldestUpdatedAt + 60_000;
    mocks.snapshotResult.retries.updatedAt = oldestUpdatedAt + 60_000;
    mocks.eventResult.updatedAt = oldestUpdatedAt + 60_000;

    render(<DashboardView />);

    expect(
      screen.getByText("Updated 2026-08-29 00:00:00"),
    ).toBeInTheDocument();
  });

  it("keeps empty pressure table accessible and does not draw truncated retry data", () => {
    mocks.snapshotResult.retries.data = {
      buckets: [{ key: "0", count: 3 }],
      truncated: true,
    };
    render(<DashboardView />);

    expect(screen.getByText("No active failure clusters")).toBeInTheDocument();
    expect(
      screen.getByText(/Retry distribution is truncated/),
    ).toBeInTheDocument();
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
      "var(--chart-4)",
    );
    expect(screen.getByText("Unknown: 3")).toHaveAttribute(
      "data-color",
      "var(--chart-3)",
    );
    expect(screen.getByText("Unrecoverable: 2")).toHaveAttribute(
      "data-color",
      "var(--chart-1)",
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
    expect(within(pressureTable).getAllByText("0 (0%)")).toHaveLength(2);
    expect(
      screen.getByText("Current failure pressure — Top 5 clusters"),
    ).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("Now");
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/);
  });

  it("labels pressure fields for the mobile card layout", () => {
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
    ];
    render(<DashboardView />);

    expect(screen.queryByText("Swipe to view more")).not.toBeInTheDocument();
    const firstRowCells = Array.from(
      screen
        .getByRole("table", { name: "Current failure pressure" })
        .querySelectorAll("tbody tr:first-child td"),
    );
    expect(
      firstRowCells.map((cell) => cell.getAttribute("data-label")),
    ).toEqual([
      "Cluster",
      "Current",
      "Failed / Prepared",
      "Oldest",
      "Next retry",
    ]);
  });

  it("keeps stock, flow, activity, health, then pressure in reading order", () => {
    render(<DashboardView />);

    const content = document.body.textContent ?? "";
    expect(screen.getAllByText(/Updated /)).toHaveLength(1);
    expect(screen.queryByText(/Last updated/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("Dashboard activity")).not.toHaveTextContent(
      /Time range|2026-08-23|2026-08-29/,
    );
    expect(content.indexOf("STOCK / Backlog exposure")).toBeLessThan(
      content.indexOf("FLOW / Compensation effectiveness"),
    );
    expect(content.indexOf("FLOW / Compensation effectiveness")).toBeLessThan(
      content.indexOf("Dashboard activity"),
    );
    expect(content.indexOf("Dashboard activity")).toBeLessThan(
      content.indexOf("Current health"),
    );
    expect(
      screen.getByRole("heading", {
        name: "Current health for selected execution range",
      }),
    ).toBeInTheDocument();
    expect(content.indexOf("Current health")).toBeLessThan(
      content.indexOf("Current failure pressure"),
    );
    expect(content.indexOf("Recoverability")).toBeLessThan(
      content.indexOf("Retry distribution"),
    );
    expect(content.indexOf("Retry distribution")).toBeLessThan(
      content.indexOf("Current failure pressure"),
    );
  });
});
