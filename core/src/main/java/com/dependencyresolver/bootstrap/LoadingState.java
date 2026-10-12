package com.dependencyresolver.bootstrap;

public enum LoadingState {
    SCANNING,
    AWAITING_USER,
    DOWNLOADING,
    INSTALLING,
    READY_TO_LAUNCH,
    HANDED_OFF,
    ABORTED
}
