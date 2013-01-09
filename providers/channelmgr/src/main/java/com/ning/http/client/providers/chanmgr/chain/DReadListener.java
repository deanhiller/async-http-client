package com.ning.http.client.providers.chanmgr.chain;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.playorm.nio.api.channels.Channel;
import org.playorm.nio.api.channels.TCPChannel;
import org.playorm.nio.api.handlers.DataChunk;
import org.playorm.nio.api.handlers.DataListener;

import com.ning.http.client.providers.chanmgr.ChanMgrResponseFuture;
import com.ning.http.pool.AsyncConnectionPool;
import com.ning.http.pool.AsyncConnectionPoolImpl;
import com.ning.http.pool.Connection;

public class DReadListener<T> implements DataListener {

	@Override
	public void incomingData(Channel channel, DataChunk b) throws IOException {
		ChanMgrResponseFuture<T> future = fetchFuture(channel);
		
		//This method is called many times in the case of chunking so we can't release the connection until
		//finished = true
		
		//if(finished)
			future.getConnection().releaseConnection();
	}

	@Override
	public void farEndClosed(Channel channel) {
		ChanMgrResponseFuture<T> future = fetchFuture(channel);
	}

	@Override
	public void failure(Channel channel, ByteBuffer data, Exception e) {
		ChanMgrResponseFuture<T> future = fetchFuture(channel);
		future.abort(e);
	}

	private ChanMgrResponseFuture<T> fetchFuture(Channel channel) {
		ChanMgrResponseFuture<T> future = (ChanMgrResponseFuture<T>) channel.getSession().get("future");
		return future;
	}

}
