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

import { ExchangeError } from "@ahoo-wang/fetcher";
import { useState } from "react";
import { commandErrorMessage } from "@/features/Failed/commandErrors.ts";

export interface CommandForm {
  /** The command is on its way. */
  sending: boolean;
  /** Why the last send failed, in the service's words; `null` otherwise. */
  refused: string | null;
  /** Sends, then `onSent` once the service has taken it. */
  send(command: () => Promise<void>): void;
}

/** The service's own reason: a refused command's result, or the error. */
export async function reasonOf(error: unknown): Promise<string> {
  if (error instanceof ExchangeError) return commandErrorMessage(error);
  return error instanceof Error ? error.message : String(error);
}

/**
 * One form's command in the record detail: one send at a time, and a
 * refusal kept beside the form until the next send, in the service's words.
 */
export function useCommandForm(onSent: () => void): CommandForm {
  const [sending, setSending] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);
  const send = (command: () => Promise<void>) => {
    setSending(true);
    setRefused(null);
    command().then(
      () => {
        setSending(false);
        onSent();
      },
      async (error: unknown) => {
        setRefused(await reasonOf(error));
        setSending(false);
      },
    );
  };
  return { sending, refused, send };
}
