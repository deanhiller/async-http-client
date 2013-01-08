package com.ning.http.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.providers.chanmgr.ChanMgrConnectionsPool;

public class AsyncConnectionPoolImpl<T> implements AsyncConnectionPool<T>, ConnectionCloseListener<T> {

    private final static Logger log = LoggerFactory.getLogger(ChanMgrConnectionsPool.class);
    private final ConcurrentHashMap<String, HostPool<T>> connectionsPool = new ConcurrentHashMap<String, HostPool<T>>();
    private List<PendingRequest<T>> pendingRequests = new ArrayList<PendingRequest<T>>();
    
    //private final boolean sslConnectionPoolEnabled;
    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final long maxIdleTime;
	private ConnectionCreator<T> creator;
	private int requestTimeout;
	private ScheduledExecutorService timer;

    public AsyncConnectionPoolImpl(PoolConfig config, ScheduledExecutorService svc) {
        this.maxTotalConnections = config.getMaxTotalConnections();
        this.maxConnectionPerHost = config.getMaxConnectionPerHost();
        //this.sslConnectionPoolEnabled = config.isSslConnectionPoolEnabled();
        this.maxIdleTime = config.getIdleConnectionInPoolTimeoutInMs();
        this.requestTimeout = config.getRequestTimeoutInMs();
        this.timer = svc;
        timer.scheduleAtFixedRate(new IdleChannelDetector(), maxIdleTime, maxIdleTime, TimeUnit.MILLISECONDS);
    }

	@Override
	public void setCreator(ConnectionCreator<T> creator) {
		this.creator = creator;
	}

	@Override
	public void obtainConnection(String baseUrl, ConnectionAvailableListener<T> l) {
		HostPool<T> idleConnectionForHost = fetchQueue(baseUrl, connectionsPool);
		
		int maxPerHost = Integer.MAX_VALUE;
		int max = Integer.MAX_VALUE;
		
		if(maxConnectionPerHost > 0)
			maxPerHost = maxConnectionPerHost;
		if(maxTotalConnections > 0)
			max = maxTotalConnections;

        synchronized(this) {
            int size = idleConnectionForHost.numInUse();
            if(getTotalSize() >= max) {
            	log.debug("Maximum number of requests reached for total="+maxTotalConnections+" We are waiting for release of connection now ms="+requestTimeout);
            	schedulePendingRequest(baseUrl, l);
            } else if(size >= maxPerHost) {
	        	log.debug("Maximum number of requests reached for host="+baseUrl+" total="+maxConnectionPerHost+" We are waiting for release of connection now ms="+requestTimeout);
	        	schedulePendingRequest(baseUrl, l);
	        } else {
	        	Connection<T> connection = idleConnectionForHost.grabConnection(creator, this);
	        	connection.setBaseUrl(baseUrl);
	        	l.connectionAvailable(connection);
	        }
        }
	}

	private int getTotalSize() {
		//because we are outside every other hosts sync blocks, this is approximate but good enough for keeping connections roughly within
		//the total amoune of connections
		int total = 0;
		for(HostPool<T> host : connectionsPool.values()) {
			total += host.numTotalConnections();
		}
		return total;
	}

	private void schedulePendingRequest(String baseUrl, ConnectionAvailableListener<T> l) {
		//we need to queue the request
		PendingRequest<T> r = new PendingRequest<T>(l, baseUrl);
		ScheduledFuture<?> future = timer.schedule(r, requestTimeout, TimeUnit.MILLISECONDS);
		r.setFuture(future);

		pendingRequests.add(r);
	}

	private static <Z> HostPool<Z> fetchQueue(String baseUrl, ConcurrentHashMap<String, HostPool<Z>> map) {
		HostPool<Z> queue = map.get(baseUrl);
		if(queue == null) {
			HostPool<Z> newOne = new HostPool<Z>();
			queue = map.putIfAbsent(baseUrl, newOne);
			if(queue == null)
				queue = newOne;			
		}

		return queue;
	}

