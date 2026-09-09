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

import { cn } from '../../lib/utils.js';
import { formatRecordValue } from '../recordValueFormat.js';
import { TextCell } from './TextCell.js';

export interface LinkCellProps {
  value?: unknown;
  text?: string;
  href?: string | null;
  newTab?: boolean;
  className?: string;
}
function safeHref(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.trim()) return;
  try {
    const href = value.trim();
    const url = new URL(href, 'https://view-engine.invalid/');
    if (['http:', 'https:', 'mailto:', 'tel:'].includes(url.protocol))
      return href;
  } catch {
    /* Invalid row data stays readable without navigation. */
  }
}
export function LinkCell({
  value,
  text,
  href,
  newTab = false,
  className,
}: LinkCellProps) {
  const target = safeHref(href === undefined ? value : href);
  const display = text ?? formatRecordValue(value);
  if (!target)
    return <TextCell value={value} text={display} className={className} />;
  return (
    <span
      className={cn(
        'fve-root fve:inline-flex fve:min-w-0 fve:max-w-full',
        className,
      )}
    >
      <a
        href={target}
        target={newTab ? '_blank' : undefined}
        rel={newTab ? 'noopener noreferrer' : undefined}
        title={display}
        className="fve:truncate fve:text-primary fve:underline fve:underline-offset-4 fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-ring"
      >
        {display}
      </a>
    </span>
  );
}
