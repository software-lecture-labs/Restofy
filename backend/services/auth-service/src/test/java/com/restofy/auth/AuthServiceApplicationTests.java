package com.restofy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.restofy.auth.support.AuthTestDatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
class AuthServiceApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoadsThroughMain() {
		assertThat(context.containsBean("authSecurityFilterChain")).isTrue();
	}

}
