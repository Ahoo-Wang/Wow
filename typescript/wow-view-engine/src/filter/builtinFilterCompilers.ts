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

import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import type {
  FilterCompiler,
  FilterComponentProperties,
} from './filterModel.js';
import { compileBuiltinFilter } from './filterBuiltinCompiler.js';
import {
  isFilterOptionValue,
  readFilterOptions,
} from './filterOptionSource.js';

function selection(multiple: boolean, textOnly = false): FilterCompiler {
  return {
    compile(props, context) {
      if (
        !(multiple ? [Op.IN, Op.NOT_IN] : [Op.EQ, Op.NE]).includes(
          context.operator,
        )
      )
        throw new TypeError('选择器不支持当前操作');
      if (props.selectedOptions !== undefined)
        readFilterOptions(props.selectedOptions);
      const input = multiple
        ? props.values
        : props.value === undefined
          ? undefined
          : [props.value];
      if (input === undefined) return undefined;
      if (
        !Array.isArray(input) ||
        input.some(
          value =>
            !isFilterOptionValue(value) ||
            (textOnly && typeof value !== 'string'),
        )
      )
        throw new TypeError('选择值必须是有效的字符串或数字');
      if (!input.length) return undefined;
      const values = [...new Set(input)];
      return compileBuiltinFilter(
        multiple ? { values } : { value: values[0] },
        context,
      );
    },
    clear(props) {
      const next: FilterComponentProperties = { ...props };
      delete next.value;
      delete next.values;
      delete next.selectedOptions;
      return next;
    },
  };
}
const single = selection(false),
  multi = selection(true);
const range: FilterCompiler = {
  compile(props, context) {
    if (
      context.operator !== Op.BETWEEN ||
      !['date', 'datetime'].includes(context.field?.type ?? '')
    )
      throw new TypeError('日期时间区间需要日期字段与 BETWEEN 操作');
    return compileBuiltinFilter(
      {
        lowerBound: props.lowerBound === '' ? undefined : props.lowerBound,
        upperBound: props.upperBound === '' ? undefined : props.upperBound,
      },
      context,
    );
  },
  clear(props) {
    const next: FilterComponentProperties = { ...props };
    delete next.lowerBound;
    delete next.upperBound;
    return next;
  },
};
function remote(compiler: FilterCompiler): FilterCompiler {
  return {
    ...compiler,
    compile(props, context) {
      if (
        typeof context.options?.source !== 'string' ||
        !context.options.source.trim()
      )
        throw new TypeError('远程选择器需要候选数据源名称');
      return compiler.compile(props, context);
    },
  };
}
const compilers: Readonly<Record<string, FilterCompiler>> = {
  select: single,
  'multi-select': multi,
  'remote-select': remote(single),
  'remote-multi-select': remote(multi),
  'text-values': selection(true, true),
  'datetime-range': range,
};
export function getBuiltinFilterCompiler(
  name: string,
): FilterCompiler | undefined {
  return Object.prototype.hasOwnProperty.call(compilers, name)
    ? compilers[name]
    : undefined;
}
