package com.ning.http.pool;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HostPool<T> {

	private ConcurrentLinkedQueue<Connection<T>> idleConnections = new ConcurrentLinkedQueue<Connection<T>>();
	private Set<Connection<T>> inUseConnections = new HashSet<Connection<T>>();

	@Override
	public String toString() {
		return "[IDLE="+idleConnections+",   IN_USE="+inUseConnections+"]";
	}

	public Connection<T> grabConnection(ConnectionCreator<T> creator, ConnectionCloseListener<T> listener) {
    	Connection<T> conn = idleConnections.poll();
    	if(conn == null) {
    		conn = creator.createConnection(listener);
    	}
    	
		if(conn != null)
			inUseConnections.add(conn);
		return conn;
	}
	
	public void releaseConnection(Connection<T> conn) {
		inUseConnections.remove(conn);
		idleConnections.add(conn);
	}

	public void closeConnection(Connection<T> conn) {
		inUseConnections.remove(conn);
		idleConnections.remove(conn);
		conn.close();
	}
	
	public void connectionClosedFarEnd(Connection<T> conn) {
		inUseConnections.remove(conn);
		idleConnections.remove(conn);
	}
	
	public int numIdle() {
		return idleConnections.size();
	}

	public int numInUse() {
		return inUseConnections.size();
	}

	public int numTotalConnections() {
		return idleConnections.size() + inUseConnections.size();
	}

	public void releaseIdleConnections(long maxIdleTime) {
		long now = System.currentTimeMillis();
		Iterator<Connection<T>> iterator = idleConnections.iterator();
		while(iterator.hasNext()) {
			Connection<T> conn = iterator.next();
			long time = conn.getLastTimeUsed();

			if(now-time > maxIdleTime) {
				idleConnections.remove(conn);
				conn.close();
			}
		}
	}
}
