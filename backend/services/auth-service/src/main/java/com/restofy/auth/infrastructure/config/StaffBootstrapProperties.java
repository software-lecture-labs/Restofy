package com.restofy.auth.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth.bootstrap")
@Getter
@Setter
public class StaffBootstrapProperties {
    private String environment = "";
    private DevUsers devUsers = new DevUsers();
    private InitialAdmin initialAdmin = new InitialAdmin();

    // Deliberately no generated toString: these properties contain credentials.
    @Getter
    @Setter
    public static class Account {
        private String email;
        private String password;
    }

    @Getter
    @Setter
    public static class DevUsers {
        private boolean enabled;
        private Account admin = new Account();
        private Account waiter = new Account();
        private Account kitchen = new Account();
    }

    @Getter
    @Setter
    public static class InitialAdmin extends Account {
        private boolean enabled;
    }
}
