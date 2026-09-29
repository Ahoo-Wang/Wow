"use client"

import * as React from "react"
import { cn } from "cn"

function Label({ className, ...props }: React.ComponentProps<"label">) {
  return (
    <label
      data-slot="label"
      className={cn(
        "fve:flex fve:items-center fve:gap-2 fve:text-sm fve:leading-none fve:font-medium fve:select-none fve:group-data-[disabled=true]:pointer-events-none fve:group-data-[disabled=true]:opacity-50 fve:peer-disabled:cursor-not-allowed fve:peer-disabled:opacity-50",
        className
      )}
      {...props}
    />
  )
}

export { Label }
