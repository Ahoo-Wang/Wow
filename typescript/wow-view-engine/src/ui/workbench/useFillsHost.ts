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
import { useLayoutEffect, type RefObject } from 'react';

/** The attribute a workbench wears while it fills a height its host gave. */
export const FILLS_HOST = 'data-fills-host';

/**
 * Whether the host gave the workbench a height, said on its root as
 * `data-fills-host` for `styles.css` to lay the column out by.
 *
 * The root is `h-full`. Inside a parent of definite height that is the
 * parent's height, and the workbench fills it: the result takes what the
 * parts above it leave and the footer sits at the bottom, whatever the rows
 * (the user's 2026-09-23 review, borrowed from the legacy console). Inside a
 * parent whose height is its content it is `auto`, and the workbench is the
 * page-flow column it always was, its table capped at the room left in the
 * viewport (`useViewportFit`).
 *
 * The two cannot be told apart in CSS — a percentage of an indefinite
 * height quietly becomes `auto` — so they are told apart here, by the one
 * question that separates them: does the root's height change when it is
 * sized by its content alone — no height of its own, and no stretch or grow
 * from a grid or flex parent? A root sized by its content answers no. A root sized by
 * its host answers yes: the host's height and the content's differ, taller
 * or shorter. Asked when the root mounts, when its parent resizes and when
 * the window does; the attribute is written onto the element rather than
 * through a render, as the expansion's is, because nothing inside React
 * needs to know.
 *
 * Not while the view is expanded: that is a definite height of its own, and
 * `data-view-expanded` lays the column out already.
 */
export function useFillsHost(root: RefObject<HTMLElement | null>): void {
  useLayoutEffect(() => {
    const element = root.current;
    const parent = element?.parentElement;
    if (!element || !parent || typeof window === 'undefined') return;
    const measure = () => {
      if (element.hasAttribute('data-view-expanded')) return;
      if (givenHeight(element)) element.setAttribute(FILLS_HOST, '');
      else element.removeAttribute(FILLS_HOST);
    };
    measure();
    window.addEventListener('resize', measure);
    if (typeof ResizeObserver === 'undefined')
      return () => window.removeEventListener('resize', measure);
    const observer = new ResizeObserver(measure);
    observer.observe(parent);
    return () => {
      window.removeEventListener('resize', measure);
      observer.disconnect();
      element.removeAttribute(FILLS_HOST);
    };
  }, [root]);
}

/**
 * Whether the element's height is its host's rather than its content's.
 *
 * Content-sized for one reading: no height of its own, and none handed over
 * by the parent either — a grid or flex parent stretches its item, and a
 * column parent may grow it, whatever the item's own height says. The inline
 * style it borrows is put back before anything paints.
 */
function givenHeight(element: HTMLElement): boolean {
  const given = element.getBoundingClientRect().height;
  const { height, alignSelf, flex } = element.style;
  element.style.height = 'auto';
  element.style.alignSelf = 'start';
  element.style.flex = 'none';
  const content = element.getBoundingClientRect().height;
  Object.assign(element.style, { height, alignSelf, flex });
  return Math.abs(given - content) > 1;
}
