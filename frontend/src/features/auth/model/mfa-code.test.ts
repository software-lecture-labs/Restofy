import { describe, expect, it } from "vitest";
import { validateMfaCode } from "./mfa-code";

describe("validateMfaCode", () => {
  it("accepts exactly six digits for an authenticator code", () => {
    expect(validateMfaCode("totp", "123456")).toBeUndefined();
    expect(validateMfaCode("totp", " 123456 ")).toBeUndefined();
  });

  it.each(["12345", "1234567", "12345a", "abcdef", "12 456"])("rejects the authenticator code %j", (code) => {
    expect(validateMfaCode("totp", code)).toBe("codeInvalid");
  });

  it("asks for a code when it is empty or blank", () => {
    expect(validateMfaCode("totp", "")).toBe("codeRequired");
    expect(validateMfaCode("recovery", "   ")).toBe("codeRequired");
  });

  it("accepts a recovery code with or without dashes, in any letter case", () => {
    expect(validateMfaCode("recovery", "abcd-efgh-ijkl-mnop-qrst")).toBeUndefined();
    expect(validateMfaCode("recovery", "ABCDEFGHIJKLMNOPQRST")).toBeUndefined();
    expect(validateMfaCode("recovery", "PKF4-EDHF-HORH-DUUT-KMD7")).toBeUndefined();
  });

  it.each(["abcd-efgh-ijkl-mnop", "abcd-efgh-ijkl-mnop-qrs", "abcdefghijklmnopqrs", "abcd-efgh-ijkl-mnop-qrs1", "abcde-fgh-ijkl-mnop-qrst"])(
    "rejects the recovery code %j",
    (code) => {
      expect(validateMfaCode("recovery", code)).toBe("codeInvalid");
    },
  );

  it("does not accept a six-digit code as a recovery code, nor the other way round", () => {
    expect(validateMfaCode("recovery", "123456")).toBe("codeInvalid");
    expect(validateMfaCode("totp", "abcd-efgh-ijkl-mnop-qrst")).toBe("codeInvalid");
  });
});
