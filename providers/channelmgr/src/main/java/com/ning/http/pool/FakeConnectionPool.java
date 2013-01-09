package com.ning.http.pool;

import org.playorm.nio.api.channels.TCPChannel;

public class FakeConnectionPool implements AsyncConnectionPool<TCPChannel>, ConnectionCloseListener<TCPChannel> {

	private ConnectionCreator<TCPChannel> creator;

	@Override
	public void setCreator(ConnectionCreator<TCPChannel> creator) {
		this.creator = creator;
	}

	@Override
	public void obtainConnection(String baseUrl,
			ConnectionAvailableListener<TCPChannel> l) {
		Connection<TCPChannel> conn = creator.createConnection(this);
		l.connectionAvailable(conn);
	}

	@Override
	public void connectionClosed(Connection<TCPChannel> conn) {
		//no-op since connection is used only once anyways
	}

}
