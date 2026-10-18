package com.techinterviewer.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface TopicRepository extends JpaRepository<Topic, Long> {

    List<Topic> findBySkillIdOrderByMinDifficultyAscNameAscIdAsc(Long skillId);

    List<Topic> findBySkillIdInOrderByMinDifficultyAscNameAscIdAsc(Collection<Long> skillIds);

    Optional<Topic> findBySkillIdAndSlug(Long skillId, String slug);
}
