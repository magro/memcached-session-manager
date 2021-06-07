package de.javakaffee.web.msm;

import de.javakaffee.web.msm.MemcachedSessionService.SessionManager;

public class DefaultObjectIOFactory implements ObjectIOFactory {
	@Override
	public ObjectIOStrategy createObjectIOStrategy(final SessionManager _manager) {
		return new DefaultObjectIOStrategy();
	}
}
