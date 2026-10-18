package com.techinterviewer.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.TestcontainersConfiguration;
import com.techinterviewer.user.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/** The complete application in the local profile without any JWT_SECRET: it boots, warns, and its tokens work. */
@SpringBootTest(properties = "app.security.jwt.secret=")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class LocalProfileEphemeralSecretIT {

    @Autowired
    JwtKeyProvider keys;

    @Autowired
    JwtService jwtService;

    @Autowired
    JwtDecoder decoder;

    @Test
    void bootsWithAThrowAwayKeyAndWarns(CapturedOutput output) {
        assertThat(keys.isEphemeral()).isTrue();
        assertThat(keys.signingKey().getEncoded()).hasSize(48);
        assertThat(output.getAll()).contains("WARN").contains("JWT_SECRET is not set");
    }

    @Test
    void theThrowAwayKeyIssuesTokensTheApplicationAccepts() {
        UUID id = UUID.randomUUID();

        String token = jwtService.issueAccessToken(new AuthenticatedUser(id, Role.CANDIDATE)).value();

        assertThat(decoder.decode(token).getSubject()).isEqualTo(id.toString());
    }
}
