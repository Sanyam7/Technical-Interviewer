package jsonprobe;

import com.techinterviewer.interview.domain.ExpectedKeyPoint;
import com.techinterviewer.interview.domain.InterviewMemory;
import com.techinterviewer.interview.domain.Section;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A record with an Instant, an enum, a map keyed by an enum, a list and a nested document: the awkward cases of a JSON column. */
public record ProbeDocument(
        Instant at,
        Section section,
        Map<Section, Integer> perSection,
        List<ExpectedKeyPoint> keyPoints,
        InterviewMemory memory) {
}
