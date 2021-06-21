package com.licenta.keycloak.providers.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.influxdb.InfluxDB;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.keycloak.email.DefaultEmailSenderProvider;
import org.keycloak.email.EmailException;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.UserModel;
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

public class IPEventListenerProvider implements EventListenerProvider {

    private final InfluxDB influxDB;
    private KeycloakSession session = null;
    private final RealmProvider model;
    private final String nameInfluxDB;
    private final String retentionPolicyInfluxDB;
    private final Set<EventType> excludeEventsList;
    private final Set<OperationType> excludeAdminOpList;
    private final ArrayList<String> locationList = new ArrayList<String>();
    private static final Logger log = Logger.getLogger(IPEventListenerProvider.class);

    public IPEventListenerProvider(Set<EventType> excludeEventsList, Set<OperationType> excludeAdminOpList, InfluxDB influxDB, String nameInfluxDB, String retentionPolicyInfluxDB, KeycloakSession session) {
        this.influxDB = influxDB;
        this.session = session;
        this.model = session.realms();
        this.nameInfluxDB = nameInfluxDB;
        this.retentionPolicyInfluxDB = retentionPolicyInfluxDB;
        this.excludeEventsList = excludeEventsList;
        this.excludeAdminOpList = excludeAdminOpList;
    }

    @Override
    public void onEvent(Event event) {
        if (excludeEventsList != null && excludeEventsList.contains(event.getType())) {
            return;
        } else if(event.getType().toString()=="LOGIN"){
            try {
				insertDataIntoInfluxDB(event);
			} catch (IOException e) {
				e.printStackTrace();
			}
            checkUserLocation(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (excludeAdminOpList != null && excludeAdminOpList.contains(event.getOperationType())) {
            return;
        } else {
            insertDataIntoInfluxDB(event);
        }
    }

    @Override
    public void close() {
    }

    private void insertDataIntoInfluxDB(Event event) throws IOException {
        Point.Builder pb = Point.measurement("event").
                tag("type", event.getType().toString()).
                tag("realmId", event.getRealmId()).
                tag("clientId", event.getClientId() != null ? event.getClientId() : "unknown").
                addField("userId", event.getUserId() != null ? event.getUserId() : "unknown").
                addField("ipAddress", event.getIpAddress() != null ? event.getIpAddress() : "unknown").
                addField("location", ipToLocation(event.getIpAddress().toString()) != null ? event.getIpAddress() : "unknown/localhost").
                time(event.getTime(), TimeUnit.MILLISECONDS);
        if (event.getError() != null) {
            pb.addField("error", event.getError());
        }

        if (event.getDetails() != null) {
            String username = event.getDetails().get("username");
            if( username != null) {
                pb.addField("username", username);
            }

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
        influxDB.write(nameInfluxDB, retentionPolicyInfluxDB, pb.build());
    }

    private void insertDataIntoInfluxDB(AdminEvent event) {
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
        influxDB.write(nameInfluxDB, retentionPolicyInfluxDB, pb.build());
    }
    
    private void checkUserLocation(Event event){
        QueryResult queryResult = influxDB.query(new Query("select \"ipAddress\", \"userId\", \"location\" from \"14d\".event where \"userId\" = '" + event.getUserId() + "'ORDER BY \"time\" DESC limit 3", nameInfluxDB));
        JSONObject myJson = new JSONObject(queryResult);
        JSONArray responseLenght = myJson.getJSONArray("results").getJSONObject(0).getJSONArray("series").getJSONObject(0).getJSONArray("values");
        try{
  		  for(int i = 0; i < responseLenght.length(); i++){
  			  String json_data = myJson.getJSONArray("results").
	  				  getJSONObject(0).
	  				  getJSONArray("series").
	  				  getJSONObject(0).
			  		  getJSONArray("values").
			  		  getJSONArray(i).
			  		  getString(3);
	  		  locationList.add(json_data);
	  		  log.info(locationList.get(i));log.info("\n");
  		  	}
  		  } catch(JSONException e){ log.info("error in fromInfluxDB"+e.toString()); }
        if(responseLenght.length() == 1){
            log.info("only one authentication");
        }
        else if(locationList.get(0).toString().equalsIgnoreCase(locationList.get(1).toString()) == false) { //condition to send email when 1st location =/= 2nd location
        	sendEmail(event);
        }
    }

    private String ipToLocation(String ip) throws IOException{
    	
        try {
            URL ipapi = new URL("https://ipapi.co/" + ip + "/city");
            URLConnection c = ipapi.openConnection();
            c.setRequestProperty("Content-Type", "java-ipapi-client");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream())
            );
            String line;
            while ((line = reader.readLine()) != null) {
                log.info(line);
            }
            reader.close();
        } catch(IOException e){
            log.info("The provided IP is local or API error" + e.toString());
        }
		return null;
   }
    
    private void sendEmail(Event event) {
        RealmModel realmModel = this.model.getRealm(event.getRealmId());
        UserModel userModel = this.session.users().getUserById(event.getUserId(), realmModel);

    	String contentForEmail = "Unrecognized IP location\n\n" +
                "Email: " + userModel.getEmail() + "\n" +
                "Username: " + userModel.getUsername() + "\n" +
                "Client: " + event.getClientId();

        String htmlContentForEmail = "<h1>Hello, " + userModel.getUsername() + "</h1>"  +
                "<br>" +
                "<p>This is an automated message to notify you that we detected a login attempt with a valid password to your account from an unrecognized location.</p>" +
                "<br>" +
                "<p>Location of login: " + locationList.get(0).toString() + "</p>" +
                "<p>IP Address logged in: " + event.getIpAddress() + "</p>" +

                "<br>";
    	
    	if (userModel != null && userModel.getEmail() != null) {
    		DefaultEmailSenderProvider defaultEmailSenderProvider = new DefaultEmailSenderProvider(session);
    		try {
    			defaultEmailSenderProvider.send(session.getContext().getRealm().getSmtpConfig(), userModel,
    					"Suspicious sign in detected", 
    					contentForEmail,
    					htmlContentForEmail);
    		} catch (EmailException e) {
    			e.printStackTrace();	
    		}
    	}
    }
}
