/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.replicator;

import io.openmessaging.KeyValue;
import io.openmessaging.connector.api.component.connector.ConnectorContext;
import io.openmessaging.connector.api.component.task.Task;
import io.openmessaging.connector.api.component.task.source.SourceConnector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.protocol.body.ClusterInfo;
import org.apache.rocketmq.common.protocol.body.ConsumeStatsList;
import org.apache.rocketmq.common.protocol.body.SubscriptionGroupWrapper;
import org.apache.rocketmq.common.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.remoting.exception.RemotingConnectException;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.replicator.common.Utils;
import org.apache.rocketmq.replicator.config.ConfigDefine;
import org.apache.rocketmq.replicator.config.RmqConnectorConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.tools.command.CommandUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RmqMetaReplicator extends SourceConnector {
    private static final Logger log = LoggerFactory.getLogger(RmqSourceReplicator.class);

    private static final Set<String> INNER_CONSUMER_GROUPS = new HashSet<>();
    private static final Set<String> SYS_CONSUMER_PREFIX = new HashSet<>();

    private RmqConnectorConfig replicatorConfig;

    private volatile boolean configValid = false;
    private Set<String> knownGroups;
    private DefaultMQAdminExt srcMQAdminExt;
    private DefaultMQAdminExt targetMQAdminExt;
    private volatile boolean adminStarted;
    private ScheduledExecutorService executor;
    private List<Pattern> whiteListPatterns;

    static {
        INNER_CONSUMER_GROUPS.add(MixAll.TOOLS_CONSUMER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.FILTERSRV_CONSUMER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.MONITOR_CONSUMER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.CLIENT_INNER_PRODUCER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.SELF_TEST_PRODUCER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.SELF_TEST_CONSUMER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.CID_ONSAPI_PERMISSION_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.ONS_HTTP_PROXY_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.CID_ONSAPI_OWNER_GROUP);
        INNER_CONSUMER_GROUPS.add(MixAll.CID_ONSAPI_PULL_GROUP);

        SYS_CONSUMER_PREFIX.add(MixAll.CID_RMQ_SYS_PREFIX);
        SYS_CONSUMER_PREFIX.add("PositionManage");
        SYS_CONSUMER_PREFIX.add("ConfigManage");
        SYS_CONSUMER_PREFIX.add("OffsetManage");
        SYS_CONSUMER_PREFIX.add("DefaultConnectCluster");
        SYS_CONSUMER_PREFIX.add("RebalanceService");
    }

    public RmqMetaReplicator() {
        replicatorConfig = new RmqConnectorConfig();
        knownGroups = new HashSet<>();
        whiteListPatterns = new ArrayList<>();
        executor = Executors.newSingleThreadScheduledExecutor(new BasicThreadFactory.Builder().namingPattern("RmqMetaReplicator-SourceWatcher-%d").daemon(true).build());
    }

    @Override
    public void validate(KeyValue config) {
        // Check they need key.
        for (String requestKey : ConfigDefine.REQUEST_CONFIG) {
            if (!config.containsKey(requestKey)) {
                log.error("RmqMetaReplicator check need key error , request config key: " + requestKey);
                throw new RuntimeException("RmqMetaReplicator check need key error.");
            }
        }
    }

    @Override
    public void init(KeyValue config) {
        try {
            replicatorConfig.init(config);
        } catch (IllegalArgumentException e) {
            log.error("RmqMetaReplicator validate config error.", e);
            throw new IllegalArgumentException("RmqMetaReplicator validate config error.");
        }
        this.configValid = true;
        this.prepare();
    }

    @Override
    public void start(ConnectorContext componentContext) {
        super.start(componentContext);
        log.info("starting...");
        executor.scheduleAtFixedRate(this::refreshConsumerGroups, replicatorConfig.getRefreshInterval(), replicatorConfig.getRefreshInterval(), TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(this::syncSubConfig, replicatorConfig.getRefreshInterval(), replicatorConfig.getRefreshInterval(), TimeUnit.SECONDS);
    }

    @Override public void stop() {
        log.info("stopping...");
        this.executor.shutdown();
        this.srcMQAdminExt.shutdown();
        this.targetMQAdminExt.shutdown();
    }

    @Override public void pause() {

    }

    @Override public void resume() {

    }

    @Override public Class<? extends Task> taskClass() {
        return MetaSourceTask.class;
    }

    @Override
    public List<KeyValue> taskConfigs(int maxTasks) {
        log.debug("preparing taskConfig...");
        if (!configValid) {
            return new ArrayList<>();
        }

        try {
            this.syncSubConfig();
            this.knownGroups = this.fetchConsumerGroups();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Utils.groupPartitions(new ArrayList<>(this.knownGroups), replicatorConfig, maxTasks);
    }

    private void prepare() {
        this.initWhiteListPatterns();
        log.info("RocketMQ meta replicator init success for whiteListPatterns.");

        this.startMQAdminTools();
        log.info("RocketMQ meta replicator start success for mqAdminTools.");
    }

    private synchronized void startMQAdminTools() {
        if (!configValid || adminStarted) {
            return;
        }

        try {
            this.srcMQAdminExt = Utils.startSrcMQAdminTool(this.replicatorConfig);
            this.targetMQAdminExt = Utils.startTargetMQAdminTool(this.replicatorConfig);
            this.adminStarted = true;
        } catch (MQClientException e) {
            log.error("RocketMQ meta replicator start failed for `startMQAdminTools` exception.", e);
            throw new IllegalStateException("Replicator start failed for `startMQAdminTools` exception.");
        }
    }

    private void refreshConsumerGroups() {
        try {
            log.debug("refreshConsumerGroups...");
            Set<String> groups = fetchConsumerGroups();
            Set<String> newGroups = new HashSet<>(groups);
            Set<String> deadGroups = new HashSet<>(knownGroups);
            newGroups.removeAll(knownGroups);
            deadGroups.removeAll(groups);
            if (!newGroups.isEmpty() || !deadGroups.isEmpty()) {
                log.info("reconfig consumer groups, new Groups: {} , dead groups: {}, previous groups: {}", newGroups, deadGroups, knownGroups);
                knownGroups = groups;
                connectorContext.requestTaskReconfiguration();
            }
        } catch (Exception e) {
            log.error("refresh consumer groups failed.", e);
        }
    }

    private void syncSubConfig() {
        try {
            Set<String> masterSet =
                CommandUtil.fetchMasterAddrByClusterName(this.srcMQAdminExt, replicatorConfig.getSrcCluster());
            List<String> masters = new ArrayList<>(masterSet);
            Collections.shuffle(masters);

            Set<String> targetBrokers =
                CommandUtil.fetchMasterAddrByClusterName(this.targetMQAdminExt, replicatorConfig.getTargetCluster());

            String addr = masters.get(0);
            SubscriptionGroupWrapper sub = this.srcMQAdminExt.getAllSubscriptionGroup(addr, TimeUnit.SECONDS.toMillis(10));
            for (Map.Entry<String, SubscriptionGroupConfig> entry : sub.getSubscriptionGroupTable().entrySet()) {
                if (skipInnerGroup(entry.getKey()) || skipNotInWhiteList(entry.getKey())) {
                    ensureSubConfig(targetBrokers, entry.getValue());
                }
            }
        } catch (Exception e) {
            log.error("syncSubConfig failed", e);
        }
    }

    private void ensureSubConfig(Collection<String> targetBrokers,
        SubscriptionGroupConfig subConfig) throws InterruptedException, RemotingException, MQClientException, MQBrokerException {
        for (String addr : targetBrokers) {
            this.targetMQAdminExt.createAndUpdateSubscriptionGroupConfig(addr, subConfig);
        }
    }

    private Set<String> fetchConsumerGroups() throws InterruptedException, RemotingTimeoutException, MQClientException, RemotingSendRequestException, RemotingConnectException, MQBrokerException {
        return listGroups().stream().filter(this::skipInnerGroup).filter(this::skipNotInWhiteList).collect(Collectors.toSet());
    }

    private Set<String> listGroups() throws InterruptedException, RemotingTimeoutException, MQClientException, RemotingSendRequestException, RemotingConnectException, MQBrokerException {
        Set<String> groups = new HashSet<>();
        ClusterInfo clusterInfo = this.srcMQAdminExt.examineBrokerClusterInfo();
        String[] addrs = clusterInfo.retrieveAllAddrByCluster(this.replicatorConfig.getSrcCluster());
        for (String addr : addrs) {
            ConsumeStatsList stats = this.srcMQAdminExt.fetchConsumeStatsInBroker(addr, true, 3 * 1000);
            stats.getConsumeStatsList().stream().map(Map::keySet).forEach(groups::addAll);
        }
        return groups;
    }

    private boolean skipInnerGroup(String group) {
        if (INNER_CONSUMER_GROUPS.contains(group)) {
            return false;
        }
        return !SYS_CONSUMER_PREFIX.stream().anyMatch(prefix -> group.startsWith(prefix));
    }

    private boolean skipNotInWhiteList(String group) {
        for (Pattern pattern : this.whiteListPatterns) {
            Matcher matcher = pattern.matcher(group);
            if (matcher.matches()) {
                return true;
            }
        }
        return false;
    }

    private void initWhiteListPatterns() {
        for (String group : this.replicatorConfig.getWhiteList()) {
            Pattern pattern = Pattern.compile(group);
            this.whiteListPatterns.add(pattern);
        }
    }
}
