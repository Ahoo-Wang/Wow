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
  RetryDistributionChart,
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

  it("renders distribution counts and percentages as a CSS ratio bar", () => {
    render(
      <DistributionChart
        title="Recoverability"
        description="Now"
        data={[
          { key: "unknown", label: "Unknown", count: 3, color: "#f59e0b" },
          {
            key: "unrecoverable",
            label: "Unrecoverable",
            count: 7,
            color: "#dc2626",
          },
        ]}
      />,
    );

    expect(
      screen.getByRole("img", {
        name: "Recoverability: Unknown 3 (30%), Unrecoverable 7 (70%)",
      }),
    ).toBeInTheDocument();
    expect(document.querySelector(".recharts-sector")).toBeNull();
    expect(screen.getByText("3 (30%)")).toBeInTheDocument();
    expect(screen.getByText("7 (70%)")).toBeInTheDocument();
  });

  it("renders zero distribution percentages without invalid numbers", () => {
    render(
      <DistributionChart
        title="Retry distribution"
        description="Current active snapshots"
        data={[
          { key: "0", label: "0 retries", count: 0, color: "#2563eb" },
          { key: "1-2", label: "1-2 retries", count: 0, color: "#2563eb" },
        ]}
      />,
    );

    expect(screen.getAllByText("0 (0%)")).toHaveLength(2);
    expect(document.body).not.toHaveTextContent(/NaN|Infinity/);
  });

  it("renders retry buckets as labeled bars without rounding nonzero data to zero", () => {
    render(
      <RetryDistributionChart
        data={[
          { key: "0", label: "0 retries", count: 488_710, color: "#64748b" },
          {
            key: "1–2",
            label: "1–2 retries",
            count: 616,
            color: "#2563eb",
          },
          {
            key: "3–5",
            label: "3–5 retries",
            count: 5_002,
            color: "#f59e0b",
          },
          {
            key: "6+",
            label: "6+ retries",
            count: 33_421,
            color: "#dc2626",
          },
        ]}
      />,
    );

    expect(
      screen.getByRole("heading", { name: "Retry distribution" }),
    ).toBeInTheDocument();
    expect(screen.getByText("0 retries")).toBeInTheDocument();
    expect(screen.getByText("488710 (93%)")).toBeInTheDocument();
    expect(screen.getByText("616 (<1%)")).toBeInTheDocument();
    expect(screen.getByText("33421 (6%)")).toBeInTheDocument();
    expect(document.querySelectorAll(".recharts-bar-rectangle")).toHaveLength(
      4,
    );
    expect(document.querySelector("[data-slot='chart']")).toHaveClass(
      "h-36",
      "py-2",
    );
    expect(document.querySelector(".recharts-sector")).toBeNull();
  });

  it("shows all trend series and provides a screen-reader data table", () => {
    render(<CompensationTrendChart points={[trendPointFixture()]} />);

    for (const label of [
      "New failures",
      "Prepared",
      "Retried failed",
      "Succeeded",
    ]) {
      expect(
        screen.getAllByText(label).some((element) => !element.closest("table")),
      ).toBe(true);
    }
    expect(
      screen.getByRole("table", { name: "Compensation outcomes data" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "Succeeded" }),
    ).toBeInTheDocument();
    expect(document.querySelector(".recharts-tooltip-wrapper")).not.toBeNull();
    expect(document.querySelector("[data-slot='chart']")).toHaveClass(
      "h-36",
      "py-2",
    );
  });
});
