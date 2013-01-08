package com.ning.http.client.providers.chanmgr;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;

public class ChanMgrUtil {

	public static AsyncHttpClient provider(AsyncHttpClientConfig config) {
        if (config == null) {
            config = new AsyncHttpClientConfig.Builder().build();
        }
        return new AsyncHttpClient(new ChanMgrAsyncHttpProvider(config), config);
	}

}
