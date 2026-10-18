package com.techinterviewer.interview.port;

import com.techinterviewer.common.validation.Require;
import org.jspecify.annotations.Nullable;

/** A question already asked in this interview, so the generator avoids repeating it. */
public record AskedQuestion(@Nullable String topicKey, String prompt) {

    public AskedQuestion {
        topicKey = Require.nullOrNotBlank(topicKey, "topicKey", 160);
        Require.notBlank(prompt, "prompt", 4000);
    }
}
