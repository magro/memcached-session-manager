package de.javakaffee.web.msm.serializer.kryo;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import de.javakaffee.web.msm.integration.TestUtils;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collection;

public class SpringSecurityUserSerializerTest {

	@Test
	public void testSpringSecurityUserSerializer() {
		final Kryo kryo = new Kryo();
		kryo.setInstantiatorStrategy(new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
		kryo.setRegistrationRequired(false);
		
		new SpringSecurityUserRegistration().customize(kryo);
		
		final Collection<? extends GrantedAuthority> authorities = Arrays.asList(new SimpleGrantedAuthority("foo"));
		final User user = new User("foo", "bar", authorities);

		Output out = new Output(100, 1024);
		kryo.writeObject(out, user);
		final byte[] data = out.toBytes();
		
		final User user2 = kryo.readObject(new Input(data), User.class);
		TestUtils.assertDeepEquals(user, user2);
	}
}
