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

import { useState } from 'react';
import { XIcon } from 'lucide-react';
import {
  InputGroupInput,
  InputGroupButton,
} from '../components/ui/input-group.js';
export interface FilterTextValuesProps {
  value?: readonly string[];
  rawText?: string;
  onRawTextChange?(text: string): void;
  onValueChange(values: string[], rawText: string): void;
  onValidityChange?(valid: boolean, message?: string): void;
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
}
export function FilterTextValues({
  value = [],
  onValueChange,
  rawText,
  onRawTextChange,
  onValidityChange,
  label,
  disabled,
  invalid,
  errorId,
}: FilterTextValuesProps) {
  const [localText, setText] = useState('');
  const text = rawText ?? localText;
  const [composing, setComposing] = useState(false);
  function commit(input: string) {
    onValueChange(
      [
        ...new Set([
          ...value,
          ...input
            .split(/[\r\n,，;；]+/)
            .map(part => part.trim())
            .filter(Boolean),
        ]),
      ],
      '',
    );
    setText('');
    onValidityChange?.(true);
  }
  return (
    <span className="fve-root fve:inline-flex fve:max-w-full fve:flex-wrap fve:items-center fve:gap-1">
      {value.map(item => (
        <span
          key={item}
          className="fve:inline-flex fve:max-w-48 fve:items-center fve:rounded fve:bg-muted fve:px-1"
        >
          <span className="fve:truncate" title={item}>
            {item}
          </span>
          <InputGroupButton
            size="icon-xs"
            aria-label={`移除${item}`}
            disabled={disabled}
            onClick={() =>
              onValueChange(
                value.filter(other => other !== item),
                text,
              )
            }
          >
            <XIcon aria-hidden="true" />
          </InputGroupButton>
        </span>
      ))}
      <InputGroupInput
        aria-label={label}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? errorId : undefined}
        value={text}
        disabled={disabled}
        className="fve:w-36"
        placeholder="输入或粘贴多个值"
        onChange={event => {
          setText(event.target.value);
          onRawTextChange?.(event.target.value);
          onValidityChange?.(!event.target.value.trim(), '请按回车确认输入');
        }}
        onCompositionStart={() => setComposing(true)}
        onCompositionEnd={() => setComposing(false)}
        onKeyDown={event => {
          if (
            event.key === 'Enter' &&
            (composing || event.nativeEvent.isComposing || text.trim())
          ) {
            event.preventDefault();
            event.stopPropagation();
            if (!composing && !event.nativeEvent.isComposing) commit(text);
          }
        }}
        onPaste={event => {
          if (disabled || composing) return;
          event.preventDefault();
          const { value, selectionStart, selectionEnd } = event.currentTarget;
          commit(
            value.slice(0, selectionStart ?? value.length) +
              event.clipboardData.getData('text') +
              value.slice(selectionEnd ?? value.length),
          );
        }}
      />
    </span>
  );
}
