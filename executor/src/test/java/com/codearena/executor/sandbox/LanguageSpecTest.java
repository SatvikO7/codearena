package com.codearena.executor.sandbox;

import com.codearena.shared.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the execution layer's security boundary.
 *
 * <p>These assertions look pedantic and are not. Every one of them fails the day somebody
 * makes a command line configurable, interpolates a filename, or reaches for a shell to
 * make a pipeline work — which is exactly how sandboxes acquire command injection.
 */
class LanguageSpecTest {

    @ParameterizedTest
    @EnumSource(Language.class)
    void everyLanguageHasASpec(Language language) {
        LanguageSpec spec = LanguageSpec.forLanguage(language);

        assertThat(spec.language()).isEqualTo(language);
        assertThat(spec.image()).isNotBlank();
        assertThat(spec.runCommand()).isNotEmpty();
    }

    /**
     * No shell, anywhere. Commands are argv arrays handed to a process builder, so a
     * metacharacter is a literal character rather than syntax.
     */
    @ParameterizedTest
    @EnumSource(Language.class)
    void noCommandInvokesAShell(Language language) {
        LanguageSpec spec = LanguageSpec.forLanguage(language);

        assertThat(spec.compileCommand()).noneMatch(LanguageSpecTest::looksLikeAShell);
        assertThat(spec.runCommand()).noneMatch(LanguageSpecTest::looksLikeAShell);
    }

    /**
     * No argument may carry shell syntax. Since there is no shell this would be inert
     * today, but an argument containing a pipe or a semicolon is a sign that somebody is
     * building a command line as a string, which is the step before adding a shell to run it.
     */
    @ParameterizedTest
    @EnumSource(Language.class)
    void noArgumentContainsShellMetacharacters(Language language) {
        LanguageSpec spec = LanguageSpec.forLanguage(language);

        assertThat(spec.compileCommand()).allSatisfy(LanguageSpecTest::assertNoMetacharacters);
        assertThat(spec.runCommand()).allSatisfy(LanguageSpecTest::assertNoMetacharacters);
    }

    /**
     * The source filename is fixed per language and never derived from user input, which
     * is what removes path traversal and malicious-filename attacks by construction.
     */
    @ParameterizedTest
    @EnumSource(Language.class)
    void theSourceFileNameIsAPlainFileName(Language language) {
        LanguageSpec spec = LanguageSpec.forLanguage(language);

        assertThat(spec.sourceFileName())
                .doesNotContain("/")
                .doesNotContain("\\")
                .doesNotContain("..")
                .matches("[A-Za-z0-9_.]+")
                .endsWith("." + language.fileExtension());
    }

    /** Pinned so an image tag cannot silently drift to `latest` or to an unpinned base. */
    @ParameterizedTest
    @EnumSource(Language.class)
    void imagesArePinnedToAnExplicitTag(Language language) {
        LanguageSpec spec = LanguageSpec.forLanguage(language);

        assertThat(spec.image()).contains(":");
        assertThat(spec.image()).doesNotEndWith(":latest");
    }

    @Test
    void onlyCompiledLanguagesRequireCompilation() {
        assertThat(LanguageSpec.forLanguage(Language.CPP).requiresCompilation()).isTrue();
        assertThat(LanguageSpec.forLanguage(Language.JAVA).requiresCompilation()).isTrue();
        assertThat(LanguageSpec.forLanguage(Language.PYTHON).requiresCompilation()).isFalse();
    }

    @Test
    void listsEveryImageTheWorkerNeeds() {
        assertThat(LanguageSpec.allImages()).hasSize(3).doesNotHaveDuplicates();
    }

    private static boolean looksLikeAShell(String argument) {
        return argument.equals("sh") || argument.equals("bash") || argument.equals("/bin/sh")
                || argument.equals("/bin/bash") || argument.equals("-c");
    }

    private static void assertNoMetacharacters(String argument) {
        assertThat(argument).doesNotContain(";").doesNotContain("|").doesNotContain("&")
                .doesNotContain("$(").doesNotContain("`").doesNotContain(">").doesNotContain("<");
    }
}
