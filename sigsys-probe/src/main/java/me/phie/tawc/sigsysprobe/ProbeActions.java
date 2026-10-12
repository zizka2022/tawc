package me.phie.tawc.sigsysprobe;

/** Startup/status dispatch cannot invoke the fixture callback. */
final class ProbeActions {
    enum Action { STATUS, METADATA, FIXTURE }
    private final Runnable status, metadata, fixture;
    ProbeActions(Runnable status, Runnable metadata, Runnable fixture) {
        this.status = status; this.metadata = metadata; this.fixture = fixture;
    }
    void startup() { status.run(); }
    void press(Action action) {
        switch (action) {
            case STATUS: status.run(); break;
            case METADATA: metadata.run(); break;
            case FIXTURE: fixture.run(); break;
            default: throw new IllegalArgumentException();
        }
    }
}
