package de.javakaffee.web.msm;

public class DefaultObjectIOFactory implements ObjectIOFactory {
	@Override
	public ObjectIOStrategy createObjectIOStrategy() {
		return new DefaultObjectIOStrategy();
	}
}
