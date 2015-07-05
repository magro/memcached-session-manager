package de.javakaffee.web.msm.serializer.kryo;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

	static class SpringSecurityUserSerializer extends Serializer<User> {
		
		private final Kryo _kryo;
		
		public SpringSecurityUserSerializer(final Kryo kryo) {
			_kryo = kryo;
		}

		@Override
		public User read(Kryo kryo, Input input, Class<User> type) {
			final String password = input.readString();
			final String username = input.readString();
			
			final int size = input.readInt(true);
			final List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>(size);
			for (int i = 0; i < size; i++) {
				authorities.add((GrantedAuthority)_kryo.readClassAndObject(input));
			}

			final boolean accountNonExpired = input.readBoolean();
			final boolean accountNonLocked = input.readBoolean();
			final boolean credentialsNonExpired = input.readBoolean();
			final boolean enabled = input.readBoolean();
			
			return new User(username, password, enabled, accountNonExpired, credentialsNonExpired, accountNonLocked, authorities);
		}

		@Override
		public void write(Kryo kryo, Output output, User user) {
			output.writeString(user.getPassword());
			output.writeString(user.getUsername());
			
			final Collection<GrantedAuthority> authorities = user.getAuthorities();
			output.writeInt(authorities.size(), true);
			for (final GrantedAuthority item : authorities) {
				_kryo.writeClassAndObject(output, item);
			}

			output.writeBoolean(user.isAccountNonExpired());
			output.writeBoolean(user.isAccountNonLocked());
			output.writeBoolean(user.isCredentialsNonExpired());
			output.writeBoolean(user.isEnabled());
		}

	}
	
}
