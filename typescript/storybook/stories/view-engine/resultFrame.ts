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
 * How a record's result is framed on screen (D69): the card a theme's
 * `result-card` draws on a workbench, or nothing of its own.
 */

/**
 * A token as the element resolves it: a probe inside the element takes the
 * value, so the mode and the preset are the element's own.
 */
export function resolvedColor(inside: HTMLElement, value: string): string {
  const probe = inside.appendChild(document.createElement('div'));
  probe.style.backgroundColor = value;
  const color = getComputedStyle(probe).backgroundColor;
  probe.remove();
  return color;
}

/** A shadow token as the element resolves it. */
export function resolvedShadow(inside: HTMLElement, value: string): string {
  const probe = inside.appendChild(document.createElement('div'));
  probe.style.boxShadow = value;
  const shadow = getComputedStyle(probe).boxShadow;
  probe.remove();
  return shadow;
}

/** A length token as the element resolves it, in px. */
export function resolvedLength(inside: HTMLElement, value: string): string {
  const probe = inside.appendChild(document.createElement('div'));
  probe.style.width = value;
  probe.style.position = 'absolute';
  const width = getComputedStyle(probe).width;
  probe.remove();
  return width;
}

/**
 * Every element between a record table and the surface it stands in —
 * a board's panel, an embed's root — that draws a card of its own: a
 * shadow or a rounded corner. A record view that is not a workbench's
 * page draws none: it already stands in the card its host gave it.
 */
export function cardsAround(table: HTMLElement, stop: HTMLElement): string[] {
  const found: string[] = [];
  for (
    let element = table.parentElement;
    element && element !== stop;
    element = element.parentElement
  ) {
    const style = getComputedStyle(element);
    if (style.boxShadow !== 'none' || style.borderTopLeftRadius !== '0px')
      found.push(
        `${element.dataset.slot ?? element.tagName}: ${style.boxShadow} / ${style.borderTopLeftRadius}`,
      );
  }
  return found;
}

/**
 * Whether two body rows next to each other are painted alike: no stripe
 * on every other one. A row's ground is read where it is painted, on the
 * row or else on its cells.
 */
export function rowGrounds(table: HTMLElement): [string, string] {
  const [first, second] = table.querySelectorAll<HTMLElement>('tbody tr');
  const ground = (row: HTMLElement) => {
    const own = getComputedStyle(row).backgroundColor;
    if (own !== 'rgba(0, 0, 0, 0)') return own;
    return getComputedStyle(row.querySelector('td')!).backgroundColor;
  };
  return [ground(first!), ground(second!)];
}

/**
 * How far a framed result's bottom stands above its work column's: nothing
 * for the band, which bleeds through the column's padding, and the padding
 * for a card, which stands inside it (D69).
 */
export function resultInset(frame: HTMLElement): number {
  const column = getComputedStyle(frame.parentElement!);
  return (
    parseFloat(column.paddingBottom) +
    parseFloat(getComputedStyle(frame).marginBottom)
  );
}
