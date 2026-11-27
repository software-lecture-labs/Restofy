"use client";

import { useRouter } from "next/navigation";
import { useStaffActivity } from "../hooks/use-staff-activity";

const LOGIN_PATH = "/login";

/**
 * Put once on every staff screen (the home page, the kitchen display, ...): it reports activity and sends the person
 * to the login once Auth has ended the session. It renders nothing.
 */
export function StaffActivity() {
  const router = useRouter();
  useStaffActivity(() => router.replace(LOGIN_PATH));
  return null;
}
