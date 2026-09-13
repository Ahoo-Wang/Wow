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

import { Component, useRef, type ReactNode } from 'react';
import type {
  FilterComponentProps,
  FilterRegistration,
} from './filterReactTypes.js';
import { ErrorBoundary } from 'react-error-boundary';
import { RenderCommit } from '../lib/RenderCommit.js';
import { Button } from '../components/ui/button.js';
import { message } from './filterPanelUtils.js';

export class EditorSession extends Component<
  FilterComponentProps & {
    editor: FilterRegistration['component'];
    session: object;
  }
> {
  private active = true;
  state = {
    editor: this.props.editor,
    session: this.props.session,
    operator: this.props.operator,
    mode: this.props.mode,
    field: this.props.field?.field,
    generation: {},
  };
  // React may render replacement props without committing them. Retained callbacks
  // must read the committed owner, never the class instance's work-in-progress props.
  private committed = { props: this.props, generation: this.state.generation };
  getSnapshotBeforeUpdate() {
    this.committed = { props: this.props, generation: this.state.generation };
    return null;
  }
  componentDidUpdate() {}
  static getDerivedStateFromProps(
    props: EditorSession['props'],
    state: EditorSession['state'],
  ) {
    if (
      props.session === state.session &&
      props.editor === state.editor &&
      props.operator === state.operator &&
      props.mode === state.mode &&
      props.field?.field === state.field
    )
      return null;
    return {
      editor: props.editor,
      session: props.session,
      operator: props.operator,
      mode: props.mode,
      field: props.field?.field,
      generation: {},
    };
  }
  componentDidMount() {
    this.active = true;
    this.committed = { props: this.props, generation: this.state.generation };
  }
  componentWillUnmount() {
    this.active = false;
  }
  render() {
    const { editor: Editor, session, ...props } = this.props;
    const generation = this.state.generation;
    const isActive = () =>
      this.active &&
      this.committed.props.session === session &&
      this.committed.generation === generation;
    return (
      <Editor
        {...props}
        onChange={node => {
          if (isActive() && !this.committed.props.disabled)
            this.committed.props.onChange(node);
        }}
        onOperatorChange={operator => {
          if (isActive() && !this.committed.props.disabled)
            this.committed.props.onOperatorChange(operator);
        }}
        onClear={
          props.onClear
            ? () => {
                if (isActive() && !this.committed.props.disabled)
                  this.committed.props.onClear?.();
              }
            : undefined
        }
        onRemove={() => {
          if (isActive() && !this.committed.props.disabled)
            this.committed.props.onRemove();
        }}
        onValidityChange={(valid, message) => {
          if (isActive()) this.committed.props.onValidityChange(valid, message);
        }}
      />
    );
  }
}
export function EditorBoundary({
  children,
  editor,
  session,
  operator,
  mode,
  disabled,
  onError,
  onRecover,
  onFallback,
}: Pick<FilterComponentProps, 'operator' | 'mode'> & {
  disabled?: boolean;
  session: object;
  children: ReactNode;
  editor: FilterRegistration['component'];
  onError(message: string): void;
  onRecover(message: string): void;
  onFallback(): void;
}) {
  const failure = useRef<{ message: string } | null>(null);
  return (
    <ErrorBoundary
      resetKeys={[editor, session, operator, mode]}
      onError={error => {
        failure.current = { message: message(error) };
        onError(failure.current.message);
      }}
      fallback={
        <Button
          variant="outline"
          disabled={disabled}
          onClick={() => {
            if (!disabled) onFallback();
          }}
        >
          使用内置编辑器
        </Button>
      }
    >
      <RenderCommit
        onCommit={() => {
          const previous = failure.current;
          if (previous) {
            failure.current = null;
            onRecover(previous.message);
          }
        }}
      >
        {children}
      </RenderCommit>
    </ErrorBoundary>
  );
}
