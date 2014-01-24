/*
 * Copyright 2014 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Martin Grotzke
 */
public class StorageKeyFormat {

    public static final StorageKeyFormat EMPTY = new StorageKeyFormat(null, null);

    public static final String WEBAPP_VERSION = "webappVersion";
    public static final String HOST = "host";
    public static final String HOST_HASH = "host.hash";
    public static final String CONTEXT = "context";
    public static final String CONTEXT_HASH = "context.hash";

	private static final char STORAGE_TOKEN_SEP = ':';
	private static final char STORAGE_KEY_SEP = '_';

	final String prefix;
    private final String config;

	private StorageKeyFormat(final String prefix, final String config) {
	    if("lock:".equals(prefix) || "bak:".equals(prefix) || "validity:".equals(prefix)) {
	        throw new IllegalArgumentException("The storage key prefix contains a reserved word (used for other purposes): " + prefix);
	    }
		this.prefix = prefix;
		this.config = config;
	}

	public static StorageKeyFormat ofHost(final String host) {
	    return of(HOST, host, null, null);
	}

	/**
	 * Creates a new {@link StorageKeyFormat} for the given configuration.
	 * The configuration has the form <code>$token,$token</code>
	 *
	 * Some examples which config would create which output for the key / session id "foo" with context path "ctxt" and host "hst":
	 * <dl>
     * <dt>static:x</dt><dd>x_foo</dd>
     * <dt>host</dt><dd>hst_foo</dd>
     * <dt>host.hash</dt><dd>e93c085e_foo</dd>
	 * <dt>context</dt><dd>ctxt_foo</dd>
     * <dt>context.hash</dt><dd>45e6345f_foo</dd>
     * <dt>host,context</dt><dd>hst:ctxt_foo</dd>
     * <dt>webappVersion</dt><dd>001_foo</dd>
     * <dt>host.hash,context.hash,webappVersion</dt><dd>e93c085e:45e6345f:001_foo</dd>
	 * </dl>
	 */
	public static StorageKeyFormat of(final String config, final String host, final String context, final String webappVersion) {
		if(config == null || config.trim().isEmpty())
			return EMPTY;

		final String[] tokens = config.split(",");

		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.length; i++) {
			final String value = PrefixTokenFactory.parse(tokens[i], host, context, webappVersion);
			if(value != null && !value.trim().isEmpty()) {
	            if(sb.length() > 0)
	                sb.append(STORAGE_TOKEN_SEP);
                sb.append(value);
			}
		}
		final String prefix = sb.length() == 0 ? null : sb.append(STORAGE_KEY_SEP).toString();
        return new StorageKeyFormat(prefix, config);
	}

	/**
	 * Formats the given input according to the configuration of this config.
	 * @param input the input string to format
	 * @return the formatted input
	 */
	public String format(final String input) {
		if(prefix == null) {
			return input;
		}
		return prefix + input;
	}

	static class PrefixTokenFactory {

        static final Pattern STATIC_PATTERN = Pattern.compile("static:([^" + STORAGE_TOKEN_SEP + "]+)");

		static String parse(final String configToken, final String host, final String context, final String webappVersion) {
			final Matcher staticMatcher = STATIC_PATTERN.matcher(configToken);
			if(staticMatcher.matches())
				return staticMatcher.group(1);
			if(HOST.equals(configToken))
				return host;
			if(HOST_HASH.equals(configToken))
				return hashString(host);
			if(CONTEXT.equals(configToken))
				return context;
			if(CONTEXT_HASH.equals(configToken))
				return hashString(context);
			if(WEBAPP_VERSION.equals(configToken))
				return webappVersion;

			throw new IllegalArgumentException("Unsupported config token " + configToken);
		}
	}

	static String hashString(final String s) {
		return hashString(s, 8);
	}

	static String hashString(final String s, final int maxLength) {
		byte[] hash = null;
		try {
			final MessageDigest md = MessageDigest.getInstance("SHA-256");
			hash = md.digest(s.getBytes());
		} catch (final NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hash.length; ++i) {
			final String hex = Integer.toHexString(hash[i]);
			if (hex.length() == 1) {
				sb.append(0);
				sb.append(hex.charAt(hex.length() - 1));
			} else {
				sb.append(hex.substring(hex.length() - 2));
			}
		}
		final String str = sb.toString();
		return str.substring(0, Math.min(maxLength, str.length()));
	}

    @Override
    public String toString() {
        return "StorageKeyFormat [prefix=" + prefix + ", config=" + config + "]";
    }

}
