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

import { useId, useState } from 'react';
import { Button } from '../components/ui/button.js';
import {
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/ui/dialog.js';
import { Input } from '../components/ui/input.js';
import { RadioGroup, RadioGroupItem } from '../components/ui/radio-group.js';
import { cn } from '../lib/utils.js';
import type { ViewEngine } from '../engine/ViewEngine.js';
import type { ViewSession, SaveAsScope } from '../contracts/viewModel.js';

import { useViewPermissions } from './useViewCapabilities.js';

export function SaveAsForm({
  engine,
  session,
  onSaved,
}: {
  engine: ViewEngine;
  session: ViewSession;
  onSaved(): void;
}) {
  const permissions = useViewPermissions(engine, session.instance.id);
  const [title, setTitle] = useState(`${session.instance.title} 副本`);
  const [scope, setScope] = useState('personal');
  const scopeId = useId();
  const [error, setError] = useState<string | null>(null);
  const writing = session.writeStatus !== 'idle';
  const choices = [
    {
      value: 'personal',
      label: '个人视图',
      description: '仅自己可见，适合保存个人常用配置。',
      disabled: !permissions.saveAsPersonal,
    },
    {
      value: 'shared',
      label: '公共视图',
      description: '对有访问权限的用户可见，适合团队共享。',
      disabled: !permissions.saveAsShared,
    },
  ];
  const target =
    (
      choices.find(item => item.value === scope && !item.disabled) ??
      choices.find(item => !item.disabled)
    )?.value ?? null;
  return (
    <form
      className="fve:flex fve:flex-col fve:gap-4"
      onSubmit={event => {
        event.preventDefault();
        if (!target) return;
        setError(null);
        const saveScope: SaveAsScope =
          target === 'personal'
            ? { type: 'personal' }
            : { type: 'public', source: 'shared' };
        void engine
          .saveAs(
            { title: title.trim(), scope: saveScope },
            session.instance.id,
          )
          .then(onSaved)
          .catch(error =>
            setError(error instanceof Error ? error.message : '另存为失败'),
          );
      }}
    >
      <DialogHeader>
        <DialogTitle>另存为视图</DialogTitle>
        <DialogDescription>
          保存当前配置，无需先运行查询。原视图保持原样。
        </DialogDescription>
      </DialogHeader>
      <label className="fve:flex fve:flex-col fve:gap-2 fve:text-sm">
        视图名称
        <Input
          aria-label="视图名称"
          autoFocus
          required
          value={title}
          onChange={event => setTitle(event.target.value)}
          disabled={writing}
        />
      </label>
      <fieldset className="fve:m-0 fve:min-w-0 fve:border-0 fve:p-0 fve:text-sm">
        <legend id={scopeId} className="fve:mb-3 fve:p-0 fve:font-medium">
          可见范围
        </legend>
        <RadioGroup
          aria-labelledby={scopeId}
          value={target}
          onValueChange={value => {
            if (value) setScope(value);
          }}
          disabled={writing}
          className="fve:gap-4"
        >
          {choices.map(choice => (
            <label
              key={choice.value}
              htmlFor={`${scopeId}-${choice.value}`}
              className={cn(
                'fve:flex fve:cursor-pointer fve:items-start fve:gap-3',
                (writing || choice.disabled) &&
                  'fve:cursor-not-allowed fve:opacity-50',
              )}
            >
              <RadioGroupItem
                id={`${scopeId}-${choice.value}`}
                value={choice.value}
                aria-labelledby={`${scopeId}-${choice.value}-label`}
                aria-describedby={`${scopeId}-${choice.value}-description`}
                disabled={choice.disabled}
                className="fve:mt-0.5"
              />
              <span className="fve:grid fve:gap-1">
                <span
                  id={`${scopeId}-${choice.value}-label`}
                  className="fve:font-medium"
                >
                  {choice.label}
                </span>
                <span
                  id={`${scopeId}-${choice.value}-description`}
                  className="fve:text-muted-foreground"
                >
                  {choice.description}
                  {choice.disabled && '（无创建权限）'}
                </span>
              </span>
            </label>
          ))}
        </RadioGroup>
      </fieldset>
      {error && (
        <p role="alert" className="fve:text-sm fve:text-destructive">
          {error}
        </p>
      )}
      <DialogFooter>
        <DialogClose render={<Button variant="outline" disabled={writing} />}>
          取消
        </DialogClose>
        <Button
          type="submit"
          disabled={
            writing ||
            !target ||
            !title.trim() ||
            session.validation.length > 0 ||
            session.requiresReload
          }
        >
          {writing ? '保存中…' : '创建视图'}
        </Button>
      </DialogFooter>
    </form>
  );
}
