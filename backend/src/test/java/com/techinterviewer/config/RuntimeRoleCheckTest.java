package com.techinterviewer.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuntimeRoleCheckTest {

    @Test
    void aSuperuserStopsTheApplicationAndTheMessageSaysWhatToDo() {
        assertThatThrownBy(() -> RuntimeRoleCheck.judge(new RuntimeRoleCheck.Role("postgres", true, true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("superuser 'postgres'")
                .hasMessageContaining("runtime-grants.sql").hasMessageContaining("POSTGRES_APP_USER");
    }

    @Test
    void anOwnerIsOnlyWarnedAboutAndALimitedRoleIsAccepted() {
        assertThatCode(() -> RuntimeRoleCheck.judge(new RuntimeRoleCheck.Role("neondb_owner", false, true))).doesNotThrowAnyException();
        assertThatCode(() -> RuntimeRoleCheck.judge(new RuntimeRoleCheck.Role("interviewer_app", false, false))).doesNotThrowAnyException();
    }
}
