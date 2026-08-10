package com.femzyk.klc.util;

import com.femzyk.klc.db.DatabaseManager;
import com.google.gson.Gson;
import okhttp3.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Properties;

public class SmsService {
    private static String provider, apiKey;
    private static final OkHttpClient http = new OkHttpClient();
    private static final Gson gson = new Gson();
    static {
        try{
            Properties p = new Properties();
            try(var in = SmsService.class.getResourceAsStream("/config.properties")){ if(in!=null) p.load(in); }
            provider = p.getProperty("sms.provider","").toLowerCase();
            apiKey = p.getProperty("sms.api_key","");
        }catch(Exception ignored){}
    }
    public static boolean isEnabled(){ return apiKey!=null && !apiKey.isBlank(); }

    public static boolean sendSms(String toPhone, String message){
        if(!isEnabled()){
            queueSms(toPhone, message);
            return false;
        }
        try{
            if("termii".equals(provider)){
                // Termii Nigeria - https://termii.com
                MediaType JSON = MediaType.get("application/json");
                String json = gson.toJson(java.util.Map.of(
                    "to", toPhone,
                    "from", "KLC CBT",
                    "sms", message,
                    "type", "plain",
                    "channel", "generic",
                    "api_key", apiKey
                ));
                Request req = new Request.Builder()
                    .url("https://api.ng.termii.com/api/sms/send")
                    .post(RequestBody.create(json, JSON))
                    .build();
                try(Response resp = http.newCall(req).execute()){
                    return resp.isSuccessful();
                }
            }
            // Twilio fallback - add if needed
            return false;
        }catch(Exception e){ queueSms(toPhone, message); return false; }
    }

    private static void queueSms(String phone, String body){
        try(Connection c=DatabaseManager.getConnection();
            PreparedStatement ps=c.prepareStatement("INSERT INTO notification_queue(recipient_phone, body, channel, subject) VALUES(?,?,?,?)")){
            ps.setString(1, phone); ps.setString(2, body); ps.setString(3, "SMS"); ps.setString(4, "KLC CBT");
            ps.executeUpdate();
        }catch(Exception ignored){}
    }
}
