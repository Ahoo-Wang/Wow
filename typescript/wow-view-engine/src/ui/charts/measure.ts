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
 * How wide a line of tick text is drawn, for deciding whether the category
 * names fit side by side (`categoryFit`). A canvas measures it where there
 * is one; elsewhere — jsdom, a server — it is estimated, a CJK character at
 * a full em and anything else at a little over half.
 */
let context: CanvasRenderingContext2D | null | undefined;

const FONT_SIZE = 12;

export function measureText(text: string, fontFamily = 'sans-serif'): number {
  context ??=
    typeof document === 'undefined'
      ? null
      : document.createElement('canvas').getContext('2d');
  if (context) {
    context.font = `${FONT_SIZE}px ${fontFamily}`;
    return context.measureText(text).width;
  }
  let width = 0;
  for (const char of text)
    width += /[\u2e80-\uffff]/.test(char) ? FONT_SIZE : FONT_SIZE * 0.6;
  return width;
}
