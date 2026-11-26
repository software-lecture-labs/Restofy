package com.restofy.kitchen;

import com.restofy.kitchen.infrastructure.config.KitchenAuthProperties;
import com.restofy.kitchen.infrastructure.security.StaffSessionCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class KitchenServiceApplicationTests {

	@Autowired
	private KitchenAuthProperties auth;
	@Autowired
	private StaffSessionCheck sessions;

	/** Without Auth's public key the application starts with staff authentication off, and the Auth check fails closed. */
	@Test
	void contextLoadsThroughMainWithStaffAuthenticationOffByDefault() {
		assertFalse(auth.staffAuthenticationEnabled());
		assertThrows(RuntimeException.class, () -> sessions.current("a.b.c"));
	}

}
