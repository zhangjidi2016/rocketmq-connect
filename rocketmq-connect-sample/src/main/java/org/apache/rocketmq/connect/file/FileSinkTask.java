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
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.rocketmq.connect.file;

import io.openmessaging.KeyValue;
import io.openmessaging.connector.api.component.task.sink.SinkTask;
import io.openmessaging.connector.api.component.task.sink.SinkTaskContext;
import io.openmessaging.connector.api.data.ConnectRecord;
import io.openmessaging.connector.api.data.RecordOffset;
import io.openmessaging.connector.api.data.RecordPartition;
import io.openmessaging.connector.api.errors.ConnectException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSinkTask extends SinkTask {

    private Logger log = LoggerFactory.getLogger(LoggerName.FILE_CONNECTOR);

    private FileConfig fileConfig;

    private PrintStream outputStream;
    
    private KeyValue config;

    @Override public void put(List<ConnectRecord> sinkDataEntries) {
        for (ConnectRecord record : sinkDataEntries) {
            Object payload = record.getData();
            log.trace("Writing line to {}: {}", logFilename(), payload);
            outputStream.println(payload);
        }

    }


    @Override public void flush(Map<RecordPartition, RecordOffset> currentOffsets) throws ConnectException {
        log.trace("Flushing output stream for {}", logFilename());
        outputStream.flush();
    }

    @Override public void start(SinkTaskContext sinkTaskContext) {
        fileConfig = new FileConfig();
        fileConfig.load(config);
        if (fileConfig.getFilename() == null || fileConfig.getFilename().isEmpty()) {
            outputStream = System.out;
        } else {
            try {
                outputStream = new PrintStream(
                    Files.newOutputStream(Paths.get(fileConfig.getFilename()), StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    false,
                    StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                throw new ConnectException("Couldn't find or create file '" + fileConfig.getFilename() + "' for FileStreamSinkTask", e);
            }
        }
    }

    @Override public void validate(KeyValue config) {

    }

    @Override public void init(KeyValue config) {
        this.config = config;
    }

    @Override public void stop() {
        if (outputStream != null && outputStream != System.out) {
            outputStream.close();
        }
    }

    @Override public void pause() {

    }

    @Override public void resume() {

    }

    private String logFilename() {
        return fileConfig.getFilename() == null ? "stdout" : fileConfig.getFilename();
    }

}
