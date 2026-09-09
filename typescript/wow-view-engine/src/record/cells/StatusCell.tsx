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
import { cn } from '../../lib/utils.js';
import {
  cellLabel,
  isCellValue,
  type CellOption,
  type CellValue,
} from './cellValue.js';

export type CellTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info';
export interface CellStatusTone {
  value: CellValue;
  tone: CellTone;
}
export interface StatusCellProps {
  value?: CellValue | null;
  options?: readonly CellOption[];
  tones?: readonly CellStatusTone[];
  className?: string;
}
export function StatusCell({
  value,
  options,
  tones,
  className,
}: StatusCellProps) {
  if (!isCellValue(value) || value === '')
    return <span className="fve-root">—</span>;
  const tone =
    tones?.find(item => Object.is(item.value, value))?.tone ?? 'neutral';
  const text = cellLabel(value, options);
  return (
    <span className={cn('fve-root fve:inline-flex fve:max-w-full', className)}>
      <Badge
        variant={
          tone === 'neutral'
            ? 'secondary'
            : tone === 'danger'
              ? 'destructive'
              : tone
        }
        data-tone={tone}
        className="fve:max-w-full"
        title={text}
      >
        <span className="fve:truncate">{text}</span>
      </Badge>
    </span>
  );
}
