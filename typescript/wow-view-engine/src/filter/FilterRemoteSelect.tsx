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
import { DEFAULT_CURSOR_SIZE, MAX_CURSOR_SIZE } from '@ahoo-wang/fetcher-wow';
import { FilterChoiceSelect } from './FilterChoiceSelect.js';
import { useRemoteFilterOptions } from './useRemoteFilterOptions.js';
import type {
  FilterOptionSource,
  FilterOptionValue,
  FilterOptionItem,
} from './filterOptionSource.js';
import { Spinner } from '../components/ui/spinner.js';
import { Button } from '../components/ui/button.js';
interface FilterRemoteSelectCommonProps {
  source: FilterOptionSource;
  selectedOptions?: readonly FilterOptionItem[];
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  errorId?: string;
  inline?: boolean;
  debounceMs?: number;
  pageSize?: number;
}
export type FilterRemoteSelectProps = FilterRemoteSelectCommonProps &
  (
    | {
        multiple: true;
        values?: readonly FilterOptionValue[];
        value?: never;
        onValueChange(
          values: FilterOptionValue[],
          selected: FilterOptionItem[],
        ): void;
      }
    | {
        multiple?: false;
        value?: FilterOptionValue | null;
        values?: never;
        onValueChange(
          value: FilterOptionValue | null,
          selected: FilterOptionItem[],
        ): void;
      }
  );
export function FilterRemoteSelect(props: FilterRemoteSelectProps) {
  const [session, setSession] = useState({
    source: props.source,
    generation: 0,
  });
  if (session.source !== props.source)
    setSession({ source: props.source, generation: session.generation + 1 });
  if (
    !props.source ||
    typeof props.source.search !== 'function' ||
    typeof props.source.resolve !== 'function'
  )
    throw new TypeError('未注册候选数据源');
  const size = props.pageSize ?? DEFAULT_CURSOR_SIZE,
    delay = props.debounceMs ?? 300;
  if (
    !Number.isInteger(size) ||
    size < 1 ||
    size > MAX_CURSOR_SIZE ||
    !Number.isFinite(delay) ||
    delay < 0
  )
    throw new TypeError('远程候选的分页大小或防抖时间无效');
  return (
    <RemoteSession key={`${session.generation}:${size}:${delay}`} {...props} />
  );
}
function RemoteSession(props: FilterRemoteSelectProps) {
  const {
    source,
    selectedOptions = [],
    label,
    disabled,
    inline,
    debounceMs,
    pageSize,
  } = props;
  const selected = props.multiple
    ? [...new Set(props.values ?? [])]
    : props.value === null || props.value === undefined
      ? []
      : [props.value];
  const remote = useRemoteFilterOptions(source, selected, debounceMs, pageSize);
  const snapshots = selected.map(id => {
    const item = remote.labels.find(item => item.value === id) ??
      selectedOptions.find(item => item.value === id) ?? {
        value: id,
        label: String(id),
      };
    return remote.missing.includes(id)
      ? { ...item, label: `${item.label}（已不可用）` }
      : item;
  });
  return (
    <FilterChoiceSelect
      invalid={props.invalid}
      errorId={props.errorId}
      options={remote.options}
      selectedOptions={snapshots}
      values={selected}
      multiple={props.multiple}
      onValuesChange={(ids, items) => {
        const snapshots = items.map(item => {
          if (!selected.includes(item.value)) return item;
          return (
            selectedOptions.find(other => other.value === item.value) ??
            remote.labels.find(other => other.value === item.value) ?? {
              value: item.value,
              label: String(item.value),
            }
          );
        });
        if (props.multiple) props.onValueChange(ids, snapshots);
        else props.onValueChange(ids[0] ?? null, snapshots);
      }}
      label={label}
      disabled={disabled}
      inline={inline}
      onOpenChange={remote.open}
      search={{
        value: remote.search,
        onChange: remote.change,
        onCompositionStart: remote.compositionStart,
        onCompositionEnd: remote.compositionEnd,
      }}
      emptyText={remote.loading || remote.failed ? '' : '没有匹配选项'}
      footer={
        <div className="fve:flex fve:flex-col fve:gap-1 fve:p-2 fve:text-sm">
          {remote.loading && <Spinner aria-label="正在加载候选" />}
          {remote.failed && (
            <div role="alert">
              候选加载失败
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled || remote.loading}
                onClick={remote.retry}
              >
                重试候选
              </Button>
            </div>
          )}
          {!remote.failed && remote.nextCursor && (
            <Button
              variant="outline"
              size="sm"
              disabled={disabled || remote.loading}
              onClick={remote.more}
            >
              加载更多
            </Button>
          )}
          {remote.hydrating && <Spinner aria-label="正在更新已选标签" />}
          {remote.hydrationFailed && (
            <div role="alert">
              已选项回填失败
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled || remote.hydrating}
                onClick={remote.retryLabels}
              >
                重试回填
              </Button>
            </div>
          )}
        </div>
      }
    />
  );
}
