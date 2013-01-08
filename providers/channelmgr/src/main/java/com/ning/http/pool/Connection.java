package com.ning.http.pool;

public abstract class Connection<T> {

	private String baseUrl;
	private T channel;
	private long lastTimeUsed;
	
	public Connection(T channel2) {
		this.channel = channel2;
		this.lastTimeUsed = System.currentTimeMillis();
	}

	
	/**
	 * Implement how to close the channel so you can do getChannel().close() in the subclass.
	 */
	protected abstract void close();
	
	public String getBaseUrl() {
		return baseUrl;
	}

	void setBaseUrl(String uri) {
		this.baseUrl = uri;
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
