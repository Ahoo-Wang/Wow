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

import { getBuiltinFilterRegistration } from './builtinFilterRegistrations.js';
import { copy } from '../lib/snapshot.js';
import type {
  FilterComponentProperties,
  FilterEditorReference,
  FilterMode,
} from './filterModel.js';
import type {
  FilterPanelProps,
  FilterRegistration,
} from './filterReactTypes.js';
import type { FilterNodeLocation } from './filterTree.js';
import { message } from './filterPanelUtils.js';

export function resolveFilterEditor(
  location: FilterNodeLocation,
  props: FilterPanelProps,
  mode: FilterMode,
): {
  props?: FilterComponentProperties;
  reference?: FilterEditorReference;
  registration?: FilterRegistration;
  options?: FilterEditorReference['options'];
  error?: string;
} {
  const { node, fields } = location;
  const field = fields.find(field => field.field === node.field);
  const reference = node.component;
  try {
    const properties = node.props;
    if (reference.name === 'builtin') return { props: properties, reference };
    const registration =
      props.extensions?.filters &&
      Object.prototype.hasOwnProperty.call(
        props.extensions.filters,
        reference.name,
      )
        ? props.extensions.filters[reference.name]
        : getBuiltinFilterRegistration(reference.name);
    if (!registration) return { error: `未注册筛选器：${reference.name}` };
    if (
      registration === getBuiltinFilterRegistration(reference.name) &&
      ['remote-select', 'remote-multi-select'].includes(reference.name)
    ) {
      const name = reference.options?.source;
      const source =
        typeof name === 'string' &&
        props.extensions?.optionSources &&
        Object.prototype.hasOwnProperty.call(
          props.extensions.optionSources,
          name,
        )
          ? props.extensions.optionSources[name]
          : undefined;
      if (
        !source ||
        typeof source.search !== 'function' ||
        typeof source.resolve !== 'function'
      )
        return { error: '未注册候选数据源' };
    }
    const supported =
      registration.modes.includes(mode) &&
      (!registration.supports ||
        registration.supports(
          copy(properties),
          copy({
            operator: node.operator,
            field,
            fields,
            options: reference.options,
            timeZone: props.timeZone,
          }),
        ));
    if (!supported)
      return { error: `筛选器 ${reference.name} 不支持当前模式或属性` };
    return {
      props: properties,
      reference,
      registration,
      options: reference.options,
    };
  } catch (error) {
    return { error: message(error) };
  }
}
