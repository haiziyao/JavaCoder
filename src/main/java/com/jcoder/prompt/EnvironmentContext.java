package com.jcoder.prompt;

import java.time.LocalDate;

public record EnvironmentContext(
        String workDir,
        String os,
        String arch,
        String shell,
        LocalDate date
) {

    public static EnvironmentContext detect(String workDir) {
        String actualWorkDir = workDir == null || workDir.isBlank()
                ? System.getProperty("user.dir")
                : workDir;

        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = System.getenv("COMSPEC");
        }

        return new EnvironmentContext(
                actualWorkDir,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                shell == null ? "unknown" : shell,
                LocalDate.now()
        );
    }
}
