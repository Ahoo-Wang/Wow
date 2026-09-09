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
import { Popover as PopoverPrimitive } from '@base-ui/react/popover';
import { cn } from '../../lib/utils.js';

const PopoverTheme = createContext<PortalTheme>({ style: {} });

function Popover(props: PopoverPrimitive.Root.Props) {
  const { scope, theme, captureTheme } = usePortalTheme(
    props.open,
    props.defaultOpen,
  );
  return (
    <span ref={scope} className="fve-root fve:inline-flex fve:max-w-full">
      <PopoverTheme.Provider value={theme}>
        <PopoverPrimitive.Root
          {...props}
          onOpenChange={(open, details) => {
            captureTheme(open);
            props.onOpenChange?.(open, details);
          }}
        />
      </PopoverTheme.Provider>
    </span>
  );
}

function PopoverTrigger({ ...props }: PopoverPrimitive.Trigger.Props) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />;
}

function PopoverContent({
  className,
  keepMounted,
  align = 'center',
  alignOffset = 0,
  side = 'bottom',
  sideOffset = 4,
  ...props
}: PopoverPrimitive.Popup.Props &
  Pick<PopoverPrimitive.Portal.Props, 'keepMounted'> &
  Pick<
    PopoverPrimitive.Positioner.Props,
    'align' | 'alignOffset' | 'side' | 'sideOffset'
  >) {
  const theme = useContext(PopoverTheme);
  return (
    <PopoverPrimitive.Portal
      keepMounted={keepMounted}
      className="fve-root"
      {...theme}
    >
      <PopoverPrimitive.Positioner
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
        className="fve-root fve:isolate fve:z-50"
      >
        <PopoverPrimitive.Popup
          data-slot="popover-content"
          className={cn(
            'fve:z-50 fve:flex fve:w-72 fve:origin-(--transform-origin) fve:flex-col fve:gap-2.5 fve:rounded-lg fve:bg-popover fve:p-2.5 fve:text-sm fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:outline-hidden fve:duration-100 fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95',
            className,
          )}
          {...props}
        />
      </PopoverPrimitive.Positioner>
    </PopoverPrimitive.Portal>
  );
}

function PopoverHeader({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="popover-header"
      className={cn('fve:flex fve:flex-col fve:gap-0.5 fve:text-sm', className)}
      {...props}
    />
  );
}

function PopoverTitle({ className, ...props }: PopoverPrimitive.Title.Props) {
  return (
    <PopoverPrimitive.Title
      data-slot="popover-title"
      className={cn('fve:font-medium', className)}
      {...props}
    />
  );
}

function PopoverDescription({
  className,
  ...props
}: PopoverPrimitive.Description.Props) {
  return (
    <PopoverPrimitive.Description
      data-slot="popover-description"
      className={cn('fve:text-muted-foreground', className)}
      {...props}
    />
  );
}

export {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
};
