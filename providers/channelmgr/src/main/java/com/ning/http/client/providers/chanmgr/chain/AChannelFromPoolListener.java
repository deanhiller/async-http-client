package com.ning.http.client.providers.chanmgr.chain;

import java.net.InetSocketAddress;
import java.net.URI;

import org.playorm.nio.api.channels.TCPChannel;
import org.playorm.nio.api.handlers.FutureOperation;

import com.ning.http.client.Request;
import com.ning.http.client.providers.chanmgr.ChanMgrResponseFuture;
import com.ning.http.pool.AsyncConnectionPool;
import com.ning.http.pool.AsyncConnectionPoolImpl;
import com.ning.http.pool.Connection;
import com.ning.http.pool.ConnectionAvailableListener;
import com.ning.http.util.AsyncHttpProviderUtils;

public class AChannelFromPoolListener<T> implements ConnectionAvailableListener<TCPChannel> {

	private ChanMgrResponseFuture<T> future;
	private Request request;
	private URI uri;
	private BConnectCallback<T> connectListener = new BConnectCallback<T>();
	private DReadListener<T> readListener = new DReadListener<T>();

	public AChannelFromPoolListener(Request request, ChanMgrResponseFuture<T> future, URI uri) {
		this.request = request;
		this.future = future;
		this.uri = uri;
	}

	@Override
	public void connectionAvailable(Connection<TCPChannel> connection) {
		TCPChannel channel = connection.getChannel();
		channel.getSession().put("future", future);

		//if not connected, call channel.connect and set connect listener to hear the connection finish
        //OR if connected already, call the connectListener directly to start writing request
        if(!channel.isConnected()) {
        	//If channel is NOT connected, this is a brand new channel we need to setup so we need to
        	// 1. register a permanent read listener
        	// 2. connect up the socket asynchronously(it calls connectListener.finished when connect is done so this thread can return and do more)
        	channel.registerForReads(readListener);
        	
            InetSocketAddress remoteAddress = null;
            if (request.getInetAddress() != null) {
                remoteAddress = new InetSocketAddress(request.getInetAddress(), AsyncHttpProviderUtils.getPort(uri));
            }
            
        	attachStateToChannel(connection, channel);
        	FutureOperation futureOp = channel.connect(remoteAddress);
        	futureOp.setListener(connectListener);
        } else {
        	//We are connected already so this was a previous connection so just
        	//set the future object on the old readListener to receive new responses
        	attachStateToChannel(connection, channel);
        	connectListener.performWrite(channel);
        }	
	}

	private void attachStateToChannel(Connection<TCPChannel> connection, TCPChannel channel) {
		channel.getSession().put("future", future);
		future.setConnection(connection);
	}

	@Override
	public void timeoutNotAvailble(String uri) {
		future.abort(new PoolTimeoutException("Could not retrieve connection from pool in alloted time.  increate number of connections in pool or timeout period possible?"));
	}

}
