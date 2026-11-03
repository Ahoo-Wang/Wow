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

import { describe, expect, it } from "vitest";
import { isDuration } from "./kotlinDuration.ts";

/*
 * What the ops service answers (`Parked.isDuration`: kotlin-stdlib 2.4.21's
 * `Duration.parseOrNull` of the trimmed text, finite and not negative),
 * asked of the stdlib itself for each text below.
 */
const TAKEN = [
  // The two formats the form names.
  "PT3M",
  "3m",
  "1h 30m",
  "P1D",
  "P1DT2H",
  "PT1H1M1S",
  "1d 2h 3m 4s 5ms 6us 7ns",
  // Kotlin's format needs no spaces, and takes more than one.
  "1h30m",
  "1d2h",
  "1h  30m",
  " 3m ",
  " 3m　",
  // A fraction on the last component only; ISO's on seconds only.
  "1.5s",
  "1.5h",
  "1h 1.5m",
  "0.5s",
  "PT1.5S",
  "PT1M30.5S",
  "1.123456789012345678h",
  // Signs: a component's own in ISO, the whole's in both.
  "PT+3M",
  "+PT3M",
  "+3m",
  "+(1h 30m)",
  "-0s",
  "-PT-1S",
  "PT1H-30M",
  // Zero, and the smallest units.
  "0s",
  "PT0S",
  "2ms",
  "3us",
  "4ns",
  "1us 1ns",
  "PT0.000000001S",
  // The largest it still holds: just under `MAX_MILLIS`.
  "P53375995583D",
  "4611686018427387902ms",
  "4611686018427387902ms 999999ns",
];

const REFUSED = [
  "",
  "soon",
  // Out of order, or a unit twice.
  "1m 1h",
  "3m 3m",
  "PT1M1H",
  "PT1H-1H",
  "1ns 1us",
  // A fraction not last, on a unit ISO does not allow it on, or empty.
  "1.5h 30m",
  "PT1.5M",
  "P1.5D",
  ".5s",
  "5.s",
  "1.d",
  "PT.5S",
  "PT5.S",
  "1h .5m",
  // Incomplete, or not a unit either format knows.
  "PT",
  "P",
  "P1DT",
  "3",
  "0",
  "m",
  "3 m",
  "3M",
  "3H",
  "pt3m",
  "PT3m",
  "P1W",
  "P1Y",
  "1_000s",
  "1e3s",
  "１s",
  // A sign makes spaces need parentheses; parentheses need a sign.
  "+3m 2s",
  "(1h 30m)",
  // Negative.
  "-3m",
  "-PT3M",
  "-(1h 30m)",
  "PT-0.000000001S",
  // Infinite, or past `MAX_MILLIS` on the way.
  "Infinity",
  "INF",
  "-Infinity",
  "P53375995584D",
  "P53375995584DT-100000000H",
  "P53375995583DT23H",
  "4611686018427387903ms",
  "4611686018427387902ms 1000000ns",
  "106751991167300d",
  "9999999999999999s",
  "99999999999999999999s",
  "PT99999999999999999999S",
  "9223372036854775808ms",
];

describe("a duration as the ops service reads it", () => {
  it.each(TAKEN)("takes %j", (value) => {
    expect(isDuration(value)).toBe(true);
  });

  it.each(REFUSED)("refuses %j", (value) => {
    expect(isDuration(value)).toBe(false);
  });
});
