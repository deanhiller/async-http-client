package com.ning.http.client.providers.chanmgr.chain;

import java.nio.ByteBuffer;

import org.playorm.nio.api.channels.Channel;
import org.playorm.nio.api.channels.RegisterableChannel;
import org.playorm.nio.api.handlers.FutureOperation;
import org.playorm.nio.api.handlers.OperationCallback;

import com.ning.http.client.providers.chanmgr.ChanMgrResponseFuture;

public class BConnectCallback<T> implements OperationCallback {

	@Override
	public void finished(Channel c) {
		performWrite(c);
	}

	@Override
	public void failed(RegisterableChannel c, Throwable e) {
		Channel channel = (Channel) c;
		ChanMgrResponseFuture<T> future = (ChanMgrResponseFuture<T>) channel.getSession().get("future");
		future.abort(e);
	}

	public void performWrite(Channel channel) {
		ChanMgrResponseFuture<T> future = (ChanMgrResponseFuture<T>) channel.getSession().get("future");
		CWriteListener<T> writeListener = new CWriteListener<T>(future);

		ByteBuffer b = null;
		FutureOperation futureOp = channel.write(b);
		futureOp.setListener(writeListener);
	}

}
