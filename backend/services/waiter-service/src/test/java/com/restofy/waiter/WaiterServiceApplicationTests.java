package com.restofy.waiter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		properties = "restofy.notification.dispatcher.enabled=false")
class WaiterServiceApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoadsThroughMain() {
		assertTrue(context.containsBean("waiterSecurityFilterChain"), "The deny-by-default security chain is part of the context");
	}

}
