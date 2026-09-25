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

/** A chain of `depth` nested elements under the body; the innermost one. */
function nested(depth: number) {
  let element: HTMLElement = document.body;
  for (let level = 0; level < depth; level++) {
    element = element.appendChild(document.createElement("div"));
  }
  return element;
}

/**
 * The jsdom the tests run in (patches/jsdom@29.1.1.patch): every chart on a
 * board reads its theme tokens off its element, most of them set nowhere in a
 * test, and an unpatched jsdom walks every ancestor again from each one to
 * say so — O(2^depth), seconds per chart twenty elements deep, which is what
 * timed the overview's tests out. Forty deep, an unpatched read never ends.
 */
describe("the test environment's computed custom properties", () => {
  afterEach(() => {
    document.body.replaceChildren();
    document.body.removeAttribute("style");
  });

  it("reads a custom property set nowhere as empty, forty elements deep", () => {
    const deepest = nested(40);
    const style = getComputedStyle(deepest);
    expect(style.getPropertyValue("--chart-1")).toBe("");
    expect(style.getPropertyValue("--_fve-chart-grid-width")).toBe("");
  });

  it("still inherits one set on an ancestor", () => {
    document.body.style.setProperty("--chart-1", "#123456");
    const deepest = nested(40);
    expect(getComputedStyle(deepest).getPropertyValue("--chart-1")).toBe(
      "#123456",
    );
  });

  it("still inherits a colour", () => {
    document.body.style.setProperty("color", "rgb(1, 2, 3)");
    const deepest = nested(40);
    expect(getComputedStyle(deepest).color).toBe("rgb(1, 2, 3)");
  });
});
