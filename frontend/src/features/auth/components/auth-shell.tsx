import type { ReactNode } from "react";
import { RestofyLogo } from "@/shared/components/brand/restofy-logo";
import { ThemeToggle } from "@/shared/components/theme/theme-toggle";
import "../styles/login.css";
import { AuthBackground } from "./auth-background";
import { LoginCard } from "./login-card";

/** The page frame shared by every screen before sign-in: background, theme switch, logo and the glowing card. */
export function AuthShell({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <main className="relative grid min-h-dvh place-items-center overflow-hidden px-4 py-10">
      <AuthBackground />
      <div className="absolute right-4 top-4 sm:right-6 sm:top-6">
        <ThemeToggle />
      </div>

      <div className="flex w-full max-w-md flex-col items-center gap-8">
        <RestofyLogo className="h-auto w-56 sm:w-64" />
        <LoginCard>{children}</LoginCard>
      </div>
    </main>
  );
}
