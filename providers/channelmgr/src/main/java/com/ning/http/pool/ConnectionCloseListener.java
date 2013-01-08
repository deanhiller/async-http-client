package com.ning.http.pool;

public interface ConnectionCloseListener<T> {

	public void connectionClosed(Connection<T> conn);
}
