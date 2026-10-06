package io.github.mkonline08.sidebar;

import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import java.util.*;

record MarketSettings(long starter,int listingDays,int listingLimit,int totalLimit,int feePercent,
                      long minWager,long maxWager,int challengeSeconds,boolean reminders,int reminderSeconds,
                      List<Component> messages,boolean leaderboard,int leaderboardSeconds) {
    static MarketSettings read(YamlConfiguration y) {
        long starter=amount(y,"economy.starting-balance",500,true);
        long min=amount(y,"coinflip.minimum",1,false),max=amount(y,"coinflip.maximum",10000,false);
        if(max<min||max>Money.LIMIT/2)throw new IllegalArgumentException("Invalid coinflip wager range.");
        List<String> lines=y.getStringList("market-reminders.messages");
        if(lines.isEmpty()||lines.size()>20)throw new IllegalArgumentException("Provide 1–20 market reminder messages.");
        var mm=MiniMessage.builder().strict(true).build();
        return new MarketSettings(starter,integer(y,"auction.expire-days",7,1,30),integer(y,"auction.max-listings-per-player",5,1,100),
                integer(y,"auction.max-listings-total",5000,10,100000),integer(y,"auction.sale-fee-percent",0,0,25),min,max,
                integer(y,"coinflip.challenge-seconds",60,10,300),bool(y,"market-reminders.enabled",true),integer(y,"market-reminders.interval-seconds",120,30,86400),
                lines.stream().map(mm::deserialize).toList(),bool(y,"money-leaderboard.enabled",true),integer(y,"money-leaderboard.refresh-seconds",60,10,3600));
    }
    private static long amount(YamlConfiguration y,String key,long fallback,boolean zero){
        String s=String.valueOf(y.get(key,fallback));if(zero&&s.matches("0(\\.0{1,2})?"))return 0;return Money.parse(s);
    }
    private static int integer(YamlConfiguration y,String key,int fallback,int min,int max){
        if(y.contains(key)&&!y.isInt(key))throw new IllegalArgumentException(key+" must be an integer.");
        int value=y.getInt(key,fallback);if(value<min||value>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max+".");return value;
    }
    private static boolean bool(YamlConfiguration y,String key,boolean fallback){
        if(y.contains(key)&&!y.isBoolean(key))throw new IllegalArgumentException(key+" must be true or false.");return y.getBoolean(key,fallback);
    }
}
