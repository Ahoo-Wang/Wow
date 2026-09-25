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

/**
 * The screenshot baselines (themes.md 5.5): an element compared with its
 * stored picture, `typescript/storybook/baselines/<story file>/<name>-<browser>.png`.
 *
 * Only the `visual` Vitest project compares (`.storybook/vitest.visual.ts`
 * hands the matcher over), and it runs only against a browser in
 * Playwright's Linux container (`scripts/linux-browser.mjs`), so the pictures
 * are that image's fonts and rasteriser — one set for every machine and for
 * CI. Everywhere else — the interaction runs, Storybook's own panel — this
 * does nothing: a story says where its pictures are taken, and still runs as
 * an interaction test. A story is in the `visual` project by its `'visual'`
 * tag (a literal: Storybook reads tags without running the file).
 */
export type StoryScreenshot = (element: Element, name: string) => Promise<void>;

declare global {
  var storybookScreenshot: StoryScreenshot | undefined;
}

export async function matchScreenshot(
  element: Element,
  name: string,
): Promise<void> {
  await globalThis.storybookScreenshot?.(element, name);
}
