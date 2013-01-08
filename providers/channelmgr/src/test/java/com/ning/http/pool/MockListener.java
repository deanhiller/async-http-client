package com.ning.http.pool;

public class MockListener implements ConnectionAvailableListener<Integer> {

	private Connection<Integer> connection;
	private String uri;

	@Override
	public void connectionAvailable(Connection<Integer> connection) {
		this.connection = connection;
	}

	@Override
	public void timeoutNotAvailble(String uri) {
		this.uri = uri;
	}

	public Connection<Integer> fetchAndReset() {
		Connection<Integer> temp = connection;
		connection = null;
		return temp;
	}

	public String fetchFailure() {
		String temp = uri;
		uri = null;
		return temp;
	}
	
	
}
