package com.ning.http.client.providers.chanmgr;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.playorm.nio.api.channels.TCPChannel;

import com.ning.http.client.ListenableFuture;
import com.ning.http.pool.Connection;

public class ChanMgrResponseFuture<T> implements ListenableFuture<T> {

	private Connection<TCPChannel> connection;

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isCancelled() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isDone() {
		throw new UnsupportedOperationException();
	}

	@Override
	public T get() throws InterruptedException, ExecutionException {
		throw new UnsupportedOperationException();
	}

	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException,
			ExecutionException, TimeoutException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void done(Callable<?> callable) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void abort(Throwable t) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void content(T v) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void touch() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getAndSetWriteHeaders(boolean writeHeader) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean getAndSetWriteBody(boolean writeBody) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public ListenableFuture<T> addListener(Runnable listener, Executor exec) {
		throw new UnsupportedOperationException();
	}

	public void setConnection(Connection<TCPChannel> connection) {
		this.connection = connection;
	}

	public Connection<TCPChannel> getConnection() {
		return connection;
	}

	public void getAndSetAuth(boolean b) {
		throw new UnsupportedOperationException();
	}

}
