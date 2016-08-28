/*
 * Copyright 2011 Martin Grotzke
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
 */
package de.javakaffee.web.msm;

import static de.javakaffee.web.msm.MemcachedNodesManager.createFor;
import static java.util.Arrays.asList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.spy.memcached.OperationTimeoutException;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.MemcachedNodesManager.StorageClientCallback;

/**
 * Test for {@link MemcachedNodesManager}.
 *
 * @author @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class MemcachedNodesManagerTest {

	private StorageClientCallback _mcc;

	@BeforeMethod
	public void beforeClass() {
		_mcc = mock(StorageClientCallback.class);
	}

	@Test(expectedExceptions = IllegalArgumentException.class)
	public void testParseWithNullShouldThrowException() {
		createFor(null, null, null, _mcc);
	}

	@Test(expectedExceptions = IllegalArgumentException.class)
	public void testParseWithEmptyStringShouldThrowException() {
		createFor("", null, null, _mcc);
	}

	@Test(expectedExceptions = IllegalArgumentException.class)
	public void testSingleSimpleNodeAndFailoverNodeShouldThrowException() {
		createFor("localhost:11211", "n1", null, _mcc);
	}

	@Test(expectedExceptions = IllegalArgumentException.class)
	public void testSingleNodeAndFailoverNodeShouldThrowException() {
		createFor("n1:localhost:11211", "n1", null, _mcc);
	}

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testCouchbaseNodesAndFailoverNodeShouldThrowException() {
        createFor("http://localhost:8091/pools", "n1", null, _mcc);
    }

	@DataProvider
	public static Object[][] nodesAndExpectedCountDataProvider() {
		return new Object[][] {
				{ "localhost:11211", 1 },
                { "http://localhost:8091/pools", 1},
                { "http://10.10.0.1:8091/pools,http://10.10.0.2:8091/pools", 2},
                { "n1:localhost:11211", 1 },
				{ "n1:localhost:11211,n2:localhost:11212", 2 },
				{ "n1:localhost:11211 n2:localhost:11212", 2 }
		};
	}

	@Test( dataProvider = "nodesAndExpectedCountDataProvider" )
	public void testCountNodes( final String memcachedNodes, final int expectedCount ) {
		final MemcachedNodesManager result = createFor( memcachedNodes, null, null, _mcc );
		assertNotNull(result);
		assertEquals(result.getCountNodes(),  expectedCount);
	}

	@DataProvider
	public static Object[][] nodesAndPrimaryNodesDataProvider() {
		return new Object[][] {
				{ "localhost:11211", null, new NodeIdList() },
                { "http://localhost:8091/pools", null, new NodeIdList() },
                { "http://10.10.0.1:8091/pools,http://10.10.0.2:8091/pools", null, new NodeIdList() },
				{ "n1:localhost:11211", null, new NodeIdList("n1") },
				{ "n1:localhost:11211,n2:localhost:11212", "n1", new NodeIdList("n2") },
				{ "n1:localhost:11211,n2:localhost:11212,n3:localhost:11213", "n1", new NodeIdList("n2", "n3") },
				{ "n1:localhost:11211,n2:localhost:11212,n3:localhost:11213", "n1,n2", new NodeIdList("n3") }
		};
	}

	@Test( dataProvider = "nodesAndPrimaryNodesDataProvider" )
	public void testPrimaryNodes(final String memcachedNodes, final String failoverNodes, final NodeIdList expectedPrimaryNodeIds) {
		final MemcachedNodesManager result = createFor( memcachedNodes, failoverNodes, null, _mcc );
		assertNotNull(result);
		assertEquals(result.getPrimaryNodeIds(), expectedPrimaryNodeIds);
	}

	@DataProvider
	public static Object[][] nodesAndFailoverNodesDataProvider() {
		return new Object[][] {
				{ "localhost:11211", null, Collections.emptyList() },
				{ "localhost:11211", "", Collections.emptyList() },
                { "http://localhost:8091/pools", null, Collections.emptyList() },
				{ "n1:localhost:11211", null, Collections.emptyList() },
				{ "n1:localhost:11211,n2:localhost:11212", "n1", Arrays.asList("n1") },
				{ "n1:localhost:11211,n2:localhost:11212,n3:localhost:11213", "n1,n2", Arrays.asList("n1", "n2") },
				{ "n1:localhost:11211,n2:localhost:11212,n3:localhost:11213", "n1 n2", Arrays.asList("n1", "n2") }
		};
	}

	@Test( dataProvider = "nodesAndFailoverNodesDataProvider" )
	public void testFailoverNodes(final String memcachedNodes, final String failoverNodes, final List<String> expectedFailoverNodeIds) {
		final MemcachedNodesManager result = createFor( memcachedNodes, failoverNodes, null, _mcc );
		assertNotNull(result);
		assertEquals(result.getFailoverNodeIds(), expectedFailoverNodeIds);
	}

	@DataProvider
	public static Object[][] nodesAndExpectedEncodedInSessionIdDataProvider() {
		return new Object[][] {
				{ "localhost:11211", null, false },
                { "http://localhost:8091/pools", null, false },
                { "http://10.10.0.1:8091/pools,http://10.10.0.2:8091/pools", null, false },
				{ "n1:localhost:11211", null, true },
				{ "n1:localhost:11211,n2:localhost:11212", "n1", true }
		};
	}

	@Test( dataProvider = "nodesAndExpectedEncodedInSessionIdDataProvider" )
	public void testIsEncodeNodeIdInSessionId( final String memcachedNodes, final String failoverNodes, final boolean expectedIsEncodeNodeIdInSessionId ) {
		final MemcachedNodesManager result = createFor( memcachedNodes, null, null, _mcc );
		assertNotNull(result);
		assertEquals(result.isEncodeNodeIdInSessionId(), expectedIsEncodeNodeIdInSessionId);
	}

	@Test(expectedExceptions = IllegalArgumentException.class)
	public void testGetNodeIdShouldThrowExceptionForNullArgument() {
		final MemcachedNodesManager result = createFor( "n1:localhost:11211", null, null, _mcc );
		result.getNodeId(null);
	}

	@DataProvider
	public static Object[][] testGetNodeIdDataProvider() {
		return new Object[][] {
				{ "n1:localhost:11211", null, new InetSocketAddress("localhost", 11211), "n1" },
				{ "n1:localhost:11211,n2:localhost:11212", null, new InetSocketAddress("localhost", 11212), "n2" },
				{ "n1:localhost:11211,n2:localhost:11212", "n1", new InetSocketAddress("localhost", 11211), "n1" }
		};
	}

	@Test( dataProvider = "testGetNodeIdDataProvider" )
	public void testGetNodeId(final String memcachedNodes, final String failoverNodes, final InetSocketAddress socketAddress, final String expectedNodeId) {
		final MemcachedNodesManager result = createFor( memcachedNodes, failoverNodes, null, _mcc );
		assertEquals(result.getNodeId(socketAddress), expectedNodeId);
	}

	/**
	 * Test for {@link MemcachedNodesManager#getNextPrimaryNodeId(String)}.
	 * @see NodeIdList#getNextNodeId(String)
	 * @see NodeIdListTest#testGetNextNodeId()
	 */
	@Test
	public void testGetNextPrimaryNodeId() {
		assertNull(createFor( "n1:localhost:11211", null, null, _mcc ).getNextPrimaryNodeId("n1"));
		assertEquals(createFor( "n1:localhost:11211,n2:localhost:11212", null, null, _mcc ).getNextPrimaryNodeId("n1"), "n2");
	}

    @Test
    public void testGetNextAvailableNodeId() throws IOException {
        assertNull(createFor( "n1:localhost:11211", null, null, _mcc ).getNextAvailableNodeId("n1"));
        assertEquals(createFor( "n1:localhost:11211,n2:localhost:11212", null, null, _mcc ).getNextAvailableNodeId("n1"), "n2");

        final StorageClientCallback mcc = mock(StorageClientCallback.class);
        when(mcc.get(anyString())).thenReturn(null);
        when(mcc.get(endsWith("n2"))).thenThrow(new OperationTimeoutException("SimulatedException"));
        assertNull(createFor( "n1:localhost:11211,n2:localhost:11212", null, null, mcc).getNextAvailableNodeId("n1"));

        assertEquals(createFor( "n1:localhost:11211,n2:localhost:11212,n3:localhost:11213", null, null, mcc).getNextAvailableNodeId("n1"), "n3");
    }

	@DataProvider
	public static Object[][] testgGetAllMemcachedAddressesDataProvider() {
		return new Object[][] {
				{ "localhost:11211", null, asList(new InetSocketAddress("localhost", 11211)) },
				{ "http://localhost:8091/pools", null, asList(new InetSocketAddress("localhost", 8091)) },
                { "http://10.10.0.1:8091/pools,http://10.10.0.2:8091/pools", null,
				    asList(new InetSocketAddress("10.10.0.1", 8091), new InetSocketAddress("10.10.0.2", 8091)) },
				{ "n1:localhost:11211", null, asList(new InetSocketAddress("localhost", 11211)) },
				{ "n1:localhost:11211,n2:localhost:11212", null, asList(new InetSocketAddress("localhost", 11211), new InetSocketAddress("localhost", 11212)) },
				{ "n1:localhost:11211,n2:localhost:11212", "n1", asList(new InetSocketAddress("localhost", 11211), new InetSocketAddress("localhost", 11212)) }
		};
	}

	@Test( dataProvider = "testgGetAllMemcachedAddressesDataProvider" )
	public void testGetAllMemcachedAddresses(final String memcachedNodes, final String failoverNodes, final Collection<InetSocketAddress> expectedSocketAddresses) {
		final MemcachedNodesManager result = createFor( memcachedNodes, failoverNodes, null, _mcc );
		assertEquals(result.getAllMemcachedAddresses(), expectedSocketAddresses);
	}

	@Test
	public void testGetSessionIdFormat() {
		final SessionIdFormat sessionIdFormat = createFor( "n1:localhost:11211", null, null, _mcc ).getSessionIdFormat();
		assertNotNull(sessionIdFormat);
	}

    @Test
    public void testSessionIdFormatForSingleNodeSetupShouldSupportLocking() {
        final SessionIdFormat sessionIdFormat = createFor( "localhost:11211", null, StorageKeyFormat.EMPTY, _mcc ).getSessionIdFormat();
        assertNotNull(sessionIdFormat);
        final String sessionId = "12345678";
        assertEquals(sessionIdFormat.createLockName(sessionId), "lock:" + sessionId);
    }

	@Test
	public void testCreateSessionIdShouldOnlyAddNodeIdIfPresent() {
		assertEquals(createFor( "n1:localhost:11211", null, null, _mcc ).createSessionId("foo"), "foo-n1" );
		assertEquals(createFor( "localhost:11211", null, null, _mcc ).createSessionId("foo"), "foo" );
	}

	@Test
	public void testSetNodeAvailable() {
		final MemcachedNodesManager cut = createFor( "n1:localhost:11211,n2:localhost:11212", null, null, _mcc );
		assertTrue(cut.isNodeAvailable("n1"));
		assertTrue(cut.isNodeAvailable("n2"));

		cut.setNodeAvailable("n1", false);

		assertFalse(cut.isNodeAvailable("n1"));
		assertTrue(cut.isNodeAvailable("n2"));
	}

    @Test
    public void testIsCouchbaseBucketConfig() {
        assertTrue(createFor("http://10.10.0.1:8091/pools", null, null, _mcc ).isCouchbaseBucketConfig());
        assertTrue(createFor("http://10.10.0.1:8091/pools,http://10.10.0.2:8091/pools", null, null, _mcc ).isCouchbaseBucketConfig());
    }

    @Test
    public void testGetCouchbaseBucketURIs() throws URISyntaxException {
        assertEquals(createFor("http://10.10.0.1:8091/pools", null, null, _mcc ).getCouchbaseBucketURIs(),
                Arrays.asList(new URI("http://10.10.0.1:8091/pools")));
        assertEquals(createFor("http://10.10.0.1:8091/pools,http://10.10.0.2:8091/pools", null, null, _mcc ).getCouchbaseBucketURIs(),
                Arrays.asList(new URI("http://10.10.0.1:8091/pools"), new URI("http://10.10.0.2:8091/pools")));
    }

    @Test
    public void testChangeSessionIdForTomcatFailover() {
        assertEquals(createFor("localhost:11211", null, null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", null, null), null), sessionId("123", null, null));
        assertEquals(createFor("localhost:11211", null, null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", null, "tc1"), "tc2"), sessionId("123", null, "tc2"));

        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", null, null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", null), null), sessionId("123", "n1", null));
        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", null, null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", null), "tc2"), sessionId("123", "n1", "tc2"));
        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", null, null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", "tc1"), "tc2"), sessionId("123", "n1", "tc2"));

        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", "n2", null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", null), null), sessionId("123", "n1", null));
        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", "n2", null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", null), "tc2"), sessionId("123", "n1", "tc2"));
        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", "n2", null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", "tc1"), "tc2"), sessionId("123", "n1", "tc2"));

        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", "n1", null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", null), null), sessionId("123", "n2", null));
        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", "n1", null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", null), "tc2"), sessionId("123", "n2", "tc2"));
        assertEquals(createFor("n1:localhost:11211,n2:localhost:11212", "n1", null, _mcc)
                .changeSessionIdForTomcatFailover(sessionId("123", "n1", "tc1"), "tc2"), sessionId("123", "n2", "tc2"));

    }

    private static String sessionId(final String plainId, final String memcachedId, final String jvmRoute) {
        final SessionIdFormat sessionIdFormat = new SessionIdFormat();
        final String withMemcachedId = sessionIdFormat.createSessionId(plainId, memcachedId);
        return jvmRoute != null ? sessionIdFormat.changeJvmRoute(withMemcachedId, jvmRoute) : withMemcachedId;
    }

}
