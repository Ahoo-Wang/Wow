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

import { useRef, useState, type RefObject } from 'react';
import { CircleAlertIcon } from 'lucide-react';
import { Button } from '../../components/ui/button.js';
import { Spinner } from '../../components/ui/spinner.js';
import {
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverHeader,
  PopoverTitle,
  PopoverDescription,
} from '../../components/ui/popover.js';
import type { RecordSummaryResult } from '../../contracts/viewModel.js';

function SummaryError({
  label,
  error,
  onRetry,
  fallback,
}: {
  label: string;
  error: string;
  onRetry?: () => void;
  fallback: RefObject<HTMLSpanElement | null>;
}) {
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  return (
    <>
      <span role="alert" className="fve:sr-only">
        {label}汇总失败
      </span>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger
          ref={trigger}
          aria-label={`${label}汇总失败，查看详情`}
          title={`${label}汇总失败`}
          render={
            <Button
              type="button"
              variant="ghost"
              size="sm"
              className="fve:h-6 fve:gap-0.5 fve:px-0 fve:text-xs fve:text-destructive"
            />
          }
        >
          {label}
          <CircleAlertIcon aria-hidden="true" />
        </PopoverTrigger>
        <PopoverContent
          align="start"
          className="fve:w-80 fve:max-w-[calc(100vw-2rem)]"
          finalFocus={() => trigger.current ?? fallback.current ?? false}
        >
          <PopoverHeader>
            <PopoverTitle>{label}汇总失败</PopoverTitle>
            <PopoverDescription className="fve:break-words">
              {error}
            </PopoverDescription>
          </PopoverHeader>
          {onRetry && (
            <Button
              type="button"
              size="sm"
              className="fve:self-start"
              onClick={() => {
                setOpen(false);
                onRetry();
              }}
            >
              重试汇总
            </Button>
          )}
        </PopoverContent>
      </Popover>
    </>
  );
}

export function RecordSummaryScope({
  label,
  result,
  onRetry,
}: {
  label: string;
  result?: RecordSummaryResult;
  onRetry?: () => void;
}) {
  const scope = useRef<HTMLSpanElement>(null);
  return (
    <span
      ref={scope}
      tabIndex={-1}
      aria-label={`${label}汇总状态`}
      title={label === '所有' ? '当前已查询条件下的所有记录' : '当前页记录'}
      className="fve:flex fve:h-6 fve:items-center fve:justify-center fve:gap-0.5 fve:text-xs fve:leading-5 fve:text-muted-foreground fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-ring"
    >
      {result?.status === 'error' ? (
        <SummaryError
          label={label}
          error={result.error ?? '暂时无法完成汇总。'}
          onRetry={onRetry}
          fallback={scope}
        />
      ) : (
        <>
          {label}
          {result?.status === 'loading' && (
            <Spinner
              className="fve:size-3 fve:shrink-0"
              aria-label={`${label}汇总加载中`}
            />
          )}
        </>
      )}
    </span>
  );
}
