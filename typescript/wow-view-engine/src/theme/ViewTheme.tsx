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

import type { ComponentPropsWithRef, CSSProperties } from 'react';
import { cn } from '../lib/utils.js';

export type ViewThemeStyle = CSSProperties & {
  [variable: `--fve-${string}`]: string | number | undefined;
};
export interface ViewThemeProps extends Omit<
  ComponentPropsWithRef<'div'>,
  'style'
> {
  theme?: string;
  appearance?: 'light' | 'dark' | 'system';
  density?: 'comfortable' | 'compact';
  style?: ViewThemeStyle;
  'data-fve-theme'?: string;
  'data-theme'?: string;
  'data-fve-density'?: string;
}
/** Optional scope wrapper; imported CSS themes work without this component. */
export function ViewTheme({
  theme,
  appearance,
  density,
  className,
  ...props
}: ViewThemeProps) {
  return (
    <div
      {...props}
      className={cn(
        'fve-root fve:bg-background fve:text-foreground',
        className,
      )}
      style={{
        ...props.style,
        // Apply only explicit token overrides; unspecified typography keeps inheriting.
        fontFamily:
          props.style?.fontFamily ??
          (props.style?.['--fve-font-family'] != null &&
          props.style['--fve-font-family'] !== ''
            ? 'var(--fve-font-family)'
            : undefined),
        fontSize:
          props.style?.fontSize ??
          (props.style?.['--fve-font-size'] != null &&
          props.style['--fve-font-size'] !== ''
            ? 'var(--fve-font-size)'
            : undefined),
        lineHeight:
          props.style?.lineHeight ??
          (props.style?.['--fve-line-height'] != null &&
          props.style['--fve-line-height'] !== ''
            ? 'var(--fve-line-height)'
            : undefined),
      }}
      data-fve-theme={theme ?? props['data-fve-theme']}
      data-theme={appearance ?? props['data-theme']}
      data-fve-density={density ?? props['data-fve-density']}
    />
  );
}
