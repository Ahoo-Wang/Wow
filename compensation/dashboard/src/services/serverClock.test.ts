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

import { afterEach, describe, expect, it } from "vitest";
import {
  resetServerClock,
  serverNow,
  synchronizeServerClock,
} from "./serverClock.ts";

describe("serverClock", () => {
  afterEach(resetServerClock);

  it("advances from the server timestamp using monotonic elapsed time", () => {
    synchronizeServerClock(1_000_000, 50);

    expect(serverNow(1_550)).toBe(1_001_500);
  });

  it("does not move backwards when the monotonic source regresses", () => {
    synchronizeServerClock(1_000_000, 100);

    expect(serverNow(90)).toBe(1_000_000);
  });

  it("returns whole epoch milliseconds for query contracts", () => {
    synchronizeServerClock(1_000_000, 50.25);

    expect(serverNow(51.9)).toBe(1_000_001);
  });

  it("rejects invalid runtime responses", () => {
    expect(() => synchronizeServerClock(Number.NaN, 0)).toThrow(
      "invalid serverTime",
    );
    expect(serverNow(1)).toBeUndefined();
  });
});
