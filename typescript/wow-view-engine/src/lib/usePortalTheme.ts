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

import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
} from 'react';

export interface PortalTheme {
  style: CSSProperties;
  'data-theme'?: 'dark' | 'light';
}

export function usePortalTheme(open?: boolean, defaultOpen = false) {
  const scope = useRef<HTMLSpanElement>(null);
  const [theme, setTheme] = useState<PortalTheme>({ style: {} });
  const [uncontrolledOpen, setUncontrolledOpen] = useState(defaultOpen);
  // Shared by open events and the layout effect; identity controls theme recapture.
  const readTheme = useCallback(() => {
    if (!scope.current) return;
    const computed = getComputedStyle(scope.current);
    const variables = Object.fromEntries(
      Array.from(computed)
        // Tailwind runtime variables belong to each element's utilities, not its theme.
        .filter(
          name => name.startsWith('--fve-') && !name.startsWith('--fve-tw-'),
        )
        .map(name => [name, computed.getPropertyValue(name)]),
    );
    const marker = scope.current.closest(
      ".dark, [data-theme='dark'], [data-theme='light']",
    );
    setTheme({
      'data-theme': marker
        ? marker.getAttribute('data-theme') === 'dark' ||
          (marker.getAttribute('data-theme') !== 'light' &&
            marker.classList.contains('dark'))
          ? 'dark'
          : 'light'
        : undefined,
      style: {
        ...variables,
        colorScheme: computed.colorScheme,
        fontFamily: computed.fontFamily,
        fontSize: computed.fontSize,
        lineHeight: computed.lineHeight,
      },
    });
  }, []);
  // Controlled openings do not emit the primitive's onOpenChange event.
  useLayoutEffect(readTheme, [readTheme, open]);
  useLayoutEffect(() => {
    if (!(open ?? uncontrolledOpen) || !scope.current) return;
    const observer = new MutationObserver(readTheme);
    for (
      let element: HTMLElement | null = scope.current;
      element;
      element = element.parentElement
    )
      observer.observe(element, {
        attributes: true,
        attributeFilter: ['class', 'data-theme', 'style'],
      });
    return () => observer.disconnect();
  }, [readTheme, open, uncontrolledOpen]);
  const captureTheme = useCallback(
    (nextOpen: boolean) => {
      setUncontrolledOpen(nextOpen);
      if (nextOpen) readTheme();
    },
    [readTheme],
  );
  return { scope, theme, captureTheme };
}
