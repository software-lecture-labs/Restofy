import { useRef, type CSSProperties, type KeyboardEvent } from "react";
import { useTranslations } from "next-intl";
import { staffRoles, type StaffRole } from "../config/staff-roles";
import { RoleIcon } from "./role-icon";

export const ROLE_PANEL_ID = "login-panel";
export const roleTabId = (role: StaffRole) => `login-role-${role}`;

type RoleSwitcherProps = Readonly<{
  value: StaffRole;
  onChange: (role: StaffRole) => void;
}>;

/** Three tabs with a sliding highlight. Arrow keys, Home and End move between them. */
export function RoleSwitcher({ value, onChange }: RoleSwitcherProps) {
  const t = useTranslations("auth.roles");
  const tabs = useRef<Array<HTMLButtonElement | null>>([]);
  const index = staffRoles.indexOf(value);

  function select(nextIndex: number) {
    const next = staffRoles[nextIndex];
    onChange(next);
    tabs.current[nextIndex]?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const last = staffRoles.length - 1;
    const target = {
      ArrowRight: index === last ? 0 : index + 1,
      ArrowLeft: index === 0 ? last : index - 1,
      Home: 0,
      End: last,
    }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    select(target);
  }

  return (
    <div
      role="tablist"
      aria-label={t("label")}
      style={{ "--role-index": index } as CSSProperties}
      className="relative grid grid-cols-3 rounded-full border border-line bg-surface-muted p-1"
    >
      <span
        aria-hidden="true"
        className="absolute inset-y-1 left-1 w-[calc((100%-0.5rem)/3)] translate-x-[calc(var(--role-index)*100%)] rounded-full bg-brand shadow-[0_6px_20px_-6px_rgb(var(--glow)/0.8)] transition-transform duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none"
      />
      {staffRoles.map((role, position) => {
        const selected = role === value;
        return (
          <button
            key={role}
            ref={(element) => {
              tabs.current[position] = element;
            }}
            id={roleTabId(role)}
            type="button"
            role="tab"
            aria-selected={selected}
            aria-controls={ROLE_PANEL_ID}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(role)}
            onKeyDown={onKeyDown}
            className={`relative z-10 flex h-11 items-center justify-center gap-1.5 rounded-full px-2 text-sm font-semibold transition-colors duration-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand ${selected ? "text-brand-fg" : "text-fg-muted hover:text-fg"}`}
          >
            <RoleIcon role={role} className="size-4 shrink-0" />
            <span className="truncate">{t(role)}</span>
          </button>
        );
      })}
    </div>
  );
}
