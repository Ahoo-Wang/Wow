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
  formatRecordDateTime,
  type RecordDateTimeFormat,
} from '../recordValueFormat.js';
import { TextCell } from './TextCell.js';

export interface DateTimeCellProps extends RecordDateTimeFormat {
  value?: string | number | Date | null;
  type?: 'date' | 'datetime';
  timeZone?: string;
  className?: string;
}
export function DateTimeCell({
  value,
  type = 'datetime',
  timeZone,
  locale,
  dateStyle,
  timeStyle,
  className,
}: DateTimeCellProps) {
  return (
    <TextCell
      value={value}
      text={formatRecordDateTime(value, type, timeZone, {
        locale,
        dateStyle,
        timeStyle,
      })}
      className={className}
    />
  );
}
