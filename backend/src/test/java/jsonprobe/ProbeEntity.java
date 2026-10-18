package jsonprobe;

import com.techinterviewer.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Probe entity for the persistence infrastructure tests. It lives outside the application's package on purpose:
 * the entity scan of every other test context never sees it. The table is created by Hibernate in that one test.
 */
@Entity
@Table(name = "probe_documents")
public class ProbeEntity extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "doc", columnDefinition = "jsonb", nullable = false)
    private ProbeDocument doc;

    protected ProbeEntity() {
    }

    public ProbeEntity(String name, ProbeDocument doc) {
        this.name = name;
        this.doc = doc;
    }

    public String name() {
        return name;
    }

    public ProbeDocument doc() {
        return doc;
    }
}
