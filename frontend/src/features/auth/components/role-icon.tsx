import type { StaffRole } from "../config/staff-roles";

const PATHS: Record<StaffRole, string[]> = {
  ADMIN: ["M12 3l7 3v5c0 5-3.5 8.5-7 10-3.5-1.5-7-5-7-10V6z", "M9 12l2 2 4-4"],
  WAITER: ["M3 18h18", "M5 18a7 7 0 0 1 14 0", "M12 7V4", "M10 4h4"],
  KITCHEN: ["M7 13.3A4 4 0 0 1 8 5.5 4.5 4.5 0 0 1 16 5a4 4 0 0 1 1 8.3V19H7z", "M7 16h10"],
};

type RoleIconProps = Readonly<{ role: StaffRole; className?: string }>;

export function RoleIcon({ role, className }: RoleIconProps) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      className={className}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {PATHS[role].map((d) => (
        <path key={d} d={d} />
      ))}
    </svg>
  );
}
