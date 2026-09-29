import * as React from "react"
import { cn } from "cn"

function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "fve:flex fve:field-sizing-content fve:min-h-16 fve:w-full fve:rounded-lg fve:border fve:border-input fve:bg-transparent fve:px-2.5 fve:py-2 fve:text-base fve:transition-colors fve:outline-none fve:placeholder:text-muted-foreground fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring/50 fve:disabled:cursor-not-allowed fve:disabled:bg-input/50 fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:md:text-sm fve:dark:bg-input/30 fve:dark:disabled:bg-input/80 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  )
}

export { Textarea }
