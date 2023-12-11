package com.example.scheduler;

import com.example.db.hbase.TaskRepository;
import com.example.model.dto.Client;
import com.example.service.ClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.order.Order;

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
    private List<ClientTaskExtractor> clientTaskExtractorList = new ArrayList<>();
    private final ScheduledExecutorService scheduledExecutorService;

    @Inject
    public Scheduler(ClientService clientService,
                     TaskRepository taskRepository,
                     ObjectMapper objectMapper,
                     ScheduledExecutorService scheduledExecutorService) {
        this.clientService = clientService;
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.scheduledExecutorService = scheduledExecutorService;
    }

    @Override
    public void start() {
        log.info("Starting scheduler");
        List<Client> clients = clientService.getAll().stream()
                .filter(Client::isActive)
                .collect(Collectors.toList());
        List<ClientTaskExtractor> temp = clients.stream().map(client -> new ClientTaskExtractor(client,
                taskRepository, objectMapper, scheduledExecutorService)
        ).collect(Collectors.toList());
        temp.forEach(ClientTaskExtractor::start);
        clientTaskExtractorList.addAll(temp);
//        clientTaskExtractorList.forEach(clientTaskExtractor -> );
    }
}