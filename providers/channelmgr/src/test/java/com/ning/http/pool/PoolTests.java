package com.ning.http.pool;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class PoolTests {

	private AsyncConnectionPoolImpl<Integer> pool;
	private MockTimer mockTimer;
	private Runnable idleConnectionRunnable;

	@BeforeMethod
	public void setup() {
		mockTimer = new MockTimer();
    	
		PoolConfig config = new PoolConfig();
		config.setMaxConnectionPerHost(2);
		config.setMaxTotalConnections(3);
		config.setRequestTimeoutInMs(100000);
		pool = new AsyncConnectionPoolImpl<Integer>(config , mockTimer);
    	
		idleConnectionRunnable = mockTimer.fetchIdleRunnable();
		
		MockCreator mockCreator = new MockCreator();
		pool.setCreator(mockCreator);
	}
	
    @Test
    public void testReuse() throws Throwable {
		MockListener l = new MockListener();
		Connection<Integer> conn1 = useUpTwoConnections(l);
		
		pool.releaseConnection(conn1);
		
		pool.obtainConnection("abc", l);
		
		Assert.assertNull(mockTimer.fetchRunnableAndReset());
		
		Connection<Integer> conn3 = l.fetchAndReset();
		Assert.assertEquals(conn1, conn3);
		Assert.assertEquals(conn1.getChannel(), conn3.getChannel());
    }

	private Connection<Integer> useUpTwoConnections(MockListener l) {
		pool.obtainConnection("abc", l);
		
		//expect listener to fire immediately
		Connection<Integer> conn1 = l.fetchAndReset();
		Assert.assertEquals(new Integer(0), conn1.getChannel());
		
		pool.obtainConnection("abc", l);
		Connection<Integer> conn2 = l.fetchAndReset();
		Assert.assertEquals(new Integer(1), conn2.getChannel());
		return conn1;
	}
    
    @Test
    public void testMaxHost() {
		MockListener l = new MockListener();
		Connection<Integer> conn1 = useUpTwoConnections(l);

		MockListener list2 = new MockListener();
		pool.obtainConnection("abc", list2);
		//since we are full here, listener will not fire so verify null(ie. no fire happened)..
		Assert.assertNull(list2.fetchAndReset());
		//This means a runnable is scheduled as well
		Runnable timeoutRunnable = mockTimer.fetchRunnableAndReset();
		Assert.assertNotNull(timeoutRunnable);

		//now, let's release a connection and see if we fire
		pool.releaseConnection(conn1);
		
		//now the release fires...
		Connection<Integer> conn3 = list2.fetchAndReset();
		Assert.assertEquals(conn1, conn3);
    }
    
    @Test
    public void testMaxHostTimeout() {
		MockListener l = new MockListener();
		useUpTwoConnections(l);

		MockListener list2 = new MockListener();
		pool.obtainConnection("abc", list2);
		//since we are full here, listener will not fire so verify null(ie. no fire happened)..
		Assert.assertNull(list2.fetchAndReset());
		//This means a runnable is scheduled as well
		Runnable timeoutRunnable = mockTimer.fetchRunnableAndReset();
		Assert.assertNotNull(timeoutRunnable);

		//now if the timer fires, we have timed out and client should hear that through listener
		timeoutRunnable.run();
		String uri = list2.fetchFailure();
		Assert.assertEquals("abc", uri);
		
    }
    
    @Test
    public void testMaxTotal() {
    	MockListener l = new MockListener();
    	Connection<Integer> conn = useUpTwoConnections(l);
    	
    	MockListener list2 = new MockListener();
    	//now use up last connection for total connections
    	pool.obtainConnection("def", list2);
    	Connection<Integer> conn3 = list2.fetchAndReset();
    	Assert.assertEquals(new Integer(2), conn3.getChannel());
    	
    	MockListener list3 = new MockListener();
    	//now this connection ends up in pending
    	pool.obtainConnection("abc", list3);
    	Assert.assertNull(list3.fetchAndReset());
    	
    	MockListener list4 = new MockListener();
    	pool.obtainConnection("zyx", list4);
    	Assert.assertNull(list4.fetchAndReset());
    	
    	pool.releaseConnection(conn3);
    	//listener3 still can't fire because his pool is still full
    	Assert.assertNull(list3.fetchAndReset());
    	
    	//BUT listener4 should fire as we are below max connections and he has no one in his pool
    	Connection<Integer> conn4 = list4.fetchAndReset();
    	Assert.assertNotNull(conn4);
    	
    	int idle = pool.getNumIdleConnections();
    	int inUse = pool.getNumInUseConnections();
    	Assert.assertEquals(3, idle+inUse);
    	
    	String poolStr = pool+"";
    	System.out.println("poolStr="+poolStr);
    }
}
