import { describe, expect, it } from "vitest";
import { defaultStaffRole, isStaffRole, staffRoles } from "./staff-roles";

describe("staff roles", () => {
  it("match the Auth service roles in display order", () => {
    expect(staffRoles).toEqual(["ADMIN", "WAITER", "KITCHEN"]);
  });

  it("start on the admin role", () => {
    expect(defaultStaffRole).toBe("ADMIN");
  });

  it("recognise only known roles", () => {
    expect(isStaffRole("KITCHEN")).toBe(true);
    expect(isStaffRole("admin")).toBe(false);
    expect(isStaffRole(undefined)).toBe(false);
  });
});
