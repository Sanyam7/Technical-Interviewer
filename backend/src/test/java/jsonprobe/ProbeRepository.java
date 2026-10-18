package jsonprobe;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProbeRepository extends JpaRepository<ProbeEntity, UUID> {
}
