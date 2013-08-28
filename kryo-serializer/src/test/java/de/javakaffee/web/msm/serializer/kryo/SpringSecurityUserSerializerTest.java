package de.javakaffee.web.msm.serializer.kryo;

import java.util.Arrays;
import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.testng.annotations.Test;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ObjectBuffer;

import de.javakaffee.kryoserializers.KryoReflectionFactorySupport;
import de.javakaffee.web.msm.integration.TestUtils;

public class SpringSecurityUserSerializerTest {

	@Test
	public void testSpringSecurityUserSerializer() {
		final Kryo kryo = new KryoReflectionFactorySupport();
		kryo.setRegistrationOptional(true);
		
		new SpringSecurityUserRegistration().customize(kryo);
		
		final Collection<? extends GrantedAuthority> authorities = Arrays.asList(new SimpleGrantedAuthority("foo"));
		final User user = new User("foo", "bar", authorities);
		
		final ObjectBuffer buffer = new ObjectBuffer(kryo, 100, 1024);
		final byte[] data = buffer.writeObject(user);
		
		final User user2 = buffer.readObject(data, User.class);
		TestUtils.assertDeepEquals(user, user2);
	}
}
