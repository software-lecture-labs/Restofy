import { describe, expect, it } from "vitest";
import { RESET_LIMITS, validateNewPassword, validateResetRequest } from "./reset-schema";

describe("validateResetRequest", () => {
  it("accepts an e-mail and a six-digit code", () => {
    expect(validateResetRequest({ email: "admin@restofy.com", totpCode: "123456" })).toEqual({});
  });

  it("reports what is missing", () => {
    expect(validateResetRequest({ email: "", totpCode: "" })).toEqual({
      email: "emailRequired",
      totpCode: "codeRequired",
    });
  });

  it("rejects a malformed e-mail and a code that is not six digits", () => {
    expect(validateResetRequest({ email: "admin", totpCode: "12345" })).toEqual({
      email: "emailInvalid",
      totpCode: "codeInvalid",
    });
    expect(validateResetRequest({ email: "admin@restofy.com", totpCode: "12345a" }).totpCode).toBe("codeInvalid");
  });

  it("ignores spaces around the code", () => {
    expect(validateResetRequest({ email: "admin@restofy.com", totpCode: " 123456 " })).toEqual({});
  });
});

describe("validateNewPassword", () => {
  it("accepts a long enough password that is repeated exactly", () => {
    expect(validateNewPassword({ password: "a".repeat(12), confirmation: "a".repeat(12) })).toEqual({});
  });

  it("requires a password", () => {
    expect(validateNewPassword({ password: "", confirmation: "" })).toEqual({ password: "passwordRequired" });
  });

  it("rejects a password shorter than the minimum", () => {
    const short = "a".repeat(RESET_LIMITS.minPassword - 1);
    expect(validateNewPassword({ password: short, confirmation: short })).toEqual({ password: "passwordShort" });
  });

  it("counts characters, not UTF-16 units", () => {
    const eleven = "😀".repeat(RESET_LIMITS.minPassword - 1);
    expect(validateNewPassword({ password: eleven, confirmation: eleven }).password).toBe("passwordShort");
    const twelve = "😀".repeat(RESET_LIMITS.minPassword);
    expect(validateNewPassword({ password: twelve, confirmation: twelve })).toEqual({});
  });

  it("asks for the same password twice", () => {
    expect(validateNewPassword({ password: "a".repeat(12), confirmation: "b".repeat(12) })).toEqual({
      confirmation: "passwordMismatch",
    });
  });

  it("does not report a mismatch while the password itself is invalid", () => {
    expect(validateNewPassword({ password: "short", confirmation: "other" })).toEqual({ password: "passwordShort" });
  });
});
