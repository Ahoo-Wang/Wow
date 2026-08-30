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
import { expect, it } from "vitest";
import DashboardSkeleton from "./DashboardSkeleton.tsx";

it("matches the four-card dashboard hierarchy", () => {
  const { container } = render(<DashboardSkeleton />);

  expect(
    screen.getByRole("status", { name: "Loading dashboard" }),
  ).toBeInTheDocument();
  expect(container.querySelectorAll("[data-slot='card']")).toHaveLength(4);
  expect(container.querySelector(".dashboard-toolbar")).not.toBeNull();
  expect(container.querySelector(".dashboard-overview")).not.toBeNull();
  expect(container.querySelector(".dashboard-activity")).not.toBeNull();
  expect(container.querySelector(".dashboard-health")).not.toBeNull();
  expect(container.querySelector(".dashboard-pressure")).not.toBeNull();
});
