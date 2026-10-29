package com.restofy.mail;

import com.restofy.mail.support.MailTestDatabaseInitializer;
import com.restofy.mail.support.MailTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = MailTestDatabaseInitializer.class)
class MailServiceApplicationTests extends MailTestSupport {

	@Test
	void contextLoadsThroughMain() {
	}

}
