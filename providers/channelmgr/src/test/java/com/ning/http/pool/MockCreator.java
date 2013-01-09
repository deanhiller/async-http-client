package com.ning.http.pool;

public class MockCreator implements ConnectionCreator<Integer> {

	private int counter;
	
	@Override
	public Connection<Integer> createConnection(ConnectionCloseListener<Integer> listener) {
		return new MyConnection(counter++);
	}
	
	private class MyConnection extends Connection<Integer> {

		private boolean isClosed;

		public MyConnection(int channel) {
			super(channel);
		}

		@Override
		protected void close() {
			isClosed = true;
		}
	}
}
