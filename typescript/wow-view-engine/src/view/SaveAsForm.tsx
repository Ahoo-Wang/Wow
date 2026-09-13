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
import { Button } from '../components/ui/button.js';
import {
  DialogClose,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../components/ui/dialog.js';
import { Input } from '../components/ui/input.js';
import { ViewScopeField } from './ViewScopeField.js';
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
  const [error, setError] = useState<string | null>(null);
  const writing = session.writeStatus !== 'idle';
  const target =
    scope === 'shared' && permissions.saveAsShared
      ? 'shared'
      : permissions.saveAsPersonal
        ? 'personal'
        : permissions.saveAsShared
          ? 'shared'
          : null;
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
      <ViewScopeField
        value={target}
        onValueChange={setScope}
        personal={permissions.saveAsPersonal}
        shared={permissions.saveAsShared}
        disabled={writing}
      />
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
