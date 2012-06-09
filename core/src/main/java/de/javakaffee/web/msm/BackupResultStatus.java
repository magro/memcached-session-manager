package de.javakaffee.web.msm;

/**
 * The enumeration of possible backup results.
 */
public enum BackupResultStatus {
        /**
         * The session was successfully stored in the sessions default memcached node.
         * This status is also used, if a session was relocated to another memcached node.
         */
        SUCCESS,
        /**
         * The session could not be stored in any memcached node.
         */
        FAILURE,
        /**
         * The session was not modified and therefore the backup was skipped.
         */
        SKIPPED
}