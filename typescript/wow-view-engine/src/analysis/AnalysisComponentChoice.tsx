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

import { Component, type ReactNode } from 'react';
import { Button } from '../components/ui/button.js';
import { FilterSelect } from '../filter/FilterSelect.js';

export class AnalysisEditorBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <div role="alert">
        编辑器无法显示，请重试或切换类型。
        <Button
          variant="outline"
          onClick={() => this.setState({ failed: false })}
        >
          重试编辑器
        </Button>
      </div>
    ) : (
      this.props.children
    );
  }
}
export function Choice({
  label,
  caption,
  value,
  options,
  onChange,
  disabled,
  invalid,
}: {
  label: string;
  caption?: string;
  value?: string;
  options: { value: string; label: string }[];
  onChange(value: string): void;
  disabled?: boolean;
  invalid?: boolean;
}) {
  return (
    <label className="fve:flex fve:min-w-0 fve:max-w-full fve:flex-col fve:gap-1">
      {caption ?? label.replace(/^(维度|指标|排序) \d+ /, '')}
      <FilterSelect
        label={label}
        value={value}
        options={options}
        disabled={disabled || !options.length}
        invalid={invalid}
        onValueChange={next => {
          if (!disabled) onChange(next);
        }}
      />
    </label>
  );
}
