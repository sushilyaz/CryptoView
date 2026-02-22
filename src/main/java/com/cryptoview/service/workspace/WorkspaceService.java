package com.cryptoview.service.workspace;

import com.cryptoview.model.config.Workspace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private static final String STORAGE_FILE = "workspaces.json";

    private final ObjectMapper objectMapper;

    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private volatile String activeWorkspaceId;

    @PostConstruct
    public void init() {
        loadFromFile();
        if (workspaces.isEmpty()) {
            Workspace defaultWs = Workspace.createDefault();
            workspaces.put(defaultWs.getId(), defaultWs);
            activeWorkspaceId = defaultWs.getId();
            saveToFile();
            log.info("Created default workspace: {}", defaultWs.getId());
        } else {
            activeWorkspaceId = workspaces.values().stream()
                    .filter(Workspace::isActive)
                    .map(Workspace::getId)
                    .findFirst()
                    .orElse(workspaces.keySet().iterator().next());
            log.info("Loaded {} workspaces, active: {}", workspaces.size(), activeWorkspaceId);
        }
    }

    public List<Workspace> getAllWorkspaces() {
        return new ArrayList<>(workspaces.values());
    }

    public Optional<Workspace> getWorkspace(String id) {
        return Optional.ofNullable(workspaces.get(id));
    }

    public Workspace getActiveWorkspace() {
        return workspaces.get(activeWorkspaceId);
    }

    public String getActiveWorkspaceId() {
        return activeWorkspaceId;
    }

    public Workspace createWorkspace(String name) {
        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID().toString());
        ws.setName(name);
        ws.setActive(false);
        workspaces.put(ws.getId(), ws);
        saveToFile();
        log.info("Created workspace: {} ({})", name, ws.getId());
        return ws;
    }

    public Optional<Workspace> updateWorkspace(String id, Workspace updated) {
        Workspace existing = workspaces.get(id);
        if (existing == null) {
            return Optional.empty();
        }

        existing.setName(updated.getName());
        existing.setEnabledMarkets(updated.getEnabledMarkets());
        existing.setMinDensityOverrides(updated.getMinDensityOverrides());
        existing.setSymbolMinDensityOverrides(updated.getSymbolMinDensityOverrides());
        existing.setBlacklistedSymbols(updated.getBlacklistedSymbols());
        existing.setSymbolComments(updated.getSymbolComments());
        existing.setSortType(updated.getSortType());

        saveToFile();
        return Optional.of(existing);
    }

    public boolean deleteWorkspace(String id) {
        if (id.equals(activeWorkspaceId)) {
            return false;
        }
        Workspace removed = workspaces.remove(id);
        if (removed != null) {
            saveToFile();
            log.info("Deleted workspace: {} ({})", removed.getName(), id);
            return true;
        }
        return false;
    }

    public Optional<Workspace> activateWorkspace(String id) {
        Workspace ws = workspaces.get(id);
        if (ws == null) {
            return Optional.empty();
        }

        workspaces.values().forEach(w -> w.setActive(false));
        ws.setActive(true);
        activeWorkspaceId = id;
        saveToFile();
        log.info("Activated workspace: {} ({})", ws.getName(), id);
        return Optional.of(ws);
    }

    public Optional<Workspace> addToBlacklist(String workspaceId, String symbol) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Optional.empty();
        ws.getBlacklistedSymbols().add(symbol.toUpperCase());
        saveToFile();
        return Optional.of(ws);
    }

    public Optional<Workspace> removeFromBlacklist(String workspaceId, String symbol) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Optional.empty();
        ws.getBlacklistedSymbols().remove(symbol.toUpperCase());
        saveToFile();
        return Optional.of(ws);
    }

    public Optional<Workspace> setSymbolComment(String workspaceId, String symbol, String comment) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Optional.empty();
        ws.getSymbolComments().put(symbol.toUpperCase(), comment);
        saveToFile();
        return Optional.of(ws);
    }

    public Optional<Workspace> removeSymbolComment(String workspaceId, String symbol) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Optional.empty();
        ws.getSymbolComments().remove(symbol.toUpperCase());
        saveToFile();
        return Optional.of(ws);
    }

    public Optional<Workspace> setSymbolMinDensity(String workspaceId, String symbol, java.math.BigDecimal minDensity) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Optional.empty();
        ws.getSymbolMinDensityOverrides().put(symbol.toUpperCase(), minDensity);
        saveToFile();
        return Optional.of(ws);
    }

    public Optional<Workspace> removeSymbolMinDensity(String workspaceId, String symbol) {
        Workspace ws = workspaces.get(workspaceId);
        if (ws == null) return Optional.empty();
        ws.getSymbolMinDensityOverrides().remove(symbol.toUpperCase());
        saveToFile();
        return Optional.of(ws);
    }

    private void loadFromFile() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) return;

        try {
            List<Workspace> loaded = objectMapper.readValue(file, new TypeReference<>() {});
            loaded.forEach(ws -> workspaces.put(ws.getId(), ws));
            log.info("Loaded {} workspaces from {}", loaded.size(), STORAGE_FILE);
        } catch (IOException e) {
            log.warn("Failed to load workspaces from {}: {}", STORAGE_FILE, e.getMessage());
        }
    }

    private void saveToFile() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(STORAGE_FILE), new ArrayList<>(workspaces.values()));
        } catch (IOException e) {
            log.error("Failed to save workspaces to {}", STORAGE_FILE, e);
        }
    }
}
