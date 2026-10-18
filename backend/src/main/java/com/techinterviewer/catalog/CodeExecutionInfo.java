package com.techinterviewer.catalog;

import java.util.List;

/**
 * Which coding languages this server runs tests for, as the setup form shows it. Implemented by the coding module; the
 * catalog depends only on this interface.
 */
public interface CodeExecutionInfo {

    /** Catalog codes of the languages whose solutions are run against tests here; empty when execution is off. */
    List<String> executableLanguages();
}
