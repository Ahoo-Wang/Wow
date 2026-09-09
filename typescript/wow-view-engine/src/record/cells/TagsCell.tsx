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

import { Badge } from '../../components/ui/badge.js';
import { Button } from '../../components/ui/button.js';
import {
  Popover,
  PopoverContent,
  PopoverTitle,
  PopoverTrigger,
} from '../../components/ui/popover.js';
import { cn } from '../../lib/utils.js';
import {
  cellLabel,
  isCellValue,
  type CellValue,
  type CellOption,
} from './cellValue.js';

export interface TagsCellProps {
  value?: CellValue | readonly CellValue[] | null;
  options?: readonly CellOption[];
  maxVisible?: number;
  className?: string;
}
export function TagsCell({
  value,
  options,
  maxVisible = 2,
  className,
}: TagsCellProps) {
  if (!Number.isInteger(maxVisible) || maxVisible < 1)
    throw new TypeError('maxVisible 必须是正整数');
  const values =
    value === null || value === undefined || value === ''
      ? []
      : Array.isArray(value)
        ? value
        : [value];
  const unique = values.every(isCellValue)
    ? [...new Set(values)].filter(value => value !== '')
    : [];
  function badge(value: CellValue) {
    const label = cellLabel(value, options);
    return (
      <Badge
        key={JSON.stringify(value)}
        variant="secondary"
        className="fve:max-w-full"
        title={label}
      >
        <span className="fve:truncate">{label}</span>
      </Badge>
    );
  }
  return (
    <span
      className={cn(
        'fve-root fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center fve:gap-1',
        className,
      )}
    >
      {unique.length ? unique.slice(0, maxVisible).map(badge) : '—'}
      {unique.length > maxVisible && (
        <Popover>
          <PopoverTrigger
            aria-label={`查看全部 ${unique.length} 个标签`}
            render={<Button type="button" variant="ghost" size="xs" />}
          >
            +{unique.length - maxVisible}
          </PopoverTrigger>
          <PopoverContent
            align="start"
            className="fve:max-w-(--available-width)"
          >
            <PopoverTitle>全部标签（{unique.length}）</PopoverTitle>
            <div
              className="fve:flex fve:max-h-64 fve:flex-wrap fve:gap-1 fve:overflow-auto"
              role="region"
              aria-label="全部标签"
              tabIndex={0}
            >
              {unique.map(value => (
                <span
                  key={JSON.stringify(value)}
                  className="fve:block fve:w-full fve:whitespace-pre-wrap fve:break-words"
                >
                  {cellLabel(value, options)}
                </span>
              ))}
            </div>
          </PopoverContent>
        </Popover>
      )}
    </span>
  );
}
