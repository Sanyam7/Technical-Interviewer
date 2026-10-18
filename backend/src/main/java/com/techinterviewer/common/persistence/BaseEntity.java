package com.techinterviewer.common.persistence;

import com.techinterviewer.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * Base class of every entity with a UUID primary key.
 *
 * <ul>
 *   <li>The id is a UUIDv7 assigned <b>in the constructor</b>, so an entity has its identity (and a stable
 *       {@code hashCode}) from the moment it exists, and inserts are ordered by creation time.
 *   <li>Because the id is never database generated, Spring Data cannot tell a new entity from a detached one
 *       by looking at it, and {@code save} would issue a {@code SELECT} followed by a merge. Implementing
 *       {@link Persistable} fixes that: {@link #isNew()} is true until the entity is persisted
 *       ({@link PostPersist}) or loaded ({@link PostLoad}), so {@code save} goes straight to {@code persist}.
 *   <li>{@code equals}/{@code hashCode} use the id and the real entity class (Hibernate proxies are unwrapped).
 * </ul>
 *
 * <p>How to use it: extend it, give the subclass a {@code protected} no-argument constructor for JPA (it calls
 * {@code super()}, which merely draws an id that Hibernate then overwrites) and a public constructor or
 * factory that establishes the invariants. Never set the id yourself. Entities that need a database identity
 * (the catalog's {@code bigint} ids) do not extend this class.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    protected BaseEntity() {
        this.id = UuidV7.generate();
    }

    @Override
    public UUID getId() {
        return id;
    }

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
        if (!(other instanceof BaseEntity that)) {
            return false;
        }
        return Hibernate.getClass(this) == Hibernate.getClass(that) && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return Hibernate.getClass(this).getSimpleName() + "[" + id + "]";
    }
}
