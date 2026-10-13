package com.bgsoftware.superiorskyblock.pvc;

import com.bgsoftware.superiorskyblock.core.errors.ManagerLoadException;

/** A rejected preflight leaves the running plugin untouched and never shuts down the server. */
public final class PvcReloadRejectedException extends ManagerLoadException {
    public PvcReloadRejectedException(String message) { super(message, ErrorLevel.CONTINUE); }
}
