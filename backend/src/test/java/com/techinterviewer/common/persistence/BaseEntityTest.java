package com.techinterviewer.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.common.id.UuidV7;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Identity rules of {@link BaseEntity} that need no database (the persistence behaviour is in PersistenceInfrastructureIT). */
class BaseEntityTest {

    static class Alpha extends BaseEntity {
    }

    static class Beta extends BaseEntity {
    }

    private static <T extends BaseEntity> T withId(T entity, UUID id) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
        return entity;
    }

    @Test
    void everyNewEntityGetsItsOwnTimeOrderedId() throws Exception {
        Alpha first = new Alpha();
        Thread.sleep(2);
        Alpha second = new Alpha();

        assertThat(UuidV7.isV7(first.getId())).isTrue();
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getId().toString()).isLessThan(second.getId().toString());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aNewEntityIsNewUntilItIsMarkedPersisted() {
        Alpha entity = new Alpha();
        assertThat(entity.isNew()).isTrue();

        entity.markNotNew();

        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void entitiesOfTheSameClassAreEqualExactlyWhenTheirIdsAre() throws Exception {
        UUID id = UuidV7.generate();
        Alpha one = withId(new Alpha(), id);
        Alpha same = withId(new Alpha(), id);

        assertThat(one).isEqualTo(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(same).isEqualTo(one);
        assertThat(one).isNotEqualTo(withId(new Alpha(), UuidV7.generate()));
        assertThat(new HashSet<>(Set.of(one, new Alpha()))).contains(same);
    }

    @Test
    void entitiesOfDifferentClassesAreNeverEqualEvenWithTheSameId() throws Exception {
        UUID id = UuidV7.generate();

        Alpha alpha = withId(new Alpha(), id);
        Beta beta = withId(new Beta(), id);

        assertThat(alpha).isNotEqualTo(beta);
        assertThat(beta).isNotEqualTo(alpha);
    }

    @Test
    void anEntityIsNotEqualToNullOrToItsIdOrToAnUnrelatedObject() {
        Alpha entity = new Alpha();

        assertThat(entity).isNotEqualTo(null).isNotEqualTo(entity.getId()).isNotEqualTo("x");
    }

    @Test
    void theHashCodeIsStableWhateverHappensToTheEntity() {
        Alpha entity = new Alpha();
        int before = entity.hashCode();

        entity.markNotNew();

        assertThat(entity.hashCode()).isEqualTo(before).isEqualTo(entity.getId().hashCode());
    }

    @Test
    void toStringNamesTheClassAndTheIdButNothingElse() {
        Alpha entity = new Alpha();

        assertThat(entity.toString()).isEqualTo("Alpha[" + entity.getId() + "]");
    }
}
