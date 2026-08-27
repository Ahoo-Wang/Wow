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

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { copyTextToClipboard } from "./clipboard.ts";

const originalClipboard = Object.getOwnPropertyDescriptor(
  navigator,
  "clipboard",
);
const originalExecCommand = Object.getOwnPropertyDescriptor(
  document,
  "execCommand",
);

function defineClipboard(value: Clipboard | undefined) {
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value,
  });
}

function defineExecCommand(value: (command: string) => boolean) {
  Object.defineProperty(document, "execCommand", {
    configurable: true,
    value,
  });
}

describe("copyTextToClipboard", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
  });

  afterEach(() => {
    vi.restoreAllMocks();
    if (originalClipboard) {
      Object.defineProperty(navigator, "clipboard", originalClipboard);
    } else {
      Reflect.deleteProperty(navigator, "clipboard");
    }
    if (originalExecCommand) {
      Object.defineProperty(document, "execCommand", originalExecCommand);
    } else {
      Reflect.deleteProperty(document, "execCommand");
    }
  });

  it("uses the Clipboard API when it is available", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    const execCommand = vi.fn(() => true);
    defineClipboard({ writeText } as unknown as Clipboard);
    defineExecCommand(execCommand);

    await expect(copyTextToClipboard("execution-1")).resolves.toBe(true);

    expect(writeText).toHaveBeenCalledWith("execution-1");
    expect(execCommand).not.toHaveBeenCalled();
  });

  it("falls back when the Clipboard API is unavailable", async () => {
    const execCommand = vi.fn(() => {
      const textarea = document.activeElement as HTMLTextAreaElement;
      expect(textarea).toBeInstanceOf(HTMLTextAreaElement);
      expect(textarea.value).toBe("event-1");
      expect(textarea.selectionStart).toBe(0);
      expect(textarea.selectionEnd).toBe("event-1".length);
      return true;
    });
    defineClipboard(undefined);
    defineExecCommand(execCommand);

    await expect(copyTextToClipboard("event-1")).resolves.toBe(true);

    expect(execCommand).toHaveBeenCalledWith("copy");
    expect(document.querySelector("textarea")).not.toBeInTheDocument();
  });

  it("falls back when the Clipboard API rejects the write", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("permission denied"));
    const execCommand = vi.fn(() => true);
    defineClipboard({ writeText } as unknown as Clipboard);
    defineExecCommand(execCommand);

    await expect(copyTextToClipboard("aggregate-1")).resolves.toBe(true);

    expect(execCommand).toHaveBeenCalledWith("copy");
  });

  it("restores focus and removes the temporary textarea", async () => {
    defineClipboard(undefined);
    defineExecCommand(() => true);
    const button = document.createElement("button");
    document.body.append(button);
    button.focus();

    await copyTextToClipboard("stack trace");

    expect(document.activeElement).toBe(button);
    expect(document.querySelector("textarea")).not.toBeInTheDocument();
  });

  it("returns false when both copy mechanisms fail", async () => {
    const writeText = vi.fn().mockRejectedValue(new Error("permission denied"));
    defineClipboard({ writeText } as unknown as Clipboard);
    defineExecCommand(() => false);

    await expect(copyTextToClipboard("execution-1")).resolves.toBe(false);

    expect(document.querySelector("textarea")).not.toBeInTheDocument();
  });
});
