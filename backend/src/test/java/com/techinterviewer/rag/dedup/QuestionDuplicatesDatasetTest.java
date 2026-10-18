package com.techinterviewer.rag.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.techinterviewer.rag.dedup.QuestionDuplicatesLiveTest.Pair;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The calibration set of semantic de-duplication stays well formed (the measurement itself is QuestionDuplicatesLiveTest). */
class QuestionDuplicatesDatasetTest {

    @Test
    void everyPairHasAUniqueIdAKnownKindALabelThatFitsItAndTwoDifferentTexts() throws Exception {
        List<Pair> pairs = QuestionDuplicatesLiveTest.dataset();

        assertThat(pairs).extracting(Pair::id).doesNotHaveDuplicates();
        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.kind()).isIn("paraphrase", "same-topic", "other-topic");
            assertThat(pair.duplicate()).as(pair.id()).isEqualTo(pair.kind().equals("paraphrase"));
            assertThat(pair.a()).as(pair.id()).isNotBlank().isNotEqualToIgnoringCase(pair.b());
            assertThat(pair.b()).as(pair.id()).isNotBlank();
        });
        Map<String, Long> kinds = pairs.stream().collect(Collectors.groupingBy(Pair::kind, Collectors.counting()));
        assertThat(kinds).as("enough of each kind to calibrate on")
                .hasEntrySatisfying("paraphrase", count -> assertThat(count).isGreaterThanOrEqualTo(20))
                .hasEntrySatisfying("same-topic", count -> assertThat(count).isGreaterThanOrEqualTo(20))
                .hasEntrySatisfying("other-topic", count -> assertThat(count).isGreaterThanOrEqualTo(5));
    }
}
