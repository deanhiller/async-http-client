package com.ning.http.client.providers.chanmgr.chain;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.playorm.nio.api.channels.Channel;
import org.playorm.nio.api.channels.RegisterableChannel;
import org.playorm.nio.api.handlers.FutureOperation;
import org.playorm.nio.api.handlers.OperationCallback;

import com.ning.http.client.providers.chanmgr.ChanMgrResponseFuture;

public class AConnectListener<T> implements OperationCallback {

	private ChanMgrResponseFuture<T> future;

	public AConnectListener(ChanMgrResponseFuture<T> future) {
		this.future = future;
	}

	@Override
	public void finished(Channel c) throws IOException {
		performWrite(c);
	}

	@Override
	public void failed(RegisterableChannel c, Throwable e) {
		// TODO Auto-generated method stub
		
	}

	public void performWrite(Channel c) {
		BWriteListener<T> writeListener = new BWriteListener<T>(future);
		
		ByteBuffer b = null;
		FutureOperation futureOp = c.write(b);
		futureOp.setListener(writeListener);
	}

}
