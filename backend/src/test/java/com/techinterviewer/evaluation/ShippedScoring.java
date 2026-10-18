package com.techinterviewer.evaluation;

import com.techinterviewer.evaluation.service.ScoringProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** The scoring policy of the shipped application.yml, bound as Spring binds it, without starting an application. */
public final class ShippedScoring {

    private static final ScoringPolicy POLICY = load();

    private ShippedScoring() {
    }

    public static ScoringPolicy policy() {
        return POLICY;
    }

    private static ScoringPolicy load() {
        try {
            var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources)).bind("app.evaluation.scoring", ScoringProperties.class).get().policy();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
