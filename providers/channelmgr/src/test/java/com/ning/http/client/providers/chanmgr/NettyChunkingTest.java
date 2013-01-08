package com.ning.http.client.providers.chanmgr;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.ChunkingTest;

public class NettyChunkingTest extends ChunkingTest {
    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ChanMgrUtil.provider(config);
    }
    
    @Test()
    public void testCustomChunking() throws Throwable {
    	super.testCustomChunking();
    }
}
