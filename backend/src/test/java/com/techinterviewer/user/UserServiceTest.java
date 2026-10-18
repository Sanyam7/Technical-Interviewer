package com.techinterviewer.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestClock;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.user.dto.UserResponse;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class UserServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TestClock clock = new TestClock();
    private final UserService service = new UserService(users, clock);

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraint));
    }

    private static ApiException emailTaken(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.EMAIL_TAKEN);
        return exception;
    }

    @Test
    void registeringCreatesAnEnabledCandidateWithTheGivenValuesAtTheClockTime() {
        when(users.existsByEmail("ada@example.com")).thenReturn(false);
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        User created = service.registerCandidate("ada@example.com", "{bcrypt}hash", "Ada");

        assertThat(created.getEmail()).isEqualTo("ada@example.com");
        assertThat(created.getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(created.getDisplayName()).isEqualTo("Ada");
        assertThat(created.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getCreatedAt()).isEqualTo(TestClock.START);
    }

    @Test
    void anAddressThatIsAlreadyRegisteredIsRefusedWithoutInserting() {
        when(users.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.registerCandidate("ada@example.com", "{bcrypt}hash", "Ada"))
                .satisfies(UserServiceTest::emailTaken);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void losingTheRaceForTheUniqueEmailConstraintIsAlsoEmailTaken() {
        when(users.existsByEmail("ada@example.com")).thenReturn(false);
        DataIntegrityViolationException race = violationOf(UserService.EMAIL_CONSTRAINT);
        when(users.saveAndFlush(any(User.class))).thenThrow(race);

        assertThatThrownBy(() -> service.registerCandidate("ada@example.com", "{bcrypt}hash", "Ada"))
                .satisfies(thrown -> assertThat(emailTaken(thrown).getCause()).isSameAs(race));
    }

    @Test
    void anotherConstraintViolationIsNotMistakenForADuplicateEmail() {
        when(users.existsByEmail("ada@example.com")).thenReturn(false);
        DataIntegrityViolationException other = violationOf("ck_users_role");
        when(users.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> service.registerCandidate("ada@example.com", "{bcrypt}hash", "Ada")).isSameAs(other);
    }

    @Test
    void anIntegrityViolationWithoutAConstraintNameIsNotMistakenForADuplicateEmail() {
        when(users.existsByEmail("ada@example.com")).thenReturn(false);
        DataIntegrityViolationException other = new DataIntegrityViolationException("value too long");
        when(users.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> service.registerCandidate("ada@example.com", "{bcrypt}hash", "Ada")).isSameAs(other);
    }

    @Test
    void lookupsDelegateToTheRepository() {
        User user = User.candidate("ada@example.com", "h", "Ada", TestClock.START);
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertThat(service.findByEmail("ada@example.com")).containsSame(user);
        assertThat(service.findById(user.getId())).containsSame(user);
        assertThat(service.findByEmail("nobody@example.com")).isEmpty();
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theCurrentUserIsTheStoredAccountWithoutItsHash() {
        User user = User.candidate("ada@example.com", "{bcrypt}secret", "Ada", TestClock.START);
        ReflectionTestUtils.setField(user, "role", Role.ADMIN);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        UserResponse response = service.currentUser(user.getId());

        assertThat(response).isEqualTo(new UserResponse(user.getId(), "ada@example.com", "Ada", Role.ADMIN));
    }

    @Test
    void aMissingOrDisabledAccountIsNotAuthenticated() {
        User disabled = User.candidate("ada@example.com", "h", "Ada", TestClock.START);
        ReflectionTestUtils.setField(disabled, "enabled", false);
        when(users.findById(disabled.getId())).thenReturn(Optional.of(disabled));
        UUID missing = UUID.randomUUID();
        when(users.findById(missing)).thenReturn(Optional.empty());

        for (UUID id : new UUID[] {disabled.getId(), missing}) {
            assertThatThrownBy(() -> service.currentUser(id))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        }
    }
}
