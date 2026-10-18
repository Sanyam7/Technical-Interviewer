package com.techinterviewer.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * The explicit {@code citext} cast keeps the comparison case-insensitive: a plain parameter is sent as
     * {@code varchar}, which would make Postgres compare the column as {@code text}, case-sensitively.
     */
    @Query(value = "select * from users where email = cast(:email as citext)", nativeQuery = true)
    Optional<User> findByEmail(@Param("email") String email);

    @Query(value = "select exists (select 1 from users where email = cast(:email as citext))", nativeQuery = true)
    boolean existsByEmail(@Param("email") String email);
}
