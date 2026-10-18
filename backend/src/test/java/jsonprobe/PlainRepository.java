package jsonprobe;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlainRepository extends JpaRepository<PlainEntity, UUID> {
}
