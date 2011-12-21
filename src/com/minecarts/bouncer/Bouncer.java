package com.minecarts.bouncer;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.logging.Logger;

import com.minecarts.barrenschat.cache.CacheIgnore;
import com.minecarts.bouncer.helper.LoginStatus;
import com.minecarts.dbquery.DBQuery;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.event.*;
import org.bukkit.ChatColor;
import com.minecarts.bouncer.command.BouncerCommand;
import com.minecarts.bouncer.listener.*;
import com.minecarts.objectdata.ObjectData;
import com.minecarts.barrenschat.BarrensChat;

public class Bouncer extends org.bukkit.plugin.java.JavaPlugin{
    public final Logger log = Logger.getLogger("com.minecarts.bouncer");
    public DBQuery dbq;
    public ObjectData objectData;
    public BarrensChat barrensChat;

    public final static String fullMessage = ChatColor.GRAY + "Server is full. Please visit " + ChatColor.YELLOW + "Minecarts.com" + ChatColor.GRAY + " to get a guaranteed slot.";
    public final static String whitelistMissing = ChatColor.GRAY + "Please visit " + ChatColor.YELLOW + "Minecarts.com" + ChatColor.GRAY + " to add your name to our whitelist.";
    public final static String whitelistExpired = ChatColor.GRAY + "Your whitelist entry has expired. Please visit " + ChatColor.YELLOW + "Minecarts.com" + ChatColor.GRAY + " to reapply.";
    public final static String maintenance = ChatColor.GRAY + "Our admins are working on updating the server currently, please try again soon!";

    public final PlayerListener playerListener = new PlayerListener(this);

    private HashMap<String,LoginStatus> loginStatus = new HashMap<String, LoginStatus>();
    private HashMap<String, Integer> playerFlagged = new HashMap<String, Integer>();
    
    public void onEnable() {
        PluginManager pm = getServer().getPluginManager();
        PluginDescriptionFile pdf = getDescription();
        dbq = (DBQuery) pm.getPlugin("DBQuery");
        objectData = (ObjectData) pm.getPlugin("ObjectData");
        barrensChat = (BarrensChat) pm.getPlugin("BarrensChat");

        //Register our events
        pm.registerEvent(Event.Type.PLAYER_JOIN, this.playerListener, Event.Priority.Low, this);
        pm.registerEvent(Event.Type.PLAYER_LOGIN, this.playerListener, Event.Priority.Low, this);
        pm.registerEvent(Event.Type.PLAYER_QUIT, this.playerListener, Event.Priority.Low, this);
        pm.registerEvent(Event.Type.PLAYER_KICK, this.playerListener, Event.Priority.Low, this);

        //Register commands
        getCommand("bouncer").setExecutor(new BouncerCommand(this));

        log("[" + pdf.getName() + "] version " + pdf.getVersion() + " enabled.");
    }
    public void onDisable(){

    }


    public void doLogoutMessage(final Player player){
        new Query("SELECT value FROM `player_meta` WHERE `player` = ? AND `key` = ? LIMIT 1") {
            @Override
            public void onFetchOne(HashMap row) {
                String displayMessage = null;
                if(row == null || !player.hasPermission("subscriber")){
                    displayMessage = ChatColor.GRAY + player.getDisplayName() + ChatColor.GRAY + " logged out.";
                } else {
                    displayMessage =  MessageFormat.format("{0}" + row.get("value"),ChatColor.GRAY,player.getDisplayName());
                }

                if(displayMessage != null && !player.hasPermission("bouncer.stealth_mode")){
                    delayedOptionalMessage(displayMessage, player);
                }
            }
        }.fetchOne(player.getName(),"Bouncer_QuitMessage");
    }
    public void doLoginMessage(final Player player){
        new Query("SELECT value FROM `player_meta` WHERE `player` = ? AND `key`= ? LIMIT 1") {
            @Override
            public void onFetchOne(HashMap row) {
                String displayMessage = null;
                if(!player.hasPlayedBefore()){
                    displayMessage = ChatColor.WHITE + player.getDisplayName() + " has joined the server for the first time!";
                } else if(row == null || !player.hasPermission("subscriber")){
                    displayMessage = ChatColor.GRAY + player.getDisplayName() + ChatColor.GRAY + " logged in.";
                } else {
                    displayMessage =  MessageFormat.format("{0}" + row.get("value"),ChatColor.GRAY,player.getDisplayName());
                }

                //Check to see if it's a rejoin 
                if(playerFlagged.containsKey(player.getName())){
                    Integer taskId = playerFlagged.remove(player.getName());
                    displayMessage = null;
                    if(taskId != null){
                        Bukkit.getServer().getScheduler().cancelTask(taskId); //Cancel leave message from showing
                    }
                }

                if(displayMessage != null && !player.hasPermission("bouncer.stealth_mode")){
                    for(Player p : Bukkit.getServer().getOnlinePlayers()){
                        if(CacheIgnore.isIgnoring(p,player)) continue;
                        p.sendMessage(displayMessage);
                    }
                }
            }
        }.fetchOne(player.getName(), "Bouncer_JoinMessage");
    }
    
