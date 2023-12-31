package com.example.scheduler;

import com.example.db.hbase.TaskRepository;
import com.example.model.config.ScanConfig;
import com.example.model.config.WorkerScanConfig;
import com.example.model.dao.StoredTask;
import com.example.rabbitmq.ClientTaskActor;
import com.example.scheduler.loadbalancer.LoadBalancedWorker;
import com.example.util.JobUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class Worker implements Runnable, LoadBalancedWorker {
    private static final byte[] TASK_META_DATA_CF = "meta".getBytes();
    private static final byte[] TASK_META_DATA = "data".getBytes();

    private AtomicBoolean active;
    private final String clientId;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final ClientTaskActor clientTaskActor;
    private final ScanConfig scanConfig;

    @Builder
    public Worker(String clientId,
                  TaskRepository taskRepository,
                  ObjectMapper objectMapper,
                  ClientTaskActor clientTaskActor,
                  ScanConfig scanConfig) {
        this.active = new AtomicBoolean(false);
        this.taskRepository = taskRepository;
        this.clientId = clientId;
        this.objectMapper = objectMapper;
        this.clientTaskActor = clientTaskActor;
        this.scanConfig = scanConfig;
    }
    @Override
    public void activate() {
        active.set(true);
    }

    @Override
    public void deactivate() {
        active.set(false);
    }

    @Override
    public void run() {
        activate();
        // TODO : Add multiple worker for each client for cleanup as well
        Instant startTime = Instant.now();
        try {
            if(!active.get()) {
                log.info("Worker not activated yet");
                return;
            }
            final Date currentDate = new Date();
            long end = JobUtil.nextDate(currentDate, 30, ChronoUnit.MINUTES).getTime();
            long start = new Date().getTime();
            byte[] startRowKey = Bytes.toBytes(String.format("%s:%015d", clientId, start));
            byte[] endRowKey = Bytes.toBytes(String.format("%s:%015d", clientId, end));
            log.info("start : {} , end : {}", String.format("%s:%015d", clientId, start), String.format("%s:%015d", clientId, end));
            Scan scan = new Scan()
                    .withStartRow(startRowKey, true)
                    .withStopRow(endRowKey, true)
                    .setCacheBlocks(false)
                    .setCaching(0)
                    .addColumn(TASK_META_DATA_CF, TASK_META_DATA);
            List<StoredTask> tasks;
            try(Table taskTable = taskRepository.getTable()) {
                ResultScanner result = taskTable.getScanner(scan);
                tasks = Arrays.stream(result.next(5))
                        .map(obj -> {
                            try {
                                return objectMapper.readValue(obj.getValue(TASK_META_DATA_CF, TASK_META_DATA), StoredTask.class);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .collect(Collectors.toList());
                tasks.forEach(task -> {
                    try {
                        log.info("Published");
                        clientTaskActor.publish(clientId, task);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log.info(objectMapper.writeValueAsString(tasks));
        } catch (Throwable t) {
            log.error(t.getMessage());
        }

        Instant endTime = Instant.now();
        log.info("client {} worker-type {} : Worker life span : {} to {}.", clientId, scanConfig.getName(), startTime, endTime);
    }
}
