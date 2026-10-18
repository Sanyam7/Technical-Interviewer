package com.techinterviewer.coding.service;

import com.techinterviewer.catalog.CodeExecutionInfo;
import com.techinterviewer.catalog.InterviewOptions;
import com.techinterviewer.coding.problem.CodeLanguage;
import com.techinterviewer.coding.testing.TestRunner;
import java.util.List;
import org.springframework.stereotype.Component;

/** Tells the catalog which of its coding languages are run against tests here. */
@Component
class CodeExecutionCatalog implements CodeExecutionInfo {

    private final TestRunner runner;

    CodeExecutionCatalog(TestRunner runner) {
        this.runner = runner;
    }

    @Override
    public List<String> executableLanguages() {
        return InterviewOptions.CODING_LANGUAGES.stream()
                .filter(code -> CodeLanguage.fromCode(code).map(runner::canRun).orElse(false))
                .toList();
    }
}
