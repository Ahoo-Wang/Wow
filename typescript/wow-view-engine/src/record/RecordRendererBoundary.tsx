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

interface Props {
  children: ReactNode;
  label: string;
  resetKey?: unknown;
}

export class RecordRendererBoundary extends Component<
  Props,
  { failed: boolean; resetKey: unknown }
> {
  state = { failed: false, resetKey: this.props.resetKey };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  static getDerivedStateFromProps(props: Props, state: { resetKey: unknown }) {
    const unchanged =
      Object.is(props.resetKey, state.resetKey) ||
      (Array.isArray(props.resetKey) &&
        Array.isArray(state.resetKey) &&
        props.resetKey.length === state.resetKey.length &&
        props.resetKey.every((key, index) =>
          Object.is(key, (state.resetKey as unknown[])[index]),
        ));
    return !unchanged ? { failed: false, resetKey: props.resetKey } : null;
  }

  render() {
    return this.state.failed ? (
      <span role="alert">{this.props.label}渲染失败</span>
    ) : (
      this.props.children
    );
  }
}
