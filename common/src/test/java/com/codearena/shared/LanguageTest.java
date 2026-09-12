package com.codearena.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageTest {

    @Test
    void supportsExactlyTheThreeLanguagesTheJudgeCanRun() {
        assertThat(Language.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("CPP", "JAVA", "PYTHON");
    }

    /**
     * The security boundary. A client sends a string; if it does not name a constant the
     * request fails here, long before anything could reach a process argument.
     */
    @Test
    void rejectsAnythingThatIsNotAKnownLanguage() {
        assertThatThrownBy(() -> Language.valueOf("BASH"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Language.valueOf("CPP; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * This enum must stay free of anything executable. If a compiler path or command ever
     * appears on it, client input and command construction have met, and that is precisely
     * the arrangement the design exists to prevent.
     */
    @ParameterizedTest
    @EnumSource(Language.class)
    void carriesNoExecutableConfiguration(Language language) {
        assertThat(language.getClass().getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("command")
                        .doesNotContain("image")
                        .doesNotContain("path"));
        assertThat(language.fileExtension()).matches("[a-z0-9]+");
    }
}
