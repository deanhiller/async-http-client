package com.ning.http.pool;

public interface AsyncConnectionPool<T> {

	public void setCreator(ConnectionCreator<T> creator);

	/**
	 * Fires back the connection when available to the listener which may be immediately or if pool is used up
	 * will be when another connection becomes available OR when request timeout period elapses, we fire back
	 * a timeout event as request timeout has exceeded.
	 * 
	 * @param l
	 */
	void obtainConnection(String baseUrl, ConnectionAvailableListener<T> l);

	public void clear();
	
	//void releaseConnection(Connection<T> state);	
	
}
