/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.chanmgr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.playorm.nio.api.channels.TCPChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.ConnectionsPool;

/**
 * A simple implementation of {@link com.ning.http.client.ConnectionsPool} based on a {@link java.util.concurrent.ConcurrentHashMap}
 */
public class ChanMgrConnectionsPool implements ConnectionsPool<String, TCPChannel> {

    private final static Logger log = LoggerFactory.getLogger(ChanMgrConnectionsPool.class);
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<TCPChannel>> connectionsPool = new ConcurrentHashMap<String, ConcurrentLinkedQueue<TCPChannel>>();
    private final Set<TCPChannel> openChannels = new HashSet<TCPChannel>();
    
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Timer idleConnectionDetector = new Timer(true);
    private final boolean sslConnectionPoolEnabled;
    private final int maxTotalConnections;
    private final int maxConnectionPerHost;
    private final int maxConnectionLifeTimeInMs;
    private final long maxIdleTime;

    public ChanMgrConnectionsPool(ChanMgrAsyncHttpProvider provider) {
        this.maxTotalConnections = provider.getConfig().getMaxTotalConnections();
        this.maxConnectionPerHost = provider.getConfig().getMaxConnectionPerHost();
        this.sslConnectionPoolEnabled = provider.getConfig().isSslConnectionPoolEnabled();
        this.maxIdleTime = provider.getConfig().getIdleConnectionInPoolTimeoutInMs();
        this.maxConnectionLifeTimeInMs = provider.getConfig().getMaxConnectionLifeTimeInMs();
        this.idleConnectionDetector.schedule(new IdleChannelDetector(), maxIdleTime, maxIdleTime);
    }

    /**
     * {@inheritDoc}
     */
    public boolean offer(String uri, TCPChannel channel) {
        if (isClosed.get()) return false;

        if (!sslConnectionPoolEnabled && uri.startsWith("https")) {
            return false;
        }

        Long createTime = (Long) channel.getSession().get("createTime");
        if (createTime == null){
        	channel.getSession().put("createTime", System.currentTimeMillis());
        }
        else if (maxConnectionLifeTimeInMs != -1 && (createTime + maxConnectionLifeTimeInMs) < System.currentTimeMillis() ) {
           log.debug("Channel {} expired", channel);
           return false;
        }

        log.debug("Adding uri: {} for channel {}", uri, channel);
        //channel.getPipeline().getContext(ChanMgrAsyncHttpProvider.class).setAttachment(new ChanMgrAsyncHttpProvider.DiscardEvent());

        ConcurrentLinkedQueue<TCPChannel> idleConnectionForHost = connectionsPool.get(uri);
        if (idleConnectionForHost == null) {
            ConcurrentLinkedQueue<TCPChannel> newPool = new ConcurrentLinkedQueue<TCPChannel>();
            idleConnectionForHost = connectionsPool.putIfAbsent(uri, newPool);
            if (idleConnectionForHost == null) 
            	idleConnectionForHost = newPool;
        }

        boolean added;
        int size = idleConnectionForHost.size();
        if (maxConnectionPerHost == -1 || size < maxConnectionPerHost) {
            TCPChannel idleChannel = channel;
            channel.getSession().put("uri", uri);
            synchronized (idleConnectionForHost) {
                added = idleConnectionForHost.add(idleChannel);
                openChannels.add(idleChannel);
            }
        } else {
            log.debug("Maximum number of requests per host reached {} for {}", maxConnectionPerHost, uri);
            added = false;
        }
        return added;
    }

    /**
     * {@inheritDoc}
     */
    public TCPChannel poll(String uri) {
        if (!sslConnectionPoolEnabled && uri.startsWith("https")) {
            return null;
        }

        TCPChannel idleChannel = null;
        ConcurrentLinkedQueue<TCPChannel> idleConnectionForHost = connectionsPool.get(uri);
        if (idleConnectionForHost != null) {
            boolean poolEmpty = false;
            while (!poolEmpty && idleChannel == null) {
                if (idleConnectionForHost.size() > 0) {
                    synchronized (idleConnectionForHost) {
                        idleChannel = idleConnectionForHost.poll();
                    }
                }

                if (idleChannel == null) {
                    poolEmpty = true;
                } else if (!idleChannel.isConnected() || idleChannel.isClosed()) {
                    idleChannel = null;
                    log.trace("Channel not connected or not opened!");
                }
            }
        }
        return idleChannel;
    }

    private boolean remove(TCPChannel pooledChannel) {
        if (pooledChannel == null || isClosed.get()) return false;

        String uri = (String) pooledChannel.getSession().get("uri");
        
        boolean isRemoved = false;
        ConcurrentLinkedQueue<TCPChannel> pooledConnectionForHost = connectionsPool.get(uri);
        if (pooledConnectionForHost != null) {
            isRemoved = pooledConnectionForHost.remove(pooledChannel);
        }
        openChannels.remove(pooledChannel);
        return isRemoved;
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeAll(TCPChannel channel) {
        return !isClosed.get() && remove(channel);
    }

    /**
     * {@inheritDoc}
     */
    public boolean canCacheConnection() {
        if (!isClosed.get() && maxTotalConnections != -1 && openChannels.size() >= maxTotalConnections) {
            return false;
        } else {
            return true;
        }
    }

	/**
     * {@inheritDoc}
     */
    public void destroy() {
        if (isClosed.getAndSet(true)) return;

        // stop timer
        idleConnectionDetector.cancel();
        idleConnectionDetector.purge();

        for (TCPChannel channel : openChannels) {
            close(channel);
        }
        connectionsPool.clear();
        openChannels.clear();
    }

    private void close(TCPChannel channel) {
        try {
        	openChannels.remove(channel);
        	channel.close();
        } catch (Throwable t) {
            // noop
        }
    }

    public final String toString() {
        return String.format("NettyConnectionPool: {pool-size: %d}", openChannels.size());
    }
    
    private class IdleChannelDetector extends TimerTask {
        @Override
        public void run() {
            try {
                if (isClosed.get()) return;

                if (log.isDebugEnabled()) {
                    Set<String> keys = connectionsPool.keySet();

                    for (String s : keys) {
                        log.debug("Entry count for : {} : {}", s, connectionsPool.get(s).size());
                    }
                }

                List<TCPChannel> channelsInTimeout = new ArrayList<TCPChannel>();
                long currentTime = System.currentTimeMillis();

                for (TCPChannel idleChannel : openChannels) {
                	long start = (Long) idleChannel.getSession().get("createTime");
                    long age = currentTime - start;
                    if (age > maxIdleTime) {

                        log.debug("Adding Candidate Idle Channel {}", idleChannel);

                        // store in an unsynchronized list to minimize the impact on the ConcurrentHashMap.
                        channelsInTimeout.add(idleChannel);
                    }
                }
                long endConcurrentLoop = System.currentTimeMillis();

                for (TCPChannel idleChannel : channelsInTimeout) {
                    if (remove(idleChannel)) {
                        log.debug("Closing Idle Channel {}", idleChannel);
                        close(idleChannel);
                    }
                }

                if (log.isTraceEnabled())
                    log.trace(String.format("%d channel open, %d idle channels closed (times: 1st-loop=%d, 2nd-loop=%d).\n",
                        connectionsPool.size(), channelsInTimeout.size(), endConcurrentLoop - currentTime, System.currentTimeMillis() - endConcurrentLoop));
            } catch (Throwable t) {
                log.error("uncaught exception!", t);
            }
        }
    }

}
