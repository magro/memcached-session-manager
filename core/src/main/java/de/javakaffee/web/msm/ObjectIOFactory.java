package de.javakaffee.web.msm;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

public interface ObjectIOFactory {
	ObjectIOStrategy createObjectIOStrategy(SessionManager sessionManager);
}
