package com.techinterviewer.profile;

/** The bounds of a candidate profile. The first three equal the column sizes and checks of the schema. */
public final class ProfileLimits {

    public static final int HEADLINE_MAX = 120;
    public static final int JOB_TITLE_MAX = 60;
    public static final int YEARS_EXPERIENCE_MAX = 60;
    public static final int SKILLS_MAX = 20;
    public static final int RATING_MIN = 1;
    public static final int RATING_MAX = 5;

    private ProfileLimits() {
    }
}
