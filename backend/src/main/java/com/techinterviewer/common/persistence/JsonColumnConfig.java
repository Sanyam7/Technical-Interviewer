package com.techinterviewer.common.persistence;

import org.hibernate.cfg.MappingSettings;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes Hibernate serialise {@code @JdbcTypeCode(SqlTypes.JSON)} attributes (the {@code jsonb} columns) with the
 * application's own Jackson 3 {@link JsonMapper}. Without it Hibernate picks a mapper itself: a Jackson 2 one
 * when springdoc's transitive Jackson 2 is on the classpath, with different modules and settings.
 *
 * <p>How to declare a JSON column: a record (or a collection or map of records) with
 * {@code @JdbcTypeCode(SqlTypes.JSON)} and {@code @Column(columnDefinition = "jsonb")}. Records need no annotations.
 *
 * <p>Unlike the HTTP mapper, which rejects unknown properties so clients cannot send junk, the mapper used for
 * stored documents <b>ignores unknown properties</b>: a row written by a newer version of the application (a
 * rolling deploy, or a rollback) must still be readable by an older one. This covers unknown <i>properties</i> only: an unknown enum constant (a new
 * section, criterion or flag) still fails to load, so a release that adds a constant must first ship a reader that knows
 * it. Everything else (modules, date format,
 * naming) is inherited from the application mapper, so a document looks the same in the database and on the wire.
 */
@Configuration(proxyBeanMethods = false)
class JsonColumnConfig {

    /** The mapper Hibernate uses for stored documents; a separate bean so tests can inspect it. */
    @Bean
    FormatMapper hibernateJsonFormatMapper(JsonMapper applicationMapper) {
        JsonMapper tolerantReader = applicationMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return new Jackson3JsonFormatMapper(tolerantReader);
    }

    @Bean
    HibernatePropertiesCustomizer jsonColumnsUseTheApplicationMapper(FormatMapper hibernateJsonFormatMapper) {
        return properties -> properties.put(MappingSettings.JSON_FORMAT_MAPPER, hibernateJsonFormatMapper);
    }
}
