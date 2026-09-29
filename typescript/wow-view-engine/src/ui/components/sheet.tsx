import * as React from "react"
import { Dialog as SheetPrimitive } from "@base-ui/react/dialog"
import { cn } from "cn"

import { Button } from "@/ui/components/button"
import { XIcon } from "lucide-react"

function Sheet({ ...props }: SheetPrimitive.Root.Props) {
  return <SheetPrimitive.Root data-slot="sheet" {...props} />
}

function SheetTrigger({ ...props }: SheetPrimitive.Trigger.Props) {
  return <SheetPrimitive.Trigger data-slot="sheet-trigger" {...props} />
}

function SheetClose({ ...props }: SheetPrimitive.Close.Props) {
  return <SheetPrimitive.Close data-slot="sheet-close" {...props} />
}

function SheetPortal({ ...props }: SheetPrimitive.Portal.Props) {
  return <SheetPrimitive.Portal data-slot="sheet-portal" {...props} />
}

function SheetOverlay({ className, ...props }: SheetPrimitive.Backdrop.Props) {
  return (
    <SheetPrimitive.Backdrop
      data-slot="sheet-overlay"
      className={cn(
        "fve:fixed fve:inset-0 fve:z-50 fve:bg-black/10 fve:transition-opacity fve:duration-150 fve:data-ending-style:opacity-0 fve:data-starting-style:opacity-0 fve:supports-backdrop-filter:backdrop-blur-xs",
        className
      )}
      {...props}
    />
  )
}

function SheetContent({
  className,
  children,
  side = "right",
  showCloseButton = true,
  ...props
}: SheetPrimitive.Popup.Props & {
  side?: "top" | "right" | "bottom" | "left"
  showCloseButton?: boolean
}) {
  return (
    <SheetPortal>
      <SheetOverlay />
      <SheetPrimitive.Popup
        data-slot="sheet-content"
        data-side={side}
        className={cn(
          "fve:fixed fve:z-50 fve:flex fve:flex-col fve:gap-4 fve:bg-popover fve:bg-clip-padding fve:text-sm fve:text-popover-foreground fve:shadow-lg fve:transition fve:duration-200 fve:ease-in-out fve:data-ending-style:opacity-0 fve:data-starting-style:opacity-0 fve:data-[side=bottom]:inset-x-0 fve:data-[side=bottom]:bottom-0 fve:data-[side=bottom]:h-auto fve:data-[side=bottom]:border-t fve:data-[side=bottom]:data-ending-style:translate-y-[2.5rem] fve:data-[side=bottom]:data-starting-style:translate-y-[2.5rem] fve:data-[side=left]:inset-y-0 fve:data-[side=left]:left-0 fve:data-[side=left]:h-full fve:data-[side=left]:w-3/4 fve:data-[side=left]:border-r fve:data-[side=left]:data-ending-style:translate-x-[-2.5rem] fve:data-[side=left]:data-starting-style:translate-x-[-2.5rem] fve:data-[side=right]:inset-y-0 fve:data-[side=right]:right-0 fve:data-[side=right]:h-full fve:data-[side=right]:w-3/4 fve:data-[side=right]:border-l fve:data-[side=right]:data-ending-style:translate-x-[2.5rem] fve:data-[side=right]:data-starting-style:translate-x-[2.5rem] fve:data-[side=top]:inset-x-0 fve:data-[side=top]:top-0 fve:data-[side=top]:h-auto fve:data-[side=top]:border-b fve:data-[side=top]:data-ending-style:translate-y-[-2.5rem] fve:data-[side=top]:data-starting-style:translate-y-[-2.5rem] fve:data-[side=left]:sm:max-w-sm fve:data-[side=right]:sm:max-w-sm",
          className
        )}
        {...props}
      >
        {children}
        {showCloseButton && (
          <SheetPrimitive.Close
            data-slot="sheet-close"
            render={
              <Button
                variant="ghost"
                className="fve:absolute fve:top-3 fve:right-3"
                size="icon-sm"
              />
            }
          >
            <XIcon
            />
            <span className="fve:sr-only">Close</span>
          </SheetPrimitive.Close>
        )}
      </SheetPrimitive.Popup>
    </SheetPortal>
  )
}

function SheetHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-header"
      className={cn("fve:flex fve:flex-col fve:gap-0.5 fve:p-4", className)}
      {...props}
    />
  )
}

function SheetFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-footer"
      className={cn("fve:mt-auto fve:flex fve:flex-col fve:gap-2 fve:p-4", className)}
      {...props}
    />
  )
}

function SheetTitle({ className, ...props }: SheetPrimitive.Title.Props) {
  return (
    <SheetPrimitive.Title
      data-slot="sheet-title"
      className={cn(
        "fve:text-base fve:font-medium fve:text-foreground",
        className
      )}
      {...props}
    />
  )
}

function SheetDescription({
  className,
  ...props
}: SheetPrimitive.Description.Props) {
  return (
    <SheetPrimitive.Description
      data-slot="sheet-description"
      className={cn("fve:text-sm fve:text-muted-foreground", className)}
      {...props}
    />
  )
}

export {
  Sheet,
  SheetTrigger,
  SheetClose,
  SheetContent,
  SheetHeader,
  SheetFooter,
  SheetTitle,
  SheetDescription,
}