    public LoginStatus getLoginStatus(String playerName){
        return loginStatus.get(playerName);
    }
    public void setWhitelistStatus(String playerName, LoginStatus.WhitelistStatus status){
        if(loginStatus.containsKey(playerName)){
            loginStatus.get(playerName).whitelistStatus = status;
        } else {
            loginStatus.put(playerName,new LoginStatus(status));
        }
    }
    public void setBanStatus(String playerName, boolean status, String reason){
        if(loginStatus.containsKey(playerName)){
            loginStatus.get(playerName).isBanned = status;
            loginStatus.get(playerName).banReason= reason;
        } else {
            loginStatus.put(playerName,new LoginStatus(status,reason));
        }
    }
    
    
    public void doIdentifierCheck(String ip, final String playerName){
        new Query("SELECT reason FROM player_bans WHERE (identifier = ? OR identifier = ?) AND expireTime > NOW() LIMIT 1") {
            @Override
            public void onFetchOne(HashMap row) {
                if(row == null){
                    setBanStatus(playerName,false,null);
                    return; //They're not banned
                 }
                setBanStatus(playerName,true,(String)row.get("reason"));
            }
        }.sync().fetchOne(ip, playerName);
    }

    //TODO - whitelist support with IPs and stuff.
    public void doWhitelistCheck(String ip,final String playerName){
        new Query("SELECT (`expireTime` <= NOW()) AS expired FROM `whitelist` WHERE `player` = ? LIMIT 1") {
            @Override
            public void onFetchOne(HashMap row) {
                if(row == null){
                    setWhitelistStatus(playerName, LoginStatus.WhitelistStatus.NOT_ON_LIST);
                } else if ((Boolean) row.get("expired")){
                    setWhitelistStatus(playerName, LoginStatus.WhitelistStatus.EXPIRED);
                } else {
                    setWhitelistStatus(playerName, LoginStatus.WhitelistStatus.OK);
                }
            }
        }.sync().fetchOne(playerName);
    }

    private void delayedOptionalMessage(String message, Player player){
        Runnable delayedSend = new DelayedSend(message, player, this);
        int taskId = this.getServer().getScheduler().scheduleAsyncDelayedTask(this,delayedSend,20 * 15); //12 seconds later
        this.playerFlagged.put(player.getName(), taskId);
    }

    private class DelayedSend implements Runnable{
        private String message;
        private Player playerLeft;
        private Bouncer plugin;

        public DelayedSend(String message, Player playerLeft, Bouncer plugin){
            this.message = message;
            this.playerLeft = playerLeft;
            this.plugin = plugin;
        }

        public void run(){
            Integer taskId = plugin.playerFlagged.remove(playerLeft.getName());
            for(Player player : Bukkit.getServer().getOnlinePlayers()){
                if(CacheIgnore.isIgnoring(player, playerLeft)) continue;
                player.sendMessage(message);
            }
        }
    }

    class Query extends com.minecarts.dbquery.Query {
        public Query(String sql) {
            super(Bouncer.this, dbq.getProvider(getConfig().getString("db.provider")), sql);
        }

        @Override
        public void onComplete(FinalQuery query) {
            if(query.elapsed() > 500) {
                log(MessageFormat.format("Slow query took {0,number,#} ms", query.elapsed()));
            }
        }

        @Override
        public void onException(Exception x, FinalQuery query) {
            try { throw x; }
            catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void log(String msg){
        System.out.println("Bouncer> " + msg);
    }

}
