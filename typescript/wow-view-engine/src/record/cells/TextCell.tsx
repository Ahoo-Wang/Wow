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

import { useEffect, useState } from 'react';
import { CheckIcon, CopyIcon } from 'lucide-react';
import { Button } from '../../components/ui/button.js';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '../../components/ui/tooltip.js';
import { cn } from '../../lib/utils.js';
import { formatRecordValue, recordValueText } from '../recordValueFormat.js';

export interface TextCellProps {
  value?: unknown;
  /** Display text only; copying always uses value. */
  text?: string;
  ellipsis?: boolean;
  copyable?: boolean;
  className?: string;
}
function CopyText({ text }: { text: string }) {
  const [status, setStatus] = useState<
    'idle' | 'loading' | 'success' | 'error'
  >('idle');
  useEffect(() => {
    if (status !== 'success') return;
    const timer = setTimeout(() => setStatus('idle'), 2000);
    return () => clearTimeout(timer);
  }, [status]);
  return (
    <>
      <Button
        type="button"
        size="icon-xs"
        variant="ghost"
        aria-label="复制文本"
        disabled={status === 'loading'}
        onClick={() => {
          // onClick 期望 void 返回；同步包装保持既有异步复制流程不变。
          void (async () => {
            setStatus('loading');
            try {
              await navigator.clipboard.writeText(text);
              setStatus('success');
            } catch {
              setStatus('error');
            }
          })();
        }}
      >
        {status === 'success' ? (
          <CheckIcon aria-hidden="true" />
        ) : (
          <CopyIcon aria-hidden="true" />
        )}
      </Button>
      {status === 'success' && (
        <span role="status" className="fve:sr-only">
          已复制
        </span>
      )}
      {status === 'error' && (
        <span role="alert" className="fve:text-xs fve:text-destructive">
          复制失败，请重试
        </span>
      )}
    </>
  );
}
export function TextCell({
  value,
  text,
  ellipsis = false,
  copyable = false,
  className,
}: TextCellProps) {
  const display = text ?? formatRecordValue(value);
  const raw = recordValueText(value);
  return (
    <span
      className={cn(
        'fve-root fve:inline-flex fve:min-w-0 fve:max-w-full fve:items-center fve:gap-1',
        className,
      )}
    >
      {ellipsis ? (
        <TooltipProvider>
          <Tooltip>
            <TooltipTrigger
              render={<span tabIndex={0} />}
              aria-label={display}
              className="fve:min-w-0 fve:truncate fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-ring"
            >
              {display}
            </TooltipTrigger>
            <TooltipContent className="fve:min-w-0 fve:whitespace-pre-wrap fve:break-words">
              {display}
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      ) : (
        <span
          className="fve:min-w-0 fve:whitespace-pre-wrap fve:break-words"
          title={display}
        >
          {display}
        </span>
      )}
      {copyable && raw !== '' && <CopyText key={raw} text={raw} />}
    </span>
  );
}
