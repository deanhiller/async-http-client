package com.ning.http.pool;

public class PoolConfig {

	private int maxTotalConnections;
	private int maxConnectionPerHost;
	private long idleConnectionInPoolTimeoutInMs;
	private int requestTimeoutInMs;

	
	public void setMaxTotalConnections(int maxTotalConnections) {
		this.maxTotalConnections = maxTotalConnections;
	}

	public void setMaxConnectionPerHost(int maxConnectionPerHost) {
		this.maxConnectionPerHost = maxConnectionPerHost;
	}

	public void setIdleConnectionInPoolTimeoutInMs(
			long idleConnectionInPoolTimeoutInMs) {
		this.idleConnectionInPoolTimeoutInMs = idleConnectionInPoolTimeoutInMs;
	}

	public void setRequestTimeoutInMs(int requestTimeoutInMs) {
		this.requestTimeoutInMs = requestTimeoutInMs;
	}

	public int getMaxTotalConnections() {
		return maxTotalConnections;
	}

	public int getMaxConnectionPerHost() {
		return maxConnectionPerHost;
	}

	public long getIdleConnectionInPoolTimeoutInMs() {
		return idleConnectionInPoolTimeoutInMs;
	}

	public int getRequestTimeoutInMs() {
		return requestTimeoutInMs;
	}

}
