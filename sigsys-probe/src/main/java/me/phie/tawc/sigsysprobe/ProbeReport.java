package me.phie.tawc.sigsysprobe;

import java.io.IOException;
import java.util.Locale;

/** Only fixed enums and bounded numbers cross the diagnostic UI boundary. */
final class ProbeReport {
    enum Stage { METADATA, POWER, PROVENANCE, RUN_DIRECTORY, FIXTURE, MARKER,
        OUTPUT, START, WAIT, GROUP_CHECK, TRACE_PARSE, VERIFY, STREAMS, FILES_CLEANUP, PRIOR_RUNS }
    enum Phase { NONE, BASELINE, TRACE }
    enum Category {
        NONE(0), IO(1), SECURITY(2), CANCELLED(3), POWER(4), PROVENANCE(5),
        FILES(6), DEADLINE(7), FORMAT(8), RESULT(9), OTHER(10), BUDGET(11);
        final int code;
        Category(int code) { this.code = code; }
    }
    enum Child { NONE, UNKNOWN, RUNNING, EXITED }
    enum Group { NONE, UNVERIFIED, VERIFIED }
    enum FilesState { NONE, RETAINED, REMOVED, UNKNOWN }
    static final class Failure extends IOException {
        final Category category;
        Failure(Category category) { this.category = category; }
    }
    Stage stage = Stage.POWER;
    Phase phase = Phase.NONE;
    Category category = Category.NONE;
    Child child = Child.NONE;
    Group group = Group.NONE;
    FilesState files = FilesState.NONE;
    int errno, exit = -1, cleanupCode;

    static Category classify(Exception error) {
        // Never inspect getMessage(), toString(), stack traces or file paths.
        if (error instanceof Failure) return ((Failure)error).category;
        if (error instanceof InterruptedException) return Category.CANCELLED;
        if (error instanceof SecurityException) return Category.SECURITY;
        if (error instanceof IOException) return Category.IO;
        if (error instanceof IllegalArgumentException) return Category.FORMAT;
        return Category.OTHER;
    }
    void fail(Exception error, int numericErrno) {
        if (category == Category.NONE) {
            category = classify(error);
            errno = numericErrno >= 1 && numericErrno <= 4095 ? numericErrno : 0;
        }
    }
    boolean failed() { return category != Category.NONE || cleanupCode != 0; }
    void beginPhase(boolean trace) {
        if (!canRemoveFiles()) throw new IllegalStateException();
        phase = trace ? Phase.TRACE : Phase.BASELINE;
        child = Child.NONE; group = Group.NONE; exit = -1;
    }
    void beforeStart() { child = Child.UNKNOWN; group = Group.UNVERIFIED; }
    void started() { child = Child.RUNNING; }
    void ended(int status) { child = Child.EXITED; exit = status >= 0 && status <= 255 ? status : -1; }
    void verifiedGroup() {
        if (child != Child.EXITED) throw new IllegalStateException();
        group = Group.VERIFIED;
    }
    boolean canRemoveFiles() {
        return (child == Child.NONE || child == Child.EXITED) && group != Group.UNVERIFIED && cleanupCode == 0;
    }
    void cleanupFailed(Exception error) {
        cleanupCode = classify(error).code;
        if (cleanupCode == 0) cleanupCode = Category.OTHER.code;
        files = FilesState.RETAINED;
        if (category == Category.NONE) category = classify(error);
    }
    private String word(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    String render() {
        return "probe=3 " + (failed() ? "fail" : "status") + " stage=" + word(stage) +
            " phase=" + word(phase) + " cat=" + word(category) + " code=" + category.code +
            " errno=" + errno + " exit=" + (exit < 0 ? "unknown" : exit) +
            " child=" + word(child) + " group=" + word(group) + " files=" + word(files) +
            " cleanup_code=" + cleanupCode;
    }
}
