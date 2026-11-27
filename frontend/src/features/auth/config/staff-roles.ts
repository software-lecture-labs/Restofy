/** The role names are the Auth service's StaffRole values. */
export const staffRoles = ["ADMIN", "WAITER", "KITCHEN"] as const;
export type StaffRole = (typeof staffRoles)[number];

export const defaultStaffRole: StaffRole = "ADMIN";

export function isStaffRole(value: unknown): value is StaffRole {
  return (staffRoles as readonly unknown[]).includes(value);
}
