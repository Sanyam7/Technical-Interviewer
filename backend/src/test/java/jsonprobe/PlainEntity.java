package jsonprobe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** The control of the "save without select" test: an assigned id and no {@code Persistable}. */
@Entity
@Table(name = "probe_plain")
public class PlainEntity {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    private String name = "plain";

    public UUID id() {
        return id;
    }
}
