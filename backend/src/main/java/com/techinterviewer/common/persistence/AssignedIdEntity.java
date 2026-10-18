package com.techinterviewer.common.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * Base class of entities whose primary key is assigned by the application and is not a single generated value:
 * the composite keys of the join-like tables ({@code question_sources}, {@code report_skill_scores}).
 *
 * <p>As in {@link BaseEntity}, implementing {@link Persistable} is what lets {@code save} issue a plain
 * {@code INSERT}: without it Spring Data cannot tell a new entity with a filled key from a detached one and
 * would {@code SELECT} first and then merge. {@link #isNew()} is true until the entity is persisted or loaded.
 * The subclass declares the key ({@code @EmbeddedId}) and implements {@link #getId()}; equality is by key and
 * real class.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AssignedIdEntity<?> that)) {
            return false;
        }
        // Widened to Class<?>: the Eclipse compiler rejects == between the two captured wildcard types that javac accepts.
        Class<?> mine = Hibernate.getClass(this);
        Class<?> theirs = Hibernate.getClass(that);
        return mine == theirs && getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    @Override
    public String toString() {
        return Hibernate.getClass(this).getSimpleName() + "[" + getId() + "]";
    }
}
