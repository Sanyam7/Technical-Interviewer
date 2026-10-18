package com.techinterviewer.coding.sandbox;

import com.techinterviewer.common.collect.Immutables;
import com.techinterviewer.common.validation.Require;
import java.util.List;

/**
 * One container run: the image, a shell script run as {@code sh -c script sh nonce}, and the files the script finds in
 * its working directory.
 */
public record SandboxJob(String image, String script, List<SandboxFile> files, String nonce) {

    /** A file handed to the container. */
    public record SandboxFile(String name, byte[] content) {

        public SandboxFile {
            Require.notBlank(name, "name", 100);
            if (name.contains("/") || name.startsWith(".")) {
                throw new IllegalArgumentException("a sandbox file is a plain name, not a path: " + name);
            }
            Require.nonNull(content, "content");
        }
    }

    public SandboxJob {
        Require.notBlank(image, "image", 200);
        Require.notBlank(script, "script", 4000);
        files = Immutables.list(files);
        Require.notBlank(nonce, "nonce", 64);
    }
}
