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

import type * as React from 'react';
import { createContext, useContext } from 'react';
import { usePortalTheme, type PortalTheme } from '../../lib/usePortalTheme.js';
import { Dialog as DialogPrimitive } from '@base-ui/react/dialog';
import { cn } from '../../lib/utils.js';

import { Button } from './button.js';
import { XIcon } from 'lucide-react';

const DialogTheme = createContext<PortalTheme>({ style: {} });
function Dialog(props: DialogPrimitive.Root.Props) {
  const { scope, theme, captureTheme } = usePortalTheme(
    props.open,
    props.defaultOpen,
  );
  return (
    <span ref={scope} className="fve-root fve:inline-flex">
      <DialogTheme.Provider value={theme}>
        <DialogPrimitive.Root
          {...props}
          onOpenChange={(open, details) => {
            captureTheme(open);
            props.onOpenChange?.(open, details);
          }}
        />
      </DialogTheme.Provider>
    </span>
  );
}

function DialogPortal({ className, ...props }: DialogPrimitive.Portal.Props) {
  const theme = useContext(DialogTheme);
  return (
    <DialogPrimitive.Portal
      data-slot="dialog-portal"
      className={cn('fve-root', className)}
      {...theme}
      {...props}
    />
  );
}

function DialogClose({ ...props }: DialogPrimitive.Close.Props) {
  return <DialogPrimitive.Close data-slot="dialog-close" {...props} />;
}

function DialogOverlay({
  className,
  ...props
}: DialogPrimitive.Backdrop.Props) {
  return (
    <DialogPrimitive.Backdrop
      data-slot="dialog-overlay"
      className={cn(
        'fve:fixed fve:inset-0 fve:isolate fve:z-50 fve:bg-black/10 fve:duration-100 fve:supports-backdrop-filter:backdrop-blur-xs fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-closed:animate-out fve:data-closed:fade-out-0',
        className,
      )}
      {...props}
    />
  );
}

function DialogContent({
  className,
  children,
  showCloseButton = true,
  keepMounted = false,
  side,
  ...props
}: DialogPrimitive.Popup.Props & {
  showCloseButton?: boolean;
  /** Preserve local editor state while the dialog is closed. */
  keepMounted?: boolean;
  /** Right-edge Sheet; shares dialog focus, theme and dismissal behavior. */
  side?: 'right';
}) {
  return (
    <DialogPortal keepMounted={keepMounted}>
      <DialogOverlay />
      <DialogPrimitive.Popup
        data-slot={side === 'right' ? 'sheet-content' : 'dialog-content'}
        className={cn(
          side === 'right'
            ? 'fve-root fve:fixed fve:inset-y-0 fve:right-0 fve:z-50 fve:flex fve:h-dvh fve:w-full fve:flex-col fve:gap-4 fve:bg-popover fve:p-4 fve:text-sm fve:text-popover-foreground fve:shadow-lg fve:outline-none fve:sm:max-w-[38rem] fve:data-closed:hidden'
            : 'fve-root fve:fixed fve:top-1/2 fve:left-1/2 fve:z-50 fve:grid fve:w-full fve:max-w-[calc(100%-2rem)] fve:-translate-x-1/2 fve:-translate-y-1/2 fve:gap-4 fve:rounded-xl fve:bg-popover fve:p-4 fve:text-sm fve:text-popover-foreground fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:outline-none fve:sm:max-w-sm fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95',
          className,
        )}
        {...props}
      >
        {children}
        {showCloseButton && (
          <DialogPrimitive.Close
            data-slot="dialog-close"
            render={
              <Button
                variant="ghost"
                className="fve:absolute fve:top-2 fve:right-2"
                size="icon-sm"
              />
            }
          >
            <XIcon />
            <span className="fve:sr-only">关闭</span>
          </DialogPrimitive.Close>
        )}
      </DialogPrimitive.Popup>
    </DialogPortal>
  );
}

function DialogHeader({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="dialog-header"
      className={cn('fve:flex fve:flex-col fve:gap-2', className)}
      {...props}
    />
  );
}

function DialogFooter({
  className,
  showCloseButton = false,
  children,
  ...props
}: React.ComponentProps<'div'> & {
  showCloseButton?: boolean;
}) {
  return (
    <div
      data-slot="dialog-footer"
      className={cn(
        'fve:-mx-4 fve:-mb-4 fve:flex fve:flex-col-reverse fve:gap-2 fve:rounded-b-xl fve:border-t fve:bg-muted/50 fve:p-4 fve:sm:flex-row fve:sm:justify-end',
        className,
      )}
      {...props}
    >
      {children}
      {showCloseButton && (
        <DialogPrimitive.Close render={<Button variant="outline" />}>
          关闭
        </DialogPrimitive.Close>
      )}
    </div>
  );
}

function DialogTitle({ className, ...props }: DialogPrimitive.Title.Props) {
  return (
    <DialogPrimitive.Title
      data-slot="dialog-title"
      className={cn(
        'fve:text-base fve:leading-none fve:font-medium',
        className,
      )}
      {...props}
    />
  );
}

function DialogDescription({
  className,
  ...props
}: DialogPrimitive.Description.Props) {
  return (
    <DialogPrimitive.Description
      data-slot="dialog-description"
      className={cn(
        'fve:text-sm fve:text-muted-foreground fve:*:[a]:underline fve:*:[a]:underline-offset-3 fve:*:[a]:hover:text-foreground',
        className,
      )}
      {...props}
    />
  );
}

export {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
};
