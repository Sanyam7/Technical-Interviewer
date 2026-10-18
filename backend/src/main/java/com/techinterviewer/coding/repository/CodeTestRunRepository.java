package com.techinterviewer.coding.repository;

import com.techinterviewer.coding.domain.CodeTestRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeTestRunRepository extends JpaRepository<CodeTestRun, UUID> {

    Optional<CodeTestRun> findByAnswerId(UUID answerId);

    List<CodeTestRun> findByAnswerIdIn(Collection<UUID> answerIds);

    boolean existsByAnswerId(UUID answerId);
}
