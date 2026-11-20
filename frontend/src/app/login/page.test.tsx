import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderWithIntl } from "@test/render-with-intl";

vi.mock("@/shared/lib/theme/actions", () => ({ setTheme: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));

import LoginPage from "./page";

describe("login page", () => {
  it("renders the login screen", () => {
    renderWithIntl(<LoginPage />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Yönetici girişi");
  });
});
