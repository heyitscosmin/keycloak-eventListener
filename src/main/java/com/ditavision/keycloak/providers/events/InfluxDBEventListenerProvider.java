package com.ditavision.keycloak.providers.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.influxdb.InfluxDB;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.jboss.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class InfluxDBEventListenerProvider implements EventListenerProvider {
	
	private static final Logger log = Logger.getLogger(InfluxDBEventListenerProvider.class);

    private final Set<EventType> excludedEvents;
    private final Set<OperationType> excludedAdminOperations;
    private final InfluxDB influxDB;
    private final String influxDBName;
    private final String influxDBRetention;


    public InfluxDBEventListenerProvider(Set<EventType> excludedEvents, Set<OperationType> excludedAdminOpearations, InfluxDB influxDB, String influxDBName, String influxDBRetention) {
        this.excludedEvents = excludedEvents;
        this.excludedAdminOperations = excludedAdminOpearations;
        this.influxDB = influxDB;
        this.influxDBName = influxDBName;
        this.influxDBRetention = influxDBRetention;
    }

    @Override
    public void onEvent(Event event) {
        // Ignore excluded events
        if (excludedEvents != null && excludedEvents.contains(event.getType())) {
            return;
        } else {
            toInfluxDB(event);
            fromInfluxDB(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Ignore excluded operations
        if (excludedAdminOperations != null && excludedAdminOperations.contains(event.getOperationType())) {
            return;
        } else {
            toInfluxDB(event);
        }
    }

    @Override
    public void close() {
    }

    private void toInfluxDB(Event event) {
        Point.Builder pb = Point.measurement("event").
                tag("type", event.getType().toString()).
                tag("realmId", event.getRealmId()).
                tag("clientId", event.getClientId() != null ? event.getClientId() : "unknown").
                addField("userId", event.getUserId() != null ? event.getUserId() : "unknown").
                addField("ipAddress", event.getIpAddress() != null ? event.getIpAddress() : "unknown").
                time(event.getTime(), TimeUnit.MILLISECONDS);
        if (event.getError() != null) {
            pb.addField("error", event.getError());
        }

        if (event.getDetails() != null) {
            String username = event.getDetails().get("username");
            if( username != null) {
                pb.addField("username", username);
            }

            //store details as json as probably not needed as separate fields
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                String json = objectMapper.writeValueAsString(event.getDetails());
                if (json != null && !"{}".equalsIgnoreCase(json)) {
                    pb.addField("details", json);
                }
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }

        influxDB.write(influxDBName, influxDBRetention, pb.build());
    }

    private void toInfluxDB(AdminEvent event) {
        Point.Builder pb = Point.measurement("adminEvent").
                tag("operationType", event.getOperationType().toString()).
                tag("resourceType", event.getResourceType() != null ? event.getResourceType().toString() : "unknown").
                tag("realmId", event.getRealmId()).
                tag("clientId", event.getAuthDetails().getClientId() != null ? event.getAuthDetails().getClientId() : "unknown").
                addField("userId", event.getAuthDetails().getUserId() != null ? event.getAuthDetails().getUserId() : "unknown").
                addField("ipAddress", event.getAuthDetails().getIpAddress() != null ? event.getAuthDetails().getIpAddress() : "unknown").
                addField("resourcePath", event.getResourcePath() != null ? event.getResourcePath() : "unknown").
                addField("representation", event.getRepresentation() != null ? event.getRepresentation() : "unknown").
                time(event.getTime(), TimeUnit.MILLISECONDS);
        if (event.getError() != null) {
            pb.addField("error", event.getError());
        }

        influxDB.write(influxDBName, influxDBRetention, pb.build());
    }
    private void fromInfluxDB(Event event){
        QueryResult queryResult = influxDB.query(new Query("select \"ipAddress\", \"userId\" from \"14d\".event where \"userId\" = '" + event.getUserId() + "'ORDER BY \"time\" DESC limit 3",influxDBName));
        JSONObject myJson = new JSONObject(queryResult);
        JSONArray responseLenght = myJson.getJSONArray("results").getJSONObject(0).getJSONArray("series").getJSONObject(0).getJSONArray("values");
        try{
  		  ArrayList<String> IpList = new ArrayList<String>();

  		  for(int i=0;i<responseLenght.length();i++){
	  		  String json_data = myJson.getJSONArray("results").getJSONObject(0).getJSONArray("series").
	  		  getJSONObject(0).getJSONArray("values").getJSONArray(i).getString(1);
	  		  IpList.add(json_data);
	  		  log.info(IpList.get(i)); log.info("\n");
	  		  ipToLocation(IpList.get(i));
  		  	}
  		  } catch(JSONException | IOException e){ log.info(e.toString()); }
        
        //log.info(queryResult);
    }

    private void ipToLocation(String ip) throws IOException{
        try {
            URL ipapi = new URL("https://ipapi.co/" + ip + "/city");

            URLConnection c = ipapi.openConnection();
            c.setRequestProperty("User-Agent", "java-ipapi-client");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream())
            );

            String line;
            while ((line = reader.readLine()) != null) {
                //System.out.println(line);
                log.info(line);
            }
            reader.close();
        } catch(IOException e){
            log.info("The provided IP is local." + e.toString());
        }
    }

}
