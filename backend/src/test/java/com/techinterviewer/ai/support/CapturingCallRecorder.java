package com.techinterviewer.ai.support;

import com.techinterviewer.ai.observability.LlmCallRecord;
import com.techinterviewer.ai.observability.LlmCallRecorder;
import com.techinterviewer.ai.observability.LlmCallStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Keeps call records in memory instead of the database, for tests of the AI layer without Spring. */
public final class CapturingCallRecorder extends LlmCallRecorder {

    private final List<LlmCallRecord> records = new ArrayList<>();
    private final List<UUID> ids = new ArrayList<>();

    public CapturingCallRecorder() {
        super(null, null, null);
    }

    @Override
    public synchronized Optional<UUID> record(LlmCallRecord record) {
        records.add(record);
        UUID id = UUID.randomUUID();
        ids.add(id);
        return Optional.of(id);
    }

    public synchronized List<LlmCallRecord> records() {
        return List.copyOf(records);
    }

    public synchronized List<LlmCallStatus> statuses() {
        return records.stream().map(LlmCallRecord::status).toList();
    }

    public synchronized UUID idOf(int index) {
        return ids.get(index);
    }
}
