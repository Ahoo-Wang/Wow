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

/** What the page is laid out for: paper, a Windows contrast theme. */
export interface EmulatedMedia {
  media?: 'screen' | 'print' | null;
  forcedColors?: 'active' | 'none' | null;
}

/**
 * The media emulation, where the story runs under the test runner
 * (`.storybook/media.ts`, handed over in `.storybook/vitest.setup.ts`).
 * Absent in Storybook's own panel, which cannot tell the browser what it
 * prints to.
 */
export interface StoryMedia {
  emulate(media: EmulatedMedia): Promise<void>;
}

declare global {
  var storybookMedia: StoryMedia | undefined;
}

/**
 * Lays the page out for `media` while `run` runs, and hands it back to the
 * browser after, whatever `run` did. Resolves `false`, running nothing,
 * where there is no runner to ask or the browser cannot pretend this media
 * — `probe` is the query that has to match once it is on — so a story can
 * say so instead of asserting on a layout it never got.
 */
export async function underMedia(
  media: EmulatedMedia,
  probe: string,
  run: () => Promise<void>,
): Promise<boolean> {
  const emulator = globalThis.storybookMedia;
  if (!emulator) return false;
  await emulator.emulate(media);
  try {
    if (!matchMedia(probe).matches) return false;
    await run();
    return true;
  } finally {
    await emulator.emulate({ media: null, forcedColors: null });
  }
}
