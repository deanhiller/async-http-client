package com.ning.http.client.providers.chanmgr;

import org.playorm.nio.api.channels.TCPChannel;

import com.ning.http.pool.Connection;

public class ChanMgrConnection extends Connection<TCPChannel> {

	public ChanMgrConnection(TCPChannel channel) {
		super(channel);
	}

	@Override
	protected void close() {
		getChannel().close();
	}

}
