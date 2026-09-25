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

import { createRequire } from "node:module";
import { expect, type Page } from "@playwright/test";

const require = createRequire(import.meta.url);

/** axe-core's browser build, injected as the page's own script. */
const AXE = require.resolve("axe-core/axe.min.js");

/** One rule broken, where and how badly, as a failed assertion lists it. */
type Violation = { id: string; impact: string | null; targets: string[] };

/**
 * Runs axe-core over the page as it stands against WCAG 2.0 and 2.1, A and
 * AA (criterion 5 of the rebuild proposal), and fails on any violation,
 * naming the rule and the first elements that break it.
 */
export async function expectNoAxeViolations(page: Page, where: string) {
  // A colour in the middle of a transition — the view list's current item
  // fading in — is no colour anyone reads; judge the page at rest.
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every((animation) => animation.playState !== "running"),
  );
  await page.addScriptTag({ path: AXE });
  const violations: Violation[] = await page.evaluate(async () => {
    const axe = (
      window as unknown as {
        axe: {
          run(
            context: Document,
            options: unknown,
          ): Promise<{
            violations: {
              id: string;
              impact: string | null;
              nodes: { target: string[] }[];
            }[];
          }>;
        };
      }
    ).axe;
    const { violations: found } = await axe.run(document, {
      runOnly: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"],
    });
    return found.map(({ id, impact, nodes }) => ({
      id,
      impact,
      targets: nodes.slice(0, 3).map(({ target }) => target.join(" ")),
    }));
  });
  expect(violations, `axe on ${where}`).toEqual([]);
}
