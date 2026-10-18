package com.techinterviewer.resume;

import com.techinterviewer.common.validation.Require;
import com.techinterviewer.resume.domain.ResumeProfile;
import java.util.UUID;

/**
 * A stored résumé as the rest of the application sees it: its id (what an interview links to) and its verified profile.
 */
public record ResumeSnapshot(UUID id, ResumeProfile profile) {

    public ResumeSnapshot {
        Require.nonNull(id, "id");
        Require.nonNull(profile, "profile");
    }
}
