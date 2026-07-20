package com.reagent.core;

/** Outcome of processing one persisted assistant tool-call batch. */
public enum BatchDisposition {
    EXECUTED,
    WAITING_APPROVAL
}
