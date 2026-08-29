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

import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { enUS } from "react-day-picker/locale";
import { describe, expect, it } from "vitest";
import { Calendar } from "./calendar.tsx";

describe("Calendar", () => {
  it("moves DOM focus with arrow-key day navigation", async () => {
    render(
      <Calendar
        mode="single"
        locale={enUS}
        defaultMonth={new Date(2026, 7, 1)}
      />,
    );
    const august20 = screen.getByRole("button", { name: /August 20/ });
    const august21 = screen.getByRole("button", { name: /August 21/ });
    await act(async () => {
      august20.focus();
    });
    expect(document.activeElement).toBe(august20);

    await act(async () => {
      fireEvent.keyDown(august20, { key: "ArrowRight" });
    });

    await waitFor(() => expect(document.activeElement).toBe(august21));
  });
});
