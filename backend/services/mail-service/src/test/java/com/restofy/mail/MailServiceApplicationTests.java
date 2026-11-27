package com.restofy.mail;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import com.restofy.mail.support.MailTestDatabaseInitializer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = MailTestDatabaseInitializer.class)
class MailServiceApplicationTests extends com.restofy.mail.support.MailTestSupport {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoadsThroughMain() {
		assertThat(context).isNotNull();
	}

}
