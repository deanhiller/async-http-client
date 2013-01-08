package com.ning.http.pool;

public interface ConnectionAvailableListener<T> {

	void connectionAvailable(Connection<T> connection);

	/**
	 * IF waiting for a connection to be released, we exceed the timeout period, we call this method on failure
	 * @param uri
	 */
	void timeoutNotAvailble(String uri);

}
