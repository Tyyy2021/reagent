package com.reagent.profile;

/** A client selected a profile that is not enabled in trusted configuration. */
public class UnknownProfileException extends IllegalArgumentException {

    public UnknownProfileException(String profileId) {
        super("Unknown agent profile: " + profileId);
    }
}
