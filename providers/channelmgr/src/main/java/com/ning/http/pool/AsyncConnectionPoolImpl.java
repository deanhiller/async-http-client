package com.ning.http.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.providers.chanmgr.ChanMgrAsyncHttpProvider;
import com.ning.http.client.providers.chanmgr.ChanMgrConnectionsPool;

public class AsyncConnectionPoolImpl<T> implements AsyncConnectionPool<T> {

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
	        	Connection<T> connection = idleConnectionForHost.grabConnection(creator);
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
		
		HostPool<T> idleConnectionsForHost = fetchQueue(baseUrl, connectionsPool);
		synchronized(this) {
			//release the connection back to it's pool
			idleConnectionsForHost.releaseConnection(state);
			
			//fetch the next pending request which could be for any pool
			for(int i = 0; i < pendingRequests.size(); i++) {
				PendingRequest<T> request = pendingRequests.get(i);
				if(process(request, maxPerHost, max))
					break; 
			}
		}
	}

	private boolean process(PendingRequest<T> request, int maxPerHost, int max) {
		HostPool<T> pool = fetchQueue(request.getBaseUrl(), connectionsPool);
		int numInUse = pool.numInUse();
        if(numInUse >= maxPerHost) {
        	log.debug("Pool still at max connections, skip this pending request=" +request.getBaseUrl());
        	return false;
        } else {
        	Connection<T> connection = pool.grabConnection(creator);
        	connection.setBaseUrl(request.getBaseUrl());
        	request.connectionAvailable(connection);
        	return true;
        }
	}

	private class IdleChannelDetector implements Runnable {

		@Override
		public void run() {
			//can't iterate over open 
			for(HostPool<T> pool : connectionsPool.values()) {
				//synchronize inside the for loop such that other threads still have a chance to obtain connections so if
				//there was 1000 connections, we don't stay in the loop too long(unless they are all to one server :( ).
				synchronized(AsyncConnectionPoolImpl.this) {
					pool.releaseIdleConnections(maxIdleTime);
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
		return 0;
	}

	public int getNumInUseConnections() {
		return 0;
	}
	
}
