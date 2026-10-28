/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
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
import { CommandRejectedError, WowProblemError } from "@ahoo-wang/wow-client";
import { describe, expect, it } from "vitest";
import { result } from "@/test/fakeShop";
import { ProblemAlert, problemOf } from "./problems";

describe("a problem", () => {
  it("is read from an RFC 9457 problem with its code and violations", () => {
    const error = new WowProblemError({
      type: "urn:wow:error:eshop-catalog.brand.name-taken",
      title: "Conflict",
      status: 409,
      code: "eshop-catalog.brand.name-taken",
      detail: "The name Wow is taken.",
      violations: [{ path: "name", code: "taken", message: "is taken" }],
    });
    expect(problemOf(error)).toEqual({
      title: "Conflict",
      status: 409,
      code: "eshop-catalog.brand.name-taken",
      detail: "The name Wow is taken.",
      violations: [{ path: "name", code: "taken", message: "is taken" }],
    });
  });

  it("is read from a result rejected after admission", () => {
    expect(
      problemOf(
        result({
          status: "rejected",
          error: { code: "eshop-inventory.stock.insufficient", message: "Short" },
        }),
      ),
    ).toEqual({
      title: "Rejected",
      code: "eshop-inventory.stock.insufficient",
      detail: "Short",
      violations: [],
    });
    expect(problemOf(result({ status: "failed", error: null })).title).toBe("Failed");
  });

  it("is read from a rejection: the problem it came in, else its result", () => {
    const rejected = result({
      status: "rejected",
      error: { code: "eshop-basket.basket.empty", message: "Empty" },
    });
    const problem = new WowProblemError({
      type: "urn:wow:error:eshop-basket.basket.empty",
      title: "Unprocessable",
      status: 422,
      code: "eshop-basket.basket.empty",
      detail: "The basket is empty.",
    });
    expect(
      problemOf(
        new CommandRejectedError(rejected, {
          problem: problem.problem,
          cause: problem,
        }),
      ),
    ).toMatchObject({
      title: "Unprocessable",
      status: 422,
      code: "eshop-basket.basket.empty",
      detail: "The basket is empty.",
    });
    expect(problemOf(new CommandRejectedError(rejected))).toEqual({
      title: "Rejected",
      code: "eshop-basket.basket.empty",
      detail: "Empty",
      violations: [],
    });
  });

  it("is anything else's message", () => {
    expect(problemOf(new TypeError("Failed to fetch"))).toEqual({
      title: "Request failed",
      detail: "Failed to fetch",
      violations: [],
    });
    expect(problemOf("down").detail).toBe("down");
  });

  it("is shown as an alert", () => {
    render(
      <ProblemAlert
        error={
          new WowProblemError({
            type: "about:blank",
            title: "Forbidden",
            status: 403,
            code: "wow.command.unauthorized",
            violations: [{ path: "price", code: "min", message: "too low" }],
          })
        }
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Forbidden (403)");
    expect(screen.getByTestId("problem-code")).toHaveTextContent(
      "wow.command.unauthorized",
    );
    expect(screen.getByText(/too low/)).toBeInTheDocument();
  });
});
