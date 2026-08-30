/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { fireEvent, render, screen } from "@testing-library/react";
import { AggregationDateUnit } from "@ahoo-wang/fetcher-wow";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DashboardView from "./DashboardView.tsx";

const mocks = vi.hoisted(() => ({
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
  CompensationTrendChart: () => <div>Trend chart</div>,
  DistributionChart: ({ title }: { title: string }) => <div>{title}</div>,
  RetryDistributionChart: () => <div>Retry distribution</div>,
}));

beforeEach(() => {
  vi.spyOn(Date, "now").mockReturnValue(
    new Date(2026, 7, 29, 10, 37).getTime(),
  );
  mocks.useSnapshotAnalytics.mockReturnValue({
    summary: {
      data: {
        actionableNow: 1,
        activeTotal: 4,
        olderThanRange: 0,
        timedOut: 2,
        unrecoverable: 3,
      },
      loading: false,
    },
    pressure: { data: [], loading: false },
    recoverability: { data: [], loading: false },
    retries: { data: { buckets: [], truncated: false }, loading: false },
  });
  mocks.useEventTrend.mockReturnValue({ data: [], loading: false });
});

afterEach(() => vi.restoreAllMocks());

describe("DashboardView real Calendar", () => {
  it("applies an arbitrary single day and then resets a complete range", () => {
    render(<DashboardView />);

    fireEvent.click(screen.getByRole("button", { name: /^Time range:/ }));
    fireEvent.click(screen.getByRole("button", { name: /August 20/ }));
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /August 20/ }));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    expect(mocks.useSnapshotAnalytics.mock.lastCall?.[0]).toMatchObject({
      buckets: [new Date(2026, 7, 20).getTime()],
      end: new Date(2026, 7, 21).getTime(),
      start: new Date(2026, 7, 20).getTime(),
      unit: AggregationDateUnit.DAY,
    });

    fireEvent.click(screen.getByRole("button", { name: /^Time range:/ }));
    fireEvent.click(screen.getByRole("button", { name: /August 21/ }));
    expect(screen.getByRole("button", { name: "Apply" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /August 22/ }));
    fireEvent.click(screen.getByRole("button", { name: "Apply" }));

    const window = mocks.useSnapshotAnalytics.mock.lastCall?.[0];
    expect(window).toMatchObject({
      buckets: [
        new Date(2026, 7, 21).getTime(),
        new Date(2026, 7, 22).getTime(),
      ],
      end: new Date(2026, 7, 23).getTime(),
      start: new Date(2026, 7, 21).getTime(),
      unit: AggregationDateUnit.DAY,
    });
    expect(window).toBe(mocks.useEventTrend.mock.lastCall?.[0]);
  });
});
