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

import type { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { compileFilterConfiguration, newFilterNode } from './filterCore.js';
import { copy } from '../lib/snapshot.js';
import { createFilterConfiguration } from './filterConfiguration.js';
import type { FilterNodeLocation } from './filterTree.js';
import type { FilterOption } from './filterTypes.js';
import type { FilterPanelState } from './useFilterPanelState.js';
import { FilterValueEditor } from './FilterValueEditor.js';
import { EditorBoundary, EditorSession } from './FilterEditorSession.js';
import { message, ownValue, without } from './filterPanelUtils.js';

export function FilterLeafEditor({
  location,
  operators,
  errors,
  errorId,
  panel,
}: {
  location: FilterNodeLocation;
  operators: readonly FilterOption<FilterOperator>[];
  errors: readonly string[];
  errorId?: string;
  panel: FilterPanelState;
}) {
  const {
    props,
    disabled,
    resolutions,
    epoch,
    editorEpochs,
    panelId,
    mode,
    update,
    setEditorOutputErrors,
    setEditorValidity,
    currentEditorNode,
    changeOperator,
    clearNode,
  } = panel;
  const { node, fields: scopeFields } = location;
  const field = scopeFields.find(field => field.field === node.field);
  const builtin = (
    <FilterValueEditor
      node={node}
      field={field}
      fields={scopeFields}
      timeZone={props.timeZone}
      showTime={node.component.options?.showTime === true}
      errors={errors}
      errorId={errorId}
      disabled={disabled}
      onChange={next => {
        if (currentEditorNode(node))
          update(node.id, { ...next, id: node.id, field: node.field });
      }}
    />
  );
  const resolved = resolutions.get(node.id);
  if (resolved?.error) return null;
  if (!resolved?.registration) return builtin;
  const { options: editorOptions } = resolved;
  const Custom = resolved.registration.component;
  let reportedOperator = node.operator;
  return (
    <EditorBoundary
      session={panel.session}
      key={`${epoch}:${node.id}:${ownValue(editorEpochs, node.id) ?? 0}`}
      editor={Custom}
      operator={node.operator}
      mode={mode}
      disabled={disabled}
      onError={error =>
        setEditorOutputErrors(previous => ({ ...previous, [node.id]: error }))
      }
      onRecover={error =>
        setEditorOutputErrors(previous =>
          ownValue(previous, node.id) === error
            ? without(previous, node.id)
            : previous,
        )
      }
      onFallback={() => {
        if (disabled) return;
        const candidate = { ...node, component: { name: 'builtin' } };
        const valid =
          compileFilterConfiguration(
            { mode, root: candidate },
            scopeFields,
            props.allowedOperators,
            undefined,
            props.timeZone,
          ).errors.length === 0;
        update(
          node.id,
          valid
            ? candidate
            : { ...newFilterNode(node.operator, node.field), id: node.id },
        );
        setEditorValidity(previous => without(previous, node.id));
        setEditorOutputErrors(previous => without(previous, node.id));
      }}
    >
      <EditorSession
        session={panel.session}
        editor={Custom}
        id={`${panelId}-${node.id}`}
        operators={operators}
        errors={errors}
        errorId={errors.length ? errorId : undefined}
        props={copy(resolved.props ?? {})}
        operator={node.operator}
        field={field ? copy(field) : undefined}
        fields={copy(scopeFields)}
        timeZone={props.timeZone}
        mode={mode}
        context={props.context}
        optionSources={props.extensions?.optionSources}
        options={editorOptions ? copy(editorOptions) : undefined}
        disabled={disabled}
        onOperatorChange={op => {
          const current = currentEditorNode({
            ...node,
            operator: reportedOperator,
          });
          if (!current) return;
          if (
            !operators.some(option => option.value === op && !option.disabled)
          ) {
            setEditorOutputErrors(previous => ({
              ...previous,
              [node.id]: '操作不在当前筛选器的可用范围内。',
            }));
            return;
          }
          if (current.operator === op) return;
          changeOperator(current, op);
          reportedOperator = op;
        }}
        onClear={
          resolved.registration.clear
            ? () => {
                const current = currentEditorNode({
                  ...node,
                  operator: reportedOperator,
                });
                if (current) clearNode(current);
              }
            : undefined
        }
        onRemove={() => {
          if (currentEditorNode({ ...node, operator: reportedOperator }))
            update(node.id);
        }}
        onValidityChange={(valid, error) => {
          if (!currentEditorNode({ ...node, operator: reportedOperator }))
            return;
          setEditorValidity(previous =>
            valid
              ? without(previous, node.id)
              : ownValue(previous, node.id) ===
                  (error ?? '输入尚未完成或格式无效。')
                ? previous
                : {
                    ...previous,
                    [node.id]: error ?? '输入尚未完成或格式无效。',
                  },
          );
        }}
        onChange={next => {
          const current = currentEditorNode({
            ...node,
            operator: reportedOperator,
          });
          if (!current) return;
          try {
            const nextConfiguration = createFilterConfiguration(
              { ...current, props: next },
              mode,
            );
            update(node.id, nextConfiguration.root, true);
          } catch (error) {
            setEditorOutputErrors(previous =>
              ownValue(previous, node.id) === message(error)
                ? previous
                : { ...previous, [node.id]: message(error) },
            );
          }
        }}
      />
    </EditorBoundary>
  );
}
