/* 
 * Copyright (c) 2008-2010, Hazel Ltd. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hazelcast.client;

import com.hazelcast.core.Member;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class ConnectionManager implements MembershipListener {
    private volatile Connection currentConnection;
    private final AtomicInteger connectionIdGenerator = new AtomicInteger(-1);
    private final List<InetSocketAddress> clusterMembers = new CopyOnWriteArrayList<InetSocketAddress>();
    private final ILogger logger = Logger.getLogger(getClass().getName());
    private final HazelcastClient client;
    private volatile int lastDisconnectedConnectionId = -1;
    private ClientBinder binder;

    public ConnectionManager(HazelcastClient client, InetSocketAddress[] clusterMembers, boolean shuffle) {
        this.client = client;
        this.clusterMembers.addAll(Arrays.asList(clusterMembers));
        if (shuffle) {
            Collections.shuffle(this.clusterMembers);
        }
    }

    public ConnectionManager(final HazelcastClient client, InetSocketAddress address) {
        this.client = client;
        this.clusterMembers.add(address);
    }

    public Connection getConnection() throws IOException {
        if (currentConnection == null) {
            synchronized (this) {
                if (currentConnection == null) {
                    Connection connection = searchForAvailableConnection();
                    if (connection != null) {
                        logger.log(Level.FINE, "Client is connecting to " + connection);
                        binder.bind(connection);
                        currentConnection = connection;
                    }
                }
            }
        }
        return currentConnection;
    }

    public synchronized void destroyConnection(Connection connection) {
        if (currentConnection != null && currentConnection.getVersion() == connection.getVersion()) {
            logger.log(Level.WARNING, "Connection to " + currentConnection + " is lost");
            currentConnection = null;
        }
    }

    private void popAndPush(List<InetSocketAddress> clusterMembers) {
        InetSocketAddress address = clusterMembers.remove(0);
        clusterMembers.add(address);
    }

    private Connection searchForAvailableConnection() {
        Connection connection = null;
        popAndPush(clusterMembers);
        int counter = clusterMembers.size();
        while (counter > 0) {
            try {
                connection = getNextConnection();
                break;
            } catch (Exception e) {
                popAndPush(clusterMembers);
                counter--;
            }
        }
        return connection;
    }

    protected Connection getNextConnection() {
        InetSocketAddress address = clusterMembers.get(0);
        Connection connection = new Connection(address, connectionIdGenerator.incrementAndGet());
        return connection;
    }

    public synchronized void memberAdded(MembershipEvent membershipEvent) {
        if (!this.clusterMembers.contains(membershipEvent.getMember().getInetSocketAddress())) {
            this.clusterMembers.add(membershipEvent.getMember().getInetSocketAddress());
        }
    }

    public synchronized void memberRemoved(MembershipEvent membershipEvent) {
        this.clusterMembers.remove(membershipEvent.getMember().getInetSocketAddress());
    }

    public synchronized void updateMembers() {
        Set<Member> members = client.getCluster().getMembers();
        clusterMembers.clear();
        for (Member member : members) {
            clusterMembers.add(member.getInetSocketAddress());
        }
    }

    public synchronized boolean shouldExecuteOnDisconnect(Connection connection) {
        if (lastDisconnectedConnectionId >= connection.getVersion()) {
            return false;
        }
        lastDisconnectedConnectionId = connection.getVersion();
        return true;
    }

    public void setBinder(ClientBinder binder) {
        this.binder = binder;
    }

    List<InetSocketAddress> getClusterMembers() {
        return clusterMembers;
    }
}
