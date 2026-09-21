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

import type { ViewConfig } from '../model/index.js';
import type { ConflictChoice } from '../runtime/index.js';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './components/alert-dialog.js';
import { describeConfig } from './describeConfig.js';
import type { MessageKey } from './messages.js';
import { useViewMessages } from './MessagesProvider.js';
import { AlertDialogContent } from './popups.js';

/**
 * The same choice, put once more with both configs on the table.
 *
 * Either answer loses something a user cannot see from the button that
 * offered it, so the two ways of looking are summarised side by side first.
 *
 * An `AlertDialog`, not a `Dialog`: whichever config is kept, the other one
 * goes. Base UI holds it open under an outside click and keeps focus on the
 * two answers, so neither side is discarded by a stray click.
 */
export function ConflictConfirm({
  choice,
  local,
  remote,
  onOpenChange,
  onConfirm,
}: {
  /** The choice awaiting confirmation, or null while none is. */
  choice: ConflictChoice | null;
  local: ViewConfig | null;
  remote: ViewConfig | null;
  onOpenChange(open: boolean): void;
  onConfirm(choice: ConflictChoice): void;
}) {
  const messages = useViewMessages();
  const taking = choice === 'reload';
  return (
    <AlertDialog open={choice !== null} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            {messages.label(
              taking
                ? 'label.conflict.confirm-theirs'
                : 'label.conflict.confirm-mine',
            )}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {messages.label('label.conflict.choice')}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="grid grid-cols-2 gap-3 text-sm">
          <ConfigSide titleKey="label.conflict.local" config={local} />
          <ConfigSide titleKey="label.conflict.remote" config={remote} />
        </div>
        <AlertDialogFooter>
          <AlertDialogCancel>
            {messages.label('label.dialog.cancel')}
          </AlertDialogCancel>
          <AlertDialogAction
            onClick={() => choice !== null && onConfirm(choice)}
            variant={taking ? 'default' : 'destructive'}
          >
            {messages.label(
              taking ? 'label.conflict.theirs' : 'label.conflict.mine',
            )}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function ConfigSide({
  titleKey,
  config,
}: {
  titleKey: MessageKey;
  config: ViewConfig | null;
}) {
  const messages = useViewMessages();
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span className="font-medium">{messages.label(titleKey)}</span>
      {config && (
        <span className="text-muted-foreground">
          {describeConfig(config, messages)}
        </span>
      )}
    </div>
  );
}
