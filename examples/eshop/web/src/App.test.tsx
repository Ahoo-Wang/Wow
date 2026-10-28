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

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it } from "vitest";
import { routes } from "@/App";
import { SessionProvider } from "@/shop/session";
import { fakeServices } from "@/test/fakeShop";

function renderApp() {
  const services = fakeServices([
    ["POST", /category\/snapshot\/query$/, () => ({ json: [] })],
    [
      "POST",
      /product\/snapshot\/query$/,
      () => ({ json: { total: 0, exact: true, list: [] } }),
    ],
  ]);
  render(
    <SessionProvider transport={services.transport}>
      <RouterProvider router={createMemoryRouter(routes)} />
    </SessionProvider>,
  );
  return services;
}

describe("the user switcher", () => {
  afterEach(() => localStorage.clear());

  it("acts as the first buyer by default, with the buyer's pages", async () => {
    renderApp();
    expect(screen.getByLabelText("User")).toHaveValue("alice");
    expect(screen.getByRole("link", { name: "Basket" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Catalog admin" })).toBeNull();
    expect(await screen.findByText("No products")).toBeInTheDocument();
  });

  it("switches to the employee, remembers it, and sends the employee's headers", async () => {
    const { received } = renderApp();
    await userEvent.selectOptions(screen.getByLabelText("User"), "erin");
    expect(localStorage.getItem("wow-eshop-web.user")).toBe("erin");
    expect(screen.getByRole("link", { name: "Catalog admin" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Basket" })).toBeNull();
    await waitFor(() =>
      expect(received.at(-1)?.headers).toMatchObject({
        "wow-user": "erin",
        "wow-roles": "employee",
      }),
    );
  });

  it("starts as the user it remembers", () => {
    localStorage.setItem("wow-eshop-web.user", "bob");
    renderApp();
    expect(screen.getByLabelText("User")).toHaveValue("bob");
  });
});
