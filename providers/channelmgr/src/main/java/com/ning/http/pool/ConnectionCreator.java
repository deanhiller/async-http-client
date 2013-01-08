package com.ning.http.pool;

public interface ConnectionCreator<T> {

	Connection<T> createConnection();

}
