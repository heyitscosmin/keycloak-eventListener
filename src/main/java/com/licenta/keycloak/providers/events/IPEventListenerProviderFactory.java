package com.licenta.keycloak.providers.events;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.HashSet;
import java.util.Set;

public class IPEventListenerProviderFactory implements EventListenerProviderFactory {

    private Set<EventType> excludeEventsList;
    private Set<OperationType> excludeAdminOpList;
    private final String influxdbHost = getConnectionDetails("INFLUXDB_HOST", "localhost");
    private final String influxdbPort = getConnectionDetails("INFLUXDB_PORT", "8086");
    private final String influxdbUser = getConnectionDetails("INFLUXDB_USER", "root");
    private final String influxdbPassword = getConnectionDetails("INFLUXDB_PWD", "root");
    private final String nameInfluxdbDB = getConnectionDetails("INFLUXDB_DB", "keycloak");
    private final String retentionPolicyInfluxDB = getConnectionDetails("INFLUXDB_DB_RETENTION_POLICY", "14d");
    private final String influxUrl = String.format("http://%s:%s", influxdbHost, influxdbPort);
    private InfluxDB influxDB;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new IPEventListenerProvider(excludeEventsList, excludeAdminOpList, influxDB, nameInfluxdbDB, retentionPolicyInfluxDB, session);
    }

    @SuppressWarnings("deprecation")
	@Override
    public void init(Config.Scope config) {
        String[] excludes = config.getArray("exclude-events");
        if (excludes != null) {
            excludeEventsList = new HashSet<>();
            for (String e : excludes) {
                excludeEventsList.add(EventType.valueOf(e));
            }
        }
        
        String[] excludesOperations = config.getArray("excludesOperations");
        if (excludesOperations != null) {
            excludeAdminOpList = new HashSet<>();
            for (String e : excludesOperations) {
                excludeAdminOpList.add(OperationType.valueOf(e));
            }
        }

        try {
            influxDB = InfluxDBFactory.connect(influxUrl, influxdbUser, influxdbPassword);
            influxDB.createRetentionPolicy(retentionPolicyInfluxDB, nameInfluxdbDB, retentionPolicyInfluxDB, null, 1);
        } catch (Exception ex) {
            System.err.println("Ignoring InfluxDB setup, exception occurred: " + ex.getMessage());
        }
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
        if (influxDB != null) {
            influxDB.close();
        }
    }

    @Override
    public String getId() {
        return "IPEventListener";
    }

    private String getConnectionDetails(String envVariableName, String defaultEnvValue) {
        String result = System.getenv(envVariableName);
        return result == null ? defaultEnvValue : result;
    }
}
