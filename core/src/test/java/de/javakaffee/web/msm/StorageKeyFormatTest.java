package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.StorageKeyFormat.hashString;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import org.testng.annotations.Test;

import de.javakaffee.web.msm.StorageKeyFormat.PrefixTokenFactory;

public class StorageKeyFormatTest {

	@Test
	public void testBlankValue() {
		assertEquals(StorageKeyFormat.of(null, null, null, null).format("foo"), "foo");
		assertEquals(StorageKeyFormat.of("", null, null, null).format("foo"), "foo");
	}

	@Test
	public void testPrefixBuilderFactory() {
		final String prefix = PrefixTokenFactory.parse("static:foo", null, null, null);
		assertEquals(prefix, "foo");
	}

	@Test
	public void testStaticValue() {
		final StorageKeyFormat cut = StorageKeyFormat.of("static:x", null, null, null);
		assertNotNull(cut);
		assertEquals(cut.format("foo"), "x_foo");
	}

	@Test
	public void testHost() {
		final StorageKeyFormat cut = StorageKeyFormat.of("host", "hst", null, null);
		assertNotNull(cut);
		assertEquals(cut.format("foo"), "hst_foo");
	}

	@Test
	public void testHostHash() {
		final StorageKeyFormat cut = StorageKeyFormat.of("host.hash", "hst", null, null);
		assertNotNull(cut);
		assertEquals(cut.format("foo"), hashString("hst") + "_foo");
	}

	@Test
	public void testContext() {
		assertEquals(StorageKeyFormat.of("context", null, "ctxt", null).format("foo"), "ctxt_foo");
        assertEquals(StorageKeyFormat.of("context", null, null, null).format("foo"), "foo");
        assertEquals(StorageKeyFormat.of("context", null, "", null).format("foo"), "foo");
	}

	@Test
	public void testContextHash() {
		assertEquals(StorageKeyFormat.of("context.hash", null, "ctxt", null).format("foo"), hashString("ctxt") + "_foo");
	}

	@Test
	public void testWebappVersion() {
		assertEquals(StorageKeyFormat.of("webappVersion", null, null, "001").format("foo"), "001_foo");
        assertEquals(StorageKeyFormat.of("webappVersion", null, null, null).format("foo"), "foo");
        assertEquals(StorageKeyFormat.of("webappVersion", null, null, "").format("foo"), "foo");
    }

	@Test
	public void testHostAndContext() {
		assertEquals(StorageKeyFormat.of("host,context", "hst", "ctxt", null).format("foo"), "hst:ctxt_foo");
        assertEquals(StorageKeyFormat.of("host,context", "hst", "", null).format("foo"), "hst_foo");
	}

	@Test
	public void testHostAndContextHashesWithWebappVersion() {
		final StorageKeyFormat cut = StorageKeyFormat.of("host.hash,context.hash,webappVersion", "hst", "ctxt", "001");
		assertNotNull(cut);
		assertEquals(cut.format("foo"), hashString("hst") + ":" + hashString("ctxt") + ":001_foo");
	}

}
