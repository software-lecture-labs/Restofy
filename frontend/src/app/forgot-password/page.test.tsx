import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";

vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));

import ForgotPasswordPage from "./page";

describe("forgot password page", () => {
  it("renders the password reset screen", () => {
    renderWithIntl(<ForgotPasswordPage />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Parolanı sıfırla");
  });
});
