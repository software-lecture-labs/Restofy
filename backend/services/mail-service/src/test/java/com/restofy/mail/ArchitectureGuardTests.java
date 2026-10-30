package com.restofy.mail;

import java.util.List;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureGuardTests {
    @Test
    void rejectsEveryProfileLookupOutsideThePolicy() {
        for (Class<?> fixture : List.of(ActiveProfilesLeak.class, PredicateProfilesLeak.class,
                LegacyProfilesLeak.class, MatchingProfilesLeak.class)) {
            var classes = new ClassFileImporter().importClasses(fixture);
            assertThrows(AssertionError.class,
                    () -> ArchitectureRulesTests.profileChecksLiveOnlyInEnvironmentPolicy.check(classes),
                    fixture.getSimpleName());
        }
    }

    static final class ActiveProfilesLeak {
        public String[] lookup(Environment environment) {
            return environment.getActiveProfiles();
        }
    }

    static final class PredicateProfilesLeak {
        public boolean lookup(Environment environment) {
            return environment.acceptsProfiles(Profiles.of("dev"));
        }
    }

    static final class LegacyProfilesLeak {
        public boolean lookup(Environment environment) {
            return environment.acceptsProfiles("dev");
        }
    }

    static final class MatchingProfilesLeak {
        public boolean lookup(Environment environment) {
            return environment.matchesProfiles("dev");
        }
    }
}
