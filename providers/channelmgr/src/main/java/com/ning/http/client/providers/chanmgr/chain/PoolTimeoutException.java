package com.ning.http.client.providers.chanmgr.chain;

public class PoolTimeoutException extends RuntimeException {

	public PoolTimeoutException() {
	}

	public PoolTimeoutException(String message) {
		super(message);
	}

	public PoolTimeoutException(Throwable cause) {
		super(cause);
	}

	public PoolTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}

}
