package dev.felnull.ttsvoice;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

public record Config(String botToken, String voiceVoxURL, String voiceTextAPIKey, int cashTime, String ignoreRegex,
                     List<Long> overwriteAloudServers, List<Long> inmAllowServers, List<Long> inmDenyUser,
                     List<Long> adminRoles, List<Long> needAdminServers) {

    public static Config of(JsonObject jo) {
        ImmutableList.Builder<Long> inmAllowBuilder = new ImmutableList.Builder<>();
        var iabja = jo.getAsJsonArray("InmAllowServers");
        for (JsonElement entry : iabja) {
            inmAllowBuilder.add(entry.getAsLong());
        }
        ImmutableList.Builder<Long> inmDenyBuilder = new ImmutableList.Builder<>();
        var idbja = jo.getAsJsonArray("InmDenyUser");
        for (JsonElement entry : idbja) {
            inmDenyBuilder.add(entry.getAsLong());
        }
        ImmutableList.Builder<Long> adminRolesBuilder = new ImmutableList.Builder<>();
        var arja = jo.getAsJsonArray("AdminRoles");
        for (JsonElement entry : arja) {
            adminRolesBuilder.add(entry.getAsLong());
        }

        ImmutableList.Builder<Long> needAdminServersBuilder = new ImmutableList.Builder<>();
        var naja = jo.getAsJsonArray("NeedAdminServers");
        for (JsonElement entry : naja) {
            needAdminServersBuilder.add(entry.getAsLong());
        }

        ImmutableList.Builder<Long> overwriteAloudServersBuilder = new ImmutableList.Builder<>();
        var oasja = jo.getAsJsonArray("OverwriteAloudServers");
        for (JsonElement entry : oasja) {
            overwriteAloudServersBuilder.add(entry.getAsLong());
        }

        return new Config(jo.get("BotToken").getAsString(), jo.get("VoiceVoxURL").getAsString(), jo.get("VoiceTextAPIKey").getAsString(), jo.get("CashTime").getAsInt(), jo.get("IgnoreRegex").getAsString(), overwriteAloudServersBuilder.build(), inmAllowBuilder.build(), inmDenyBuilder.build(), adminRolesBuilder.build(), needAdminServersBuilder.build());
    }

    public static Config createDefault() {
        return new Config("", "http://localhost:50021", "", 3, "(!|/|\\$|`).*", ImmutableList.of(600929948529590272L), ImmutableList.of(600929948529590272L, 436404936151007241L, 949532003093577739L), ImmutableList.of(), ImmutableList.of(939945132046827550L, 601000603354660864L), ImmutableList.of(930083398691733565L));
    }

    public void check() {
        if (botToken.isEmpty())
            throw new IllegalStateException("Bot token is empty");
        if (voiceVoxURL.isEmpty())
            throw new IllegalStateException("VoiceVox url is empty");
        if (voiceTextAPIKey.isEmpty())
            throw new IllegalStateException("VoiceText api key is empty");
        if (cashTime < 0)
            throw new IllegalStateException("Cash time must be greater than or equal to 0");
    }

    public JsonObject toJson() {
        var jo = new JsonObject();
        jo.addProperty("BotToken", botToken);
        jo.addProperty("VoiceVoxURL", voiceVoxURL);
        jo.addProperty("VoiceTextAPIKey", voiceTextAPIKey);
        jo.addProperty("CashTime", cashTime);
        jo.addProperty("IgnoreRegex", ignoreRegex);
        var imja = new JsonArray();
        for (Long allow : inmAllowServers) {
            imja.add(allow);
        }
        jo.add("InmAllowServers", imja);
        var idja = new JsonArray();
        for (Long entry : inmDenyUser) {
            idja.add(entry);
        }
        jo.add("InmDenyUser", idja);
        var arja = new JsonArray();
        for (Long entry : adminRoles) {
            arja.add(entry);
        }
        jo.add("AdminRoles", arja);

        var naja = new JsonArray();
        for (Long entry : needAdminServers) {
            naja.add(entry);
        }
        jo.add("NeedAdminServers", naja);

        var oasja = new JsonArray();
        for (Long entry : overwriteAloudServers) {
            oasja.add(entry);
        }
        jo.add("OverwriteAloudServers", oasja);

        return jo;
    }
}
