package com.techinterviewer.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SkillRepository extends JpaRepository<Skill, Long> {

    List<Skill> findByActiveTrueOrderByNameAscIdAsc();

    Optional<Skill> findBySlug(String slug);

    Optional<Skill> findBySlugAndActiveTrue(String slug);

    List<Skill> findBySlugInAndActiveTrue(Collection<String> slugs);

    /** Active or retired: the skills with these slugs. */
    List<Skill> findBySlugIn(Collection<String> slugs);
}
