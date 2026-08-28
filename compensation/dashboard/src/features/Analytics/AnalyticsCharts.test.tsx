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

import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TrendPoint } from "./analyticsQueries.ts";
import {
  CompensationTrendChart,
  DistributionChart,
} from "./AnalyticsCharts.tsx";

const trendPointFixture = (): TrendPoint => ({
  bucket: new Date(2026, 7, 28).getTime(),
  newFailures: 12,
  prepared: 6,
  retriedFailed: 4,
  succeeded: 2,
});

describe("AnalyticsCharts", () => {
  beforeEach(() => {
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      bottom: 200,
      height: 200,
      left: 0,
      right: 320,
      toJSON: () => undefined,
      top: 0,
      width: 320,
      x: 0,
      y: 0,
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it("renders distribution values as text instead of color alone", () => {
    render(
      <DistributionChart
        title="Recoverability distribution"
        description="Current active snapshots"
        data={[
          {
            key: "recoverable",
            label: "Recoverable",
            count: 7,
            color: "#16a34a",
          },
          { key: "unknown", label: "Unknown", count: 3, color: "#f59e0b" },
        ]}
      />,
    );

    expect(
      screen.getByRole("heading", { name: "Recoverability distribution" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Recoverable")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
  });

  it("provides a screen-reader trend table", () => {
    render(<CompensationTrendChart points={[trendPointFixture()]} />);

    expect(
      screen.getByRole("table", { name: "Compensation outcomes data" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "Succeeded" }),
    ).toBeInTheDocument();
  });
});