	@Override
	public void releaseConnection(Connection<T> state) {
		String baseUrl = state.getBaseUrl();
		state.setLastTimeUsed(System.currentTimeMillis());
		
		int maxPerHost = Integer.MAX_VALUE;
		int max = Integer.MAX_VALUE;
		
		if(maxConnectionPerHost > 0)
			maxPerHost = maxConnectionPerHost;
		if(maxTotalConnections > 0)
			max = maxTotalConnections;
		
		synchronized(this) {
			//can't release yet as we don't know if we have to close or just release from inUse to idle yet(depends if we are
			//maxed out AND if this request uri matches the one being released)
			
			HostPool<T> pool = fetchQueue(baseUrl, connectionsPool);
			pool.releaseConnection(state);
			
			//fetch the next pending request which could be for any pool
			for(int i = 0; i < pendingRequests.size(); i++) {
				PendingRequest<T> request = pendingRequests.get(i);
				if(process(request, maxPerHost, max, state)) {
					break; 
				}
			}
		}
	}

	private boolean process(PendingRequest<T> request, int maxPerHost, int max, Connection<T> state) {
		HostPool<T> pool = fetchQueue(request.getBaseUrl(), connectionsPool);
		int numInUse = pool.numInUse();
        if(numInUse >= maxPerHost) {
        	log.debug("Pool still at max connections, skip this pending request=" +request.getBaseUrl());
        	return false;
        } else if(!request.getBaseUrl().equals(state.getBaseUrl())) {
        	//This is NOT the same uri!!! sooooo if at max connections we MUST close this one being released to
        	//stay under max connections.
    		HostPool<T> previousHostsPool = fetchQueue(state.getBaseUrl(), connectionsPool);
			int totalSize = getTotalSize();
			if(totalSize >= max) {
				//we are at the max now AND uris don't match so we can't re-use this connection.  we must close it
				closeConnection(state, previousHostsPool);
			}
        }

        //Now, grab the connection AND if the baseUrls matched, it actually just grabs the one we just released
    	grabConnection(request, pool);
    	return true;
	}

	@Override
	public void connectionClosed(Connection<T> conn) {
		synchronized(this) {
			HostPool<T> pool = fetchQueue(conn.getBaseUrl(), connectionsPool);
			pool.connectionClosedFarEnd(conn);
		}
	}

	private void grabConnection(PendingRequest<T> request, HostPool<T> pool) {
		Connection<T> connection = pool.grabConnection(creator, this);
		connection.setBaseUrl(request.getBaseUrl());
		request.connectionAvailable(connection);
	}

	private void closeConnection(Connection<T> state, HostPool<T> previousHostsPool) {
		previousHostsPool.closeConnection(state);
		checkOnReleaseHostPool(state.getBaseUrl(), previousHostsPool);
	}

	private void checkOnReleaseHostPool(String uri,
			HostPool<T> previousHostsPool) {
		//now we need to check if we need to release the HostPool resource(ie. no connections left)
		int total = previousHostsPool.numTotalConnections();
		if(total == 0) {
			//release the memory here....pool is no longer used
			connectionsPool.remove(uri);
		}
	}

	private class IdleChannelDetector implements Runnable {

		@Override
		public void run() {
			//can't iterate over open 
			for(String key : connectionsPool.keySet()) {
				HostPool<T> pool = connectionsPool.get(key);
				//synchronize inside the for loop such that other threads still have a chance to obtain connections so if
				//there was 1000 connections, we don't stay in the loop too long(unless they are all to one server :( ).
				synchronized(AsyncConnectionPoolImpl.this) {
					pool.releaseIdleConnections(maxIdleTime);
					checkOnReleaseHostPool(key, pool);
				}
			}
		}
	}

	@Override
	public String toString() {
		String poolStr = "ConnectionPool...\n";
		for(String key : connectionsPool.keySet()) {
			HostPool<T> pool = connectionsPool.get(key);
			poolStr += key+"="+pool+"\n";
		}
		return poolStr;
	}

	public int getNumIdleConnections() {
		int total = 0;
		for(HostPool<T> host : connectionsPool.values()) {
			total += host.numIdle();
		}
		return total;
	}

	public int getNumInUseConnections() {
		int total = 0;
		for(HostPool<T> host : connectionsPool.values()) {
			total += host.numInUse();
		}
		return total;
	}
	
	public int getNumPools() {
		return connectionsPool.size();
	}

}
