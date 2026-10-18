package com.techinterviewer.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.techinterviewer.TestClock;
import com.techinterviewer.catalog.CatalogService;
import com.techinterviewer.common.error.ApiException;
import com.techinterviewer.common.error.ErrorCode;
import com.techinterviewer.interview.domain.InterviewConfiguration;
import com.techinterviewer.interview.repository.InterviewConfigurationRepository;
import com.techinterviewer.interview.repository.InterviewRepository;
import com.techinterviewer.persistence.Fixtures;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** The deletion rules that only a race can reach: the database refuses what the check did not see coming. */
class InterviewConfigurationServiceTest {

    private static final UUID USER = UUID.fromString("0192f1c2-7a3b-7c11-9d4e-5f6a7b8c9d21");

    private final InterviewConfigurationRepository configurations = mock(InterviewConfigurationRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final InterviewConfigurationService service = new InterviewConfigurationService(
            new ConfigurationValidator(mock(CatalogService.class)),
            configurations,
            interviews,
            mock(CatalogService.class),
            transactions(),
            new TestClock());

    private static InterviewTransactions transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new InterviewTransactions(manager);
    }

    private InterviewConfiguration owned() {
        InterviewConfiguration configuration = Fixtures.configuration(USER, Set.of(1L));
        when(configurations.findByIdAndOwnerUserId(configuration.getId(), USER)).thenReturn(Optional.of(configuration));
        return configuration;
    }

    @Test
    void aConfigurationNoInterviewUsesIsDeleted() {
        InterviewConfiguration configuration = owned();
        when(interviews.existsByConfigurationId(configuration.getId())).thenReturn(false);
        when(configurations.deleteByIdAndOwnerUserId(configuration.getId(), USER)).thenReturn(1L);

        assertThatCode(() -> service.delete(USER, configuration.getId())).doesNotThrowAnyException();
    }

    @Test
    void aConfigurationAnInterviewUsesIsRefusedBeforeAnythingIsDeleted() {
        InterviewConfiguration configuration = owned();
        when(interviews.existsByConfigurationId(configuration.getId())).thenReturn(true);
        when(configurations.deleteByIdAndOwnerUserId(any(), any())).thenThrow(new AssertionError("must not be deleted"));

        assertThatThrownBy(() -> service.delete(USER, configuration.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFIGURATION_IN_USE));
    }

    @Test
    void anInterviewCreatedBetweenTheCheckAndTheDeleteIsRefusedByTheDatabaseAndReportedTheSameWay() {
        InterviewConfiguration configuration = owned();
        when(interviews.existsByConfigurationId(configuration.getId())).thenReturn(false);
        when(configurations.deleteByIdAndOwnerUserId(configuration.getId(), USER))
                .thenThrow(new DataIntegrityViolationException("fk_interviews_configuration"));

        assertThatThrownBy(() -> service.delete(USER, configuration.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFIGURATION_IN_USE);
                    assertThat(ex).hasMessage(InterviewConfigurationService.IN_USE);
                })
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aConfigurationOfSomebodyElseIsNotFoundAndNeverReachesTheDeleteOrTheUsageCheck() {
        UUID foreign = UUID.randomUUID();
        when(configurations.findByIdAndOwnerUserId(foreign, USER)).thenReturn(Optional.empty());
        when(interviews.existsByConfigurationId(any())).thenThrow(new AssertionError("must not be asked"));
        when(configurations.deleteByIdAndOwnerUserId(any(), any())).thenThrow(new AssertionError("must not be deleted"));

        assertThatThrownBy(() -> service.delete(USER, foreign))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex).hasMessage(InterviewLoader.CONFIGURATION_NOT_FOUND);
                });
    }
}
