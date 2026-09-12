package com.codearena.shared;

/**
 * A language a submission may be written in.
 *
 * <p>This enum is the entire vocabulary a client may use. It carries a display name and a
 * source file extension and <strong>nothing executable</strong>: no image name, no
 * compiler path, no command line. How a language is actually built and run is a
 * server-side decision that lives in the worker, out of reach of anything a request could
 * influence.
 *
 * <p>That separation is the security boundary. A client sends {@code "CPP"}; if the string
 * does not match a constant here, deserialisation fails and the request is rejected. There
 * is no code path by which a client-supplied value reaches a process argument, because the
 * only thing that crosses the boundary is an enum constant.
 *
 * <p>Adding a language means adding a constant here and a spec in the worker. Nothing in
 * the API, the queue or the database changes.
 */
public enum Language {

    CPP("C++", "cpp"),
    JAVA("Java", "java"),
    PYTHON("Python", "py");

    private final String displayName;
    private final String fileExtension;

    Language(String displayName, String fileExtension) {
        this.displayName = displayName;
        this.fileExtension = fileExtension;
    }

    public String displayName() {
        return displayName;
    }

    /** Without the dot. Used to name the source file inside the sandbox. */
    public String fileExtension() {
        return fileExtension;
    }
}
