package com.ning.http.pool;

import java.util.concurrent.ScheduledFuture;

class PendingRequest<T> implements Runnable {
	private ConnectionAvailableListener<T> listener;
	private String baseUrl;
	private ScheduledFuture<?> future;
	private boolean wasRun = false;
	private boolean cancelled = false;
	public PendingRequest(ConnectionAvailableListener<T> listener, String baseUrl) {
		super();
		this.listener = listener;
		this.baseUrl = baseUrl;
	}

	public void setFuture(ScheduledFuture<?> future) {
		this.future = future;
	}

	@Override
	public synchronized void run() {
		if(wasRun)
			return; //nothing to do, it barely beat us
		cancelled = true;

		listener.timeoutNotAvailble(baseUrl);
	}

	/**
	 * Returns whether we actually ran it.  true if successful, false if it was cancelled
	 * @param conn
	 * @return
	 */
	public synchronized boolean connectionAvailable(Connection<T> conn) {
		if(cancelled == true)
			return false;
		wasRun = true;
		if(future != null)
			future.cancel(true);
		
		listener.connectionAvailable(conn);
		return true;
	}

	public String getBaseUrl() {
		return baseUrl;
	}
}