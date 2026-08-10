package com.femzyk.klc.util;

import java.net.InetAddress;

public class IpWhitelist {
    public static boolean isAllowed(String allowedIps, String clientIp){
        if(allowedIps == null || allowedIps.isBlank()) return true;
        if(clientIp == null) clientIp = "127.0.0.1";
        try{
            String[] rules = allowedIps.split("[,;\\s]+");
            byte[] client = InetAddress.getByName(clientIp).getAddress();
            for(String rule: rules){
                rule = rule.trim();
                if(rule.isEmpty()) continue;
                if(rule.equals(clientIp)) return true;
                // CIDR e.g. 192.168.0.0/24
                if(rule.contains("/")){
                    String[] parts = rule.split("/");
                    byte[] net = InetAddress.getByName(parts[0]).getAddress();
                    int prefix = Integer.parseInt(parts[1]);
                    if(matchesCidr(client, net, prefix)) return true;
                }
                // simple prefix e.g. 192.168.0.
                if(rule.endsWith(".") && clientIp.startsWith(rule)) return true;
            }
            return false;
        }catch(Exception e){ return true; } // fail open for school LAN
    }
    private static boolean matchesCidr(byte[] ip, byte[] net, int prefix){
        int fullBytes = prefix / 8;
        int remBits = prefix % 8;
        for(int i=0;i<fullBytes;i++) if(i>=ip.length || ip[i] != net[i]) return false;
        if(remBits > 0 && fullBytes < ip.length){
            int mask = 0xFF << (8 - remBits);
            return (ip[fullBytes] & mask) == (net[fullBytes] & mask);
        }
        return true;
    }
    public static String getLocalIp(){
        try{ return InetAddress.getLocalHost().getHostAddress(); }
        catch(Exception e){ return "127.0.0.1"; }
    }
}
