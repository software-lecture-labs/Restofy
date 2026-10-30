package com.restofy.mail;

import com.restofy.mail.support.MailTestDatabaseInitializer;
import com.restofy.mail.support.MailTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = MailTestDatabaseInitializer.class)
class MailServiceApplicationTests extends MailTestSupport {
    @Autowired private ApplicationContext context;

    @Test
    void contextLoadsThroughMain() {
        assertThat(context.getBean(MailServiceApplication.class)).isNotNull();
    }

}
