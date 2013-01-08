package com.ning.http.client.providers.chanmgr.chain;

import java.io.IOException;

import org.playorm.nio.api.channels.Channel;
import org.playorm.nio.api.channels.RegisterableChannel;
import org.playorm.nio.api.handlers.OperationCallback;

import com.ning.http.client.providers.chanmgr.ChanMgrResponseFuture;

public class BWriteListener<T> implements OperationCallback {

	private ChanMgrResponseFuture<T> future;

	public BWriteListener(ChanMgrResponseFuture<T> future) {
		this.future = future;
	}

	@Override
	public void finished(Channel c) throws IOException {
		//is there anyway to notify the client in async-http-client that the write request went through?
	}

	@Override
	public void failed(RegisterableChannel c, Throwable e) {
	}

}
