/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.rocketmq.connect.runtime.service;

import io.openmessaging.connector.api.data.RecordOffset;
import io.openmessaging.connector.api.data.RecordPartition;
import java.util.List;
import java.util.Map;

/**
 * Interface for position manager.
 */
public interface PositionManagementService {

    /**
     * Start the manager.
     */
    void start();

    /**
     * Stop the manager.
     */
    void stop();

    /**
     * Persist position info in a persist store.
     */
    void persist();

    /**
     * Persist position info in a persist store.
     */
    void load();

    /**
     * Synchronize to other nodes.
     * */
    void synchronize();

    /**
     * Get the current position table.
     *
     * @return
     */
    Map<RecordPartition, RecordOffset> getPositionTable();

    RecordOffset getPosition(RecordPartition partition);

    /**
     * Put a position info.
     */
    void putPosition(Map<RecordPartition, RecordOffset> positions);

    void putPosition(RecordPartition partition, RecordOffset position);

    /**
     * Remove a position info.
     *
     * @param partitions
     */
    void removePosition(List<RecordPartition> partitions);

    /**
     * Register a listener.
     *
     * @param listener
     */
    void registerListener(PositionUpdateListener listener);

    interface PositionUpdateListener {

        /**
         * Invoke while position info updated.
         */
        void onPositionUpdate();
    }
}
