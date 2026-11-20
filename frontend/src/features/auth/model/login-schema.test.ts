import { describe, expect, it } from "vitest";
import { LOGIN_LIMITS, validateLogin } from "./login-schema";

describe("login validation", () => {
  it("accepts a plausible email and a password", () => {
    expect(validateLogin({ email: "garson@restofy.com", password: "x" })).toEqual({});
  });

  it("ignores surrounding spaces in the email", () => {
    expect(validateLogin({ email: "  garson@restofy.com  ", password: "x" })).toEqual({});
  });

  it("asks for both fields when they are empty", () => {
    expect(validateLogin({ email: "   ", password: "" })).toEqual({
      email: "emailRequired",
      password: "passwordRequired",
    });
  });

  it.each(["garson", "garson@", "@restofy.com", "garson@restofy", "gar son@restofy.com"])(
    "rejects the malformed email %j",
    (email) => {
      expect(validateLogin({ email, password: "x" })).toEqual({ email: "emailInvalid" });
    },
  );

  it("does not trim the password", () => {
    expect(validateLogin({ email: "a@b.co", password: "   " })).toEqual({});
  });

  it("mirrors the Auth service limits", () => {
    expect(LOGIN_LIMITS).toEqual({ email: 300, password: 72 });
  });
});
