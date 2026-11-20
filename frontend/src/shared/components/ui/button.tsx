import type { ComponentProps } from "react";

type ButtonProps = ComponentProps<"button">;

export function Button({ className = "", type = "button", ...props }: ButtonProps) {
  return (
    <button
      type={type}
      className={`inline-flex h-12 w-full items-center justify-center rounded-2xl bg-brand px-6 text-base font-semibold text-brand-fg shadow-[0_8px_24px_-8px_rgb(var(--glow)/0.7)] transition-[transform,opacity] duration-200 hover:bg-brand-hover focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-brand active:scale-[0.99] disabled:cursor-not-allowed disabled:opacity-60 ${className}`}
      {...props}
    />
  );
}
