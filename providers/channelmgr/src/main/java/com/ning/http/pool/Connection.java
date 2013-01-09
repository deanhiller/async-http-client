package com.ning.http.pool;

public abstract class Connection<T> {

	private String baseUrl;
	private T channel;
	private long lastTimeUsed;
	private AsyncConnectionPoolImpl<T> pool;
	
	public Connection(T channel2) {
		this.channel = channel2;
		this.lastTimeUsed = System.currentTimeMillis();
	}

	
	/**
	 * Implement how to close the channel so you can do getChannel().close() in the subclass.
	 */
	protected abstract void close();
	
	public void releaseConnection() {
		pool.releaseConnection(this);
	}
	
	public String getBaseUrl() {
		return baseUrl;
	}

	void setBaseUrl(String uri) {
		this.baseUrl = uri;
	}

	void setPool(AsyncConnectionPoolImpl<T> pool) {
		this.pool = pool;
	}
	
	void setLastTimeUsed(long currentTimeMillis) {
		this.lastTimeUsed = currentTimeMillis;
	}

	long getLastTimeUsed() {
		return lastTimeUsed;
	}

	public T getChannel() {
		return channel;
	}
	
}
