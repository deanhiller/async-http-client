package com.ning.http.client.providers.netty;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.ChunkingTest;
import com.ning.http.client.async.ProviderUtil;

public class NettyChunkingTest extends ChunkingTest {
    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Test
	@Override
	public void testCustomChunking() throws Throwable {
		// TODO Auto-generated method stub
		super.testCustomChunking();
	}
    
}
