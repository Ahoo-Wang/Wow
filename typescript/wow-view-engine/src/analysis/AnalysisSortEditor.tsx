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

import { type ReactNode, useId } from 'react';
import { ANALYSIS_LIMITS } from './analysisCapabilities.js';
import { canAddAnalysisSort } from './analysisSort.js';
import { analysisOutputs } from './analysisEditorLabels.js';
import { Choice } from './AnalysisComponentChoice.js';
import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { Button } from '../components/ui/button.js';
import { Input } from '../components/ui/input.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisViewConfig,
  AnalysisCompileContext,
} from './analysisModel.js';

export function AnalysisSortEditor(props: {
  value: DeepReadonly<AnalysisViewConfig>;
  context: AnalysisCompileContext;
  disabled: boolean;
  /** 父层持有 invalidLimit，供 details 的 open 计算使用；此处仅用于 aria-invalid。 */
  invalidLimit: boolean;
  update(patch: Partial<AnalysisViewConfig>): void;
}): ReactNode {
  const { value, context, disabled, invalidLimit, update } = props;
  const limitHintId = useId();
  const maxLimit =
    context.capability.limits?.maxLimit ?? ANALYSIS_LIMITS.maxLimit;
  const outputs = analysisOutputs(value);
  const maxSort = context.capability.limits?.maxSort ?? ANALYSIS_LIMITS.maxSort;
  function sortOptions(index = value.sort.length) {
    const remaining = value.sort.filter((_, i) => i !== index);
    // The compiler appends every dimension not already explicitly sorted.
    return outputs.filter(
      output =>
        output.alias === value.sort[index]?.alias ||
        (!remaining.some(sort => sort.alias === output.alias) &&
          canAddAnalysisSort(
            value.dimensions,
            remaining,
            output.alias,
            maxSort,
          )),
    );
  }
  const nextSortOutput = sortOptions()[0];
  return (
    <>
      <fieldset
        disabled={disabled}
        className="fve:flex fve:min-w-0 fve:flex-col fve:gap-2"
      >
        <legend>结果排序</legend>
        {value.sort.map((sort, index) => (
          <div
            key={sort.alias}
            className="fve:flex fve:flex-wrap fve:items-end fve:gap-2"
          >
            <Choice
              label={`排序 ${index + 1} 输出`}
              value={sort.alias}
              options={sortOptions(index).map(output => ({
                value: output.alias,
                label: output.title,
              }))}
              disabled={disabled}
              onChange={alias =>
                update({
                  sort: value.sort.map((item, i) =>
                    i === index ? { ...item, alias } : item,
                  ),
                })
              }
            />
            <Choice
              label={`排序 ${index + 1} 方向`}
              value={sort.direction}
              options={[
                { value: SortDirection.ASC, label: '升序' },
                { value: SortDirection.DESC, label: '降序' },
              ]}
              disabled={disabled}
              onChange={direction =>
                update({
                  sort: value.sort.map((item, i) =>
                    i === index
                      ? { ...item, direction: direction as SortDirection }
                      : item,
                  ),
                })
              }
            />
            <Button
              variant="ghost"
              disabled={disabled}
              aria-label={`删除排序 ${index + 1}`}
              onClick={() =>
                update({ sort: value.sort.filter((_, i) => i !== index) })
              }
            >
              删除
            </Button>
          </div>
        ))}
        <div className="fve:flex fve:flex-wrap fve:gap-2">
          <Button
            variant="outline"
            disabled={disabled || !value.dimensions.length || !nextSortOutput}
            onClick={() => {
              if (nextSortOutput)
                update({
                  sort: [
                    ...value.sort,
                    {
                      alias: nextSortOutput.alias,
                      direction: SortDirection.ASC,
                    },
                  ],
                });
            }}
          >
            添加排序
          </Button>
          <Button
            variant="ghost"
            disabled={disabled || !value.sort.length}
            onClick={() => update({ sort: [] })}
          >
            清除排序
          </Button>
        </div>
      </fieldset>
      <label className="fve:flex fve:min-w-0 fve:max-w-full fve:flex-col fve:gap-1">
        最多结果行数
        <span
          id={limitHintId}
          className="fve:text-sm fve:text-muted-foreground"
        >
          请输入 1 至 {maxLimit} 的整数
        </span>
        <Input
          aria-label="最多结果行数"
          inputMode="numeric"
          value={value.limit}
          disabled={disabled}
          aria-invalid={invalidLimit || undefined}
          aria-describedby={limitHintId}
          onChange={event => {
            const raw = event.target.value;
            const number = Number(raw);
            update({
              limit:
                /^[1-9]\d*$/.test(raw) &&
                Number.isSafeInteger(number) &&
                number > 0
                  ? number
                  : raw,
            });
          }}
        />
      </label>
    </>
  );
}
