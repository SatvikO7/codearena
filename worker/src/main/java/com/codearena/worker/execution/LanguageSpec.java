package com.codearena.worker.execution;

import com.codearena.shared.Language;

import java.util.List;
import java.util.Map;

/**
 * How each supported language is built and run.
 *
 * <p><strong>This class is the security boundary of the execution layer.</strong> Every
 * command is a fixed {@code List<String>} of argv elements, defined here at compile time.
 * Nothing in a request, a database row or a queue message contributes a single element to
 * any of these lists. They are handed to {@code docker} through a process builder that
 * takes an argument array, so there is no shell to interpret metacharacters and no string
 * concatenation for an injection to ride in on: a source file containing
 * {@code "; rm -rf /"} is a file whose name never appears on a command line, and whose
 * contents are only ever read by a compiler inside a throwaway container.
 *
 * <p>The source file name is also fixed per language, never derived from user input, which
 * removes path traversal and malicious-filename attacks by construction.
 *
 * <p>Java is the awkward one: {@code javac} insists the file name match the public class.
 * The submission is written as {@code Main.java} and the run command names {@code Main},
 * so a program whose public class is called anything else fails to compile — which is a
 * COMPILATION_ERROR with a clear message, not a judge malfunction.
 */
public record LanguageSpec(
        Language language,
        String image,
        String sourceFileName,
        List<String> compileCommand,
        List<String> runCommand) {

    /** The working directory inside every sandbox container. */
    public static final String WORKDIR = "/work";

    private static final Map<Language, LanguageSpec> SPECS = Map.of(
            Language.CPP, new LanguageSpec(
                    Language.CPP,
                    "gcc:13-bookworm",
                    "main.cpp",
                    // -O2 to match what a solver would expect; -static so the produced
                    // binary has no dependency on the image it was compiled in.
                    List.of("g++", "-O2", "-std=c++17", "-static", "-o", "program", "main.cpp"),
                    List.of("./program")),

            Language.JAVA, new LanguageSpec(
                    Language.JAVA,
                    "eclipse-temurin:21-jdk-alpine",
                    "Main.java",
                    List.of("javac", "-encoding", "UTF-8", "Main.java"),
                    // -XX:+UseSerialGC and a small heap keep the JVM's own footprint
                    // predictable, so the container memory limit measures the program
                    // rather than the runtime's appetite for arenas.
                    List.of("java", "-XX:+UseSerialGC", "-Xshare:auto", "Main")),

            Language.PYTHON, new LanguageSpec(
                    Language.PYTHON,
                    "python:3.12-alpine",
                    "main.py",
                    // Nothing to compile. An empty list means "skip the compile step".
                    List.of(),
                    // -I: isolated mode. Ignores PYTHON* environment variables and keeps
                    // the script's directory off sys.path, so the program cannot be
                    // influenced by anything left in the environment.
                    List.of("python3", "-I", "main.py")));

    public static LanguageSpec forLanguage(Language language) {
        LanguageSpec spec = SPECS.get(language);
        if (spec == null) {
            // Unreachable while Language and SPECS agree; a guard so that adding a
            // constant without a spec fails loudly instead of judging nothing.
            throw new IllegalStateException("No execution spec configured for " + language);
        }
        return spec;
    }

    public boolean requiresCompilation() {
        return !compileCommand.isEmpty();
    }

    /** Every image the worker needs before it can judge anything. */
    public static List<String> allImages() {
        return SPECS.values().stream().map(LanguageSpec::image).distinct().sorted().toList();
    }
}
