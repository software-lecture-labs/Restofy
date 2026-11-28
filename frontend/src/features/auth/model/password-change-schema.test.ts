import { describe, expect, it } from "vitest";
import { validatePasswordChange } from "./password-change-schema";

const NEW = "Chosen-by-member-0042";

describe("validatePasswordChange", () => {
  it("accepts a long new password that differs from the current one", () => {
    expect(validatePasswordChange({ current: "Temporary-pass-0042", password: NEW, confirmation: NEW })).toEqual({});
  });

  it("asks for the current password and a long enough new one", () => {
    expect(validatePasswordChange({ current: "", password: "short", confirmation: "short" })).toEqual({
      current: "currentRequired",
      password: "passwordShort",
    });
  });

  it("refuses the current password as the new one", () => {
    expect(validatePasswordChange({ current: NEW, password: NEW, confirmation: NEW })).toEqual({
      password: "passwordSame",
    });
  });

  it("wants the confirmation to match", () => {
    expect(validatePasswordChange({ current: "x", password: NEW, confirmation: `${NEW}!` })).toEqual({
      confirmation: "passwordMismatch",
    });
  });
});
