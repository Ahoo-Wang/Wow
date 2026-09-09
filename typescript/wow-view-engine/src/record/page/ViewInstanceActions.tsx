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

import {
  CheckIcon,
  ChevronDownIcon,
  CopyIcon,
  RotateCcwIcon,
  SaveIcon,
} from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { ButtonGroup } from '../../components/ui/button-group.js';
import { Button } from '../../components/ui/button.js';
import { Dialog, DialogContent } from '../../components/ui/dialog.js';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu.js';
import type { ViewEngine } from '../ViewEngine.js';
import type { RecordSession } from '../recordModel.js';
import { SaveAsForm } from './SaveAsForm.js';

import { useViewPermissions } from './useViewCapabilities.js';

export function ViewInstanceActions({
  engine,
  session,
  run,
}: {
  engine: ViewEngine;
  session: RecordSession;
  run(action: () => void | Promise<void>): void;
}) {
  const [open, setOpen] = useState(false);
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    if (!saved) return;
    const timer = setTimeout(() => setSaved(false), 2500);
    return () => clearTimeout(timer);
  }, [saved]);
  const primaryRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLButtonElement>(null);
  const dialogFocus = useRef<HTMLButtonElement>(null);
  const actionsRef = useRef<HTMLDivElement>(null);
  const restoreFocus = useRef(false);
  const permissions = useViewPermissions(engine, session.instance.id);
  const canSaveAs = permissions.saveAsPersonal || permissions.saveAsShared;
  const canRestore = session.dirty || session.filterPending;
  const writing = session.writeStatus !== 'idle';
  const blocked = writing || session.filterPending || session.requiresReload;
  const showSaved =
    saved && !session.dirty && !session.filterPending && !writing;
  // Move focus after React removes the restore-only menu and disables Save.
  useLayoutEffect(() => {
    if (restoreFocus.current && !canRestore) {
      restoreFocus.current = false;
      actionsRef.current?.focus();
    }
  }, [canRestore]);
  const restore = () => {
    restoreFocus.current = permissions.save && !canSaveAs;
    run(() => engine.restore(session.instance.id));
  };
  if (!permissions.save && !canSaveAs)
    return canRestore ? (
      <Button
        variant="ghost"
        size="sm"
        disabled={writing || session.requiresReload}
        onClick={restore}
      >
        <RotateCcwIcon data-icon="inline-start" aria-hidden="true" />
        还原
      </Button>
    ) : null;
  const hasMenu = (permissions.save && canSaveAs) || canRestore;
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DropdownMenu>
        <ButtonGroup ref={actionsRef} tabIndex={-1} aria-label="视图操作">
          <Button
            ref={primaryRef}
            variant="outline"
            size="sm"
            aria-label={permissions.save ? '保存' : '另存为'}
            disabled={blocked || (permissions.save && !session.dirty)}
            onClick={() => {
              if (permissions.save)
                run(async () => {
                  setSaved(false);
                  await engine.save(session.instance.id);
                  setSaved(true);
                });
              else {
                dialogFocus.current = primaryRef.current;
                setOpen(true);
              }
            }}
          >
            {showSaved ? (
              <CheckIcon data-icon="inline-start" aria-hidden="true" />
            ) : permissions.save ? (
              <SaveIcon data-icon="inline-start" aria-hidden="true" />
            ) : (
              <CopyIcon data-icon="inline-start" aria-hidden="true" />
            )}
            {session.writeStatus === 'deleting'
              ? '删除中…'
              : writing
                ? '保存中…'
                : showSaved
                  ? '已保存'
                  : permissions.save
                    ? '保存'
                    : '另存为'}
          </Button>

          {hasMenu && (
            <DropdownMenuTrigger
              render={
                <Button
                  ref={menuRef}
                  variant="outline"
                  size="icon-sm"
                  aria-label="视图选项"
                  disabled={writing || session.requiresReload}
                />
              }
            >
              <ChevronDownIcon aria-hidden="true" />
            </DropdownMenuTrigger>
          )}
        </ButtonGroup>
        <span role="status" aria-label="保存状态" className="fve:sr-only">
          {showSaved ? '视图已保存' : ''}
        </span>
        {hasMenu && (
          <DropdownMenuContent align="start" finalFocus={!open}>
            <DropdownMenuGroup>
              {permissions.save && canSaveAs && (
                <DropdownMenuItem
                  disabled={blocked}
                  onClick={() => {
                    dialogFocus.current = menuRef.current;
                    setOpen(true);
                  }}
                >
                  <CopyIcon aria-hidden="true" />
                  另存为
                </DropdownMenuItem>
              )}
              {permissions.save && canSaveAs && canRestore && (
                <DropdownMenuSeparator />
              )}
              {canRestore && (
                <DropdownMenuItem
                  disabled={writing || session.requiresReload}
                  onClick={restore}
                >
                  <RotateCcwIcon aria-hidden="true" />
                  还原
                </DropdownMenuItem>
              )}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        )}
      </DropdownMenu>
      <DialogContent finalFocus={dialogFocus}>
        <SaveAsForm
          engine={engine}
          session={session}
          onSaved={() => setOpen(false)}
        />
      </DialogContent>
    </Dialog>
  );
}
