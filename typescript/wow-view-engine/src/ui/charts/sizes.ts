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
 * What a chart does with a new size of its plot: whatever must happen at
 * once — the library created on the element — and, handed back, the drawing,
 * which waits until every chart given a size in the same frame has done the
 * first part.
 */
export type SizeHandler = (
  width: number,
  height: number,
) => (() => void) | undefined;

const watched = new Map<Element, SizeHandler>();
let observer: ResizeObserver | undefined;

/**
 * One `ResizeObserver` for every chart on the page, so the charts a frame
 * sizes together — a board's panels, all of them once the library arrives —
 * are all created before any is drawn.
 *
 * Creating a chart adds the library's pointer and wheel listeners, and
 * drawing it reads the layout (its tooltip measures where the plot is).
 * WebKit on Linux answers the first layout after a wheel or touch listener
 * comes or goes by walking the whole page for the region those listeners
 * cover, so a chart created and drawn before the next is created paid that
 * walk once per chart: 24 charts on the theme gallery spent 5 s in one
 * frame, ~200 ms each, and the walk grows with the page. Created first and
 * drawn after, they pay it once.
 */
export function watchSize(element: Element, handler: SizeHandler): () => void {
  watched.set(element, handler);
  observer ??= new ResizeObserver(entries => {
    const drawings = entries.flatMap(
      ({ target, contentRect }) =>
        watched.get(target)?.(contentRect.width, contentRect.height) ?? [],
    );
    for (const drawing of drawings) drawing();
  });
  observer.observe(element);
  return () => {
    watched.delete(element);
    observer?.unobserve(element);
    if (watched.size > 0) return;
    // The last chart gone: nothing is kept watching, or alive, for nothing.
    observer?.disconnect();
    observer = undefined;
  };
}
