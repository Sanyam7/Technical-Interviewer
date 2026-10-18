package com.techinterviewer.evaluation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelProvenanceTest {

    @Test
    void validProvenanceIsKept() {
        UUID call = UUID.randomUUID();

        ModelProvenance provenance = new ModelProvenance("p".repeat(60), "v".repeat(20), "m".repeat(80), call);

        assertThat(provenance.promptId()).hasSize(60);
        assertThat(provenance.promptVersion()).hasSize(20);
        assertThat(provenance.model()).hasSize(80);
        assertThat(provenance.llmCallId()).isEqualTo(call);
        assertThat(new ModelProvenance("p", "1", "m", null).llmCallId()).isNull();
    }

    @Test
    void everyTextMustBePresentAndWithinItsColumn() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelProvenance(" ", "1", "m", null)).withMessageContaining("promptId");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelProvenance("p".repeat(61), "1", "m", null)).withMessageContaining("promptId");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelProvenance("p", "", "m", null)).withMessageContaining("promptVersion");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelProvenance("p", "v".repeat(21), "m", null)).withMessageContaining("promptVersion");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelProvenance("p", "1", null, null)).withMessageContaining("model");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelProvenance("p", "1", "m".repeat(81), null)).withMessageContaining("model");
    }
}
