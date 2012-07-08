package de.javakaffee.web.msm.storage.memcached;

public class ConnectionType {

	private final boolean membaseBucketConfig;
	private final String username;
	private final String password;

	public ConnectionType(final boolean membaseBucketConfig, final String username, final String password) {
		this.membaseBucketConfig = membaseBucketConfig;
		this.username = username;
		this.password = password;
	}

	public static ConnectionType valueOf(final boolean membaseBucketConfig, final String username, final String password) {
		return new ConnectionType(membaseBucketConfig, username, password);
	}

	boolean isMembaseBucketConfig() {
		return membaseBucketConfig;
	}

	boolean isSASL() {
		return  !isBlank(username) && !isBlank(password);
	}

	boolean isDefault() {
		return !isMembaseBucketConfig() && !isSASL();
	}

	boolean isBlank(final String value) {
		return value == null || value.trim().length() == 0;
	}
}