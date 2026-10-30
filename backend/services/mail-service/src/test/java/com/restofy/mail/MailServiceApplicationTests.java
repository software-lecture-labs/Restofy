package com.restofy.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import com.restofy.mail.support.MailTestDatabaseInitializer;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = MailTestDatabaseInitializer.class)
class MailServiceApplicationTests extends com.restofy.mail.support.MailTestSupport {

	@Test
	void contextLoadsThroughMain() {
	}

}
