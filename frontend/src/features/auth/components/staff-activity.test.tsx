import { render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const replace = vi.hoisted(() => vi.fn());
const hook = vi.hoisted(() => ({ useStaffActivity: vi.fn() }));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("../hooks/use-staff-activity", () => hook);

import { StaffActivity } from "./staff-activity";

beforeEach(() => {
  vi.clearAllMocks();
});

describe("StaffActivity", () => {
  it("renders nothing and sends the person to the login once the session has ended", () => {
    const { container } = render(<StaffActivity />);

    expect(container).toBeEmptyDOMElement();
    const onSignedOut = hook.useStaffActivity.mock.calls[0][0] as () => void;
    onSignedOut();
    expect(replace).toHaveBeenCalledWith("/login");
  });
});
