package com.restofy.kitchen.infrastructure.security;

/**
 * The staff roles Auth issues. They are not hierarchical; what each may do on kitchen endpoints is decided by
 * {@link SecurityConfiguration}. WAITER is a known identity with no kitchen permission (403, not 401).
 */
public enum StaffRole {
	KITCHEN,
	ADMIN,
	WAITER
}
