package de.javakaffee.web.msm.serializer.kryo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.serialize.IntSerializer;
import com.esotericsoftware.kryo.serialize.SimpleSerializer;
import com.esotericsoftware.kryo.serialize.StringSerializer;

/**
 * Provides a custom kryo serializer for the Spring Security User class.
 * <p>
 * This is needed because the User class internally contains a collection
 * of {@link GrantedAuthority}, which is actually a TreeSet with a
 * Comparator. During deserialization kryo creates a TreeSet *without*
 * any comparator and therefore expects that the contained items are
 * Comparable, which is not the case for SimpleGrantedAuthority - ClassCastException.
 * </p>
 * <p>
 * Motivated by <a href="http://code.google.com/p/memcached-session-manager/issues/detail?id=145">
 * issue #145: Deserialization fails on ConcurrentHashMap in Spring User object
 * </a>.
 * </p>
 * @author Martin Grotzke
 */
public class SpringSecurityUserRegistration implements KryoCustomization {

	@Override
	public void customize(final Kryo kryo) {
		kryo.register( User.class, new SpringSecurityUserSerializer( kryo ) );
	}

	static class SpringSecurityUserSerializer extends SimpleSerializer<User> {
		
		private final Kryo _kryo;
		
		public SpringSecurityUserSerializer(final Kryo kryo) {
			_kryo = kryo;
		}

		@Override
		public User read(final ByteBuffer buffer) {
			final String password = StringSerializer.get(buffer);
			final String username = StringSerializer.get(buffer);
			
			final int size = IntSerializer.get(buffer, true);
			final List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>(size);
			for (int i = 0; i < size; i++) {
				authorities.add((GrantedAuthority)_kryo.readClassAndObject(buffer));
			}

			final boolean accountNonExpired = buffer.get() == 1;
			final boolean accountNonLocked = buffer.get() == 1;
			final boolean credentialsNonExpired = buffer.get() == 1;
			final boolean enabled = buffer.get() == 1;
			
			return new User(username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
		}

		@Override
		public void write(final ByteBuffer buffer, final User user) {
			StringSerializer.put(buffer, user.getPassword());
			StringSerializer.put(buffer, user.getUsername());
			
			final Collection<GrantedAuthority> authorities = user.getAuthorities();
			IntSerializer.put(buffer, authorities.size(), true);
			for (final GrantedAuthority item : authorities) {
				_kryo.writeClassAndObject(buffer, item);
			}

			put(buffer, user.isAccountNonExpired());
			put(buffer, user.isAccountNonLocked());
			put(buffer, user.isCredentialsNonExpired());
			put(buffer, user.isEnabled());
		}

		private void put(final ByteBuffer buffer, final boolean value) {
			buffer.put(value ? (byte)1 : (byte)0);
		}
		
	}
	
}
