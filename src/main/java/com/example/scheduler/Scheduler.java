package com.example.scheduler;

import com.example.JobSchedulerConfiguration;
import com.example.db.hbase.JobRepository;
import com.example.db.hbase.TaskRepository;
import com.example.model.dto.Client;
import com.example.rabbitmq.ClientTaskActor;
import com.example.service.ClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.appform.dropwizard.actors.ConnectionRegistry;
import io.appform.dropwizard.actors.exceptionhandler.ExceptionHandlingFactory;
import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

@Singleton
@Slf4j
public class Scheduler implements Managed {
    private final ClientService clientService;
    private final ObjectMapper objectMapper;
    private final TaskRepository taskRepository;
    private final JobRepository jobRepository;
    private List<ClientTaskExtractor> clientTaskExtractorList = new ArrayList<>();
    private final ScheduledExecutorService scheduledExecutorService;
    private final ConnectionRegistry connectionRegistry;
    private final ExceptionHandlingFactory exceptionHandlingFactory;
    private final JobSchedulerConfiguration jobSchedulerConfiguration;
    private final ClientHttpCallHandler clientHttpCallHandler;

    @Inject
    public Scheduler(ClientService clientService,
                     TaskRepository taskRepository,
                     JobRepository jobRepository,
                     ObjectMapper objectMapper,
                     ScheduledExecutorService scheduledExecutorService,
                     ConnectionRegistry connectionRegistry,
                     ExceptionHandlingFactory exceptionHandlingFactory,
                     JobSchedulerConfiguration jobSchedulerConfiguration,
                     ClientHttpCallHandler clientHttpCallHandler) {
        this.clientService = clientService;
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.scheduledExecutorService = scheduledExecutorService;
        this.connectionRegistry = connectionRegistry;
        this.exceptionHandlingFactory = exceptionHandlingFactory;
        this.jobSchedulerConfiguration = jobSchedulerConfiguration;
        this.clientHttpCallHandler = clientHttpCallHandler;

    }

    @Override
    public void start() {
        log.info("Starting scheduler");
        List<Client> clients = clientService.getAll().stream()
                .filter(Client::isActive)
                .collect(Collectors.toList());
        List<ClientTaskExtractor> temp = clients.stream()
                .map(client -> new ClientTaskExtractor(
                        client,
                        taskRepository,
                        objectMapper,
                        scheduledExecutorService,
                        new ClientTaskActor(
                                client,
                                connectionRegistry,
                                objectMapper,
                                jobRepository,
                                taskRepository,
                                exceptionHandlingFactory,
                                jobSchedulerConfiguration,
                                clientHttpCallHandler),
                        jobSchedulerConfiguration.getWorkerScanConfig())
        ).collect(Collectors.toList());
        temp.forEach(clientTaskExtractor -> {
            try {
                clientTaskExtractor.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        clientTaskExtractorList.addAll(temp);
    }

    @Override
    public void stop() {
        clientTaskExtractorList.forEach(clientTaskExtractor -> {
            try {
                clientTaskExtractor.stop();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
