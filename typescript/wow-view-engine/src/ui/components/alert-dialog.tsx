import * as React from "react"
import { AlertDialog as AlertDialogPrimitive } from "@base-ui/react/alert-dialog"
import { cn } from "cn"

import { Button } from "@/ui/components/button"

function AlertDialog({ ...props }: AlertDialogPrimitive.Root.Props) {
  return <AlertDialogPrimitive.Root data-slot="alert-dialog" {...props} />
}

function AlertDialogTrigger({ ...props }: AlertDialogPrimitive.Trigger.Props) {
  return (
    <AlertDialogPrimitive.Trigger data-slot="alert-dialog-trigger" {...props} />
  )
}

function AlertDialogPortal({ ...props }: AlertDialogPrimitive.Portal.Props) {
  return (
    <AlertDialogPrimitive.Portal data-slot="alert-dialog-portal" {...props} />
  )
}

function AlertDialogOverlay({
  className,
  ...props
}: AlertDialogPrimitive.Backdrop.Props) {
  return (
    <AlertDialogPrimitive.Backdrop
      data-slot="alert-dialog-overlay"
      className={cn(
        "fve:fixed fve:inset-0 fve:isolate fve:z-50 fve:bg-black/10 fve:duration-100 fve:supports-backdrop-filter:backdrop-blur-xs fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-closed:animate-out fve:data-closed:fade-out-0",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogContent({
  className,
  size = "default",
  ...props
}: AlertDialogPrimitive.Popup.Props & {
  size?: "default" | "sm"
}) {
  return (
    <AlertDialogPortal>
      <AlertDialogOverlay />
      <AlertDialogPrimitive.Popup
        data-slot="alert-dialog-content"
        data-size={size}
        className={cn(
          "fve:group/alert-dialog-content fve:fixed fve:top-1/2 fve:left-1/2 fve:z-50 fve:grid fve:w-full fve:-translate-x-1/2 fve:-translate-y-1/2 fve:gap-4 fve:rounded-xl fve:bg-popover fve:p-4 fve:text-popover-foreground fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:outline-none fve:data-[size=default]:max-w-xs fve:data-[size=sm]:max-w-xs fve:data-[size=default]:sm:max-w-sm fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95",
          className
        )}
        {...props}
      />
    </AlertDialogPortal>
  )
}

function AlertDialogHeader({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-dialog-header"
      className={cn(
        "fve:grid fve:grid-rows-[auto_1fr] fve:place-items-center fve:gap-1.5 fve:text-center fve:has-data-[slot=alert-dialog-media]:grid-rows-[auto_auto_1fr] fve:has-data-[slot=alert-dialog-media]:gap-x-4 fve:sm:group-data-[size=default]/alert-dialog-content:place-items-start fve:sm:group-data-[size=default]/alert-dialog-content:text-left fve:sm:group-data-[size=default]/alert-dialog-content:has-data-[slot=alert-dialog-media]:grid-rows-[auto_1fr]",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogFooter({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-dialog-footer"
      className={cn(
        "fve:-mx-4 fve:-mb-4 fve:flex fve:flex-col-reverse fve:gap-2 fve:rounded-b-xl fve:border-t fve:bg-muted/50 fve:p-4 fve:group-data-[size=sm]/alert-dialog-content:grid fve:group-data-[size=sm]/alert-dialog-content:grid-cols-2 fve:sm:flex-row fve:sm:justify-end",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogMedia({
  className,
  ...props
}: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="alert-dialog-media"
      className={cn(
        "fve:mb-2 fve:inline-flex fve:size-10 fve:items-center fve:justify-center fve:rounded-md fve:bg-muted fve:sm:group-data-[size=default]/alert-dialog-content:row-span-2 fve:*:[svg:not([class*='size-'])]:size-6",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogTitle({
  className,
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Title>) {
  return (
    <AlertDialogPrimitive.Title
      data-slot="alert-dialog-title"
      className={cn(
        "fve:text-base fve:font-medium fve:sm:group-data-[size=default]/alert-dialog-content:group-has-data-[slot=alert-dialog-media]/alert-dialog-content:col-start-2",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof AlertDialogPrimitive.Description>) {
  return (
    <AlertDialogPrimitive.Description
      data-slot="alert-dialog-description"
      className={cn(
        "fve:text-sm fve:text-balance fve:text-muted-foreground fve:md:text-pretty fve:*:[a]:underline fve:*:[a]:underline-offset-3 fve:*:[a]:hover:text-foreground",
        className
      )}
      {...props}
    />
  )
}

function AlertDialogAction({
  className,
  ...props
}: React.ComponentProps<typeof Button>) {
  return (
    <Button
      data-slot="alert-dialog-action"
      className={cn(className)}
      {...props}
    />
  )
}

function AlertDialogCancel({
  className,
  variant = "outline",
  size = "default",
  ...props
}: AlertDialogPrimitive.Close.Props &
  Pick<React.ComponentProps<typeof Button>, "variant" | "size">) {
  return (
    <AlertDialogPrimitive.Close
      data-slot="alert-dialog-cancel"
      className={cn(className)}
      render={<Button variant={variant} size={size} />}
      {...props}
    />
  )
}

export {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogMedia,
  AlertDialogOverlay,
  AlertDialogPortal,
  AlertDialogTitle,
  AlertDialogTrigger,
}
