package dev.felnull.ttsvoice.tts;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.felnull.fnjl.util.FNStringUtil;
import dev.felnull.ttsvoice.Main;
import dev.felnull.ttsvoice.audio.loader.VoiceLoaderManager;
import dev.felnull.ttsvoice.data.dictionary.Dictionary;
import dev.felnull.ttsvoice.data.dictionary.DictionaryManager;
import dev.felnull.ttsvoice.discord.BotLocation;
import dev.felnull.ttsvoice.util.DiscordUtils;
import dev.felnull.ttsvoice.voice.VoiceType;
import dev.felnull.ttsvoice.voice.reinoare.ReinoareManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class TTSCommands {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random rand = new Random();

    public static void join(SlashCommandInteractionEvent e) {
        var channel = e.getInteraction().getOption("channel");
        AudioChannel audioChannel;
        if (channel != null) {
            audioChannel = channel.getAsChannel().asAudioChannel();
        } else {
            audioChannel = e.getMember().getVoiceState().getChannel();
            if (audioChannel == null) {
                e.reply("VCに入ってる状態で使用するか、チャンネルを指定してください。").queue();
                return;
            }
        }

        var audioManager = e.getGuild().getAudioManager();
        if (audioManager.isConnected() && audioManager.getConnectedChannel() != null && audioManager.getConnectedChannel().getIdLong() == audioChannel.getIdLong()) {
            e.reply("すでに接続しています").queue();
            return;
        }

        try {
            audioManager.openAudioConnection(audioChannel);
        } catch (InsufficientPermissionException ex) {
            if (ex.getPermission() == Permission.VOICE_CONNECT) {
                e.reply(DiscordUtils.createChannelMention(audioChannel) + "に接続する権限がありません").setEphemeral(true).queue();
            } else {
                e.reply(DiscordUtils.createChannelMention(audioChannel) + "接続に失敗しました").setEphemeral(true).queue();
            }
            return;
        }
        if (Main.getServerSaveData(e.getGuild().getIdLong()).isJoinSayName())
            TTSListener.updateAuditLogMap(e.getGuild());
        TTSManager.getInstance().connect(BotLocation.of(e), e.getChannel().getIdLong(), audioChannel.getIdLong());

        e.reply(DiscordUtils.createChannelMention(audioChannel) + "に接続しました").queue();
    }

    public static void leave(SlashCommandInteractionEvent e) {
        var audioManager = e.getGuild().getAudioManager();
        if (audioManager.isConnected()) {
            var chn = audioManager.getConnectedChannel();
            e.reply(DiscordUtils.createChannelMention(chn) + "から切断します").queue();
            audioManager.closeAudioConnection();
            TTSManager.getInstance().disconnect(BotLocation.of(e));
        } else {
            e.reply("現在VCに接続していません").queue();
        }
    }

    public static void reconnect(SlashCommandInteractionEvent e) {
        var audioManager = e.getGuild().getAudioManager();
        if (audioManager.isConnected()) {
            var chn = audioManager.getConnectedChannel();
            e.reply(DiscordUtils.createChannelMention(chn) + "に再接続します").queue();
            audioManager.closeAudioConnection();
            TTSManager.getInstance().disconnect(BotLocation.of(e));
            var rt = new ReconnectThread(audioManager, e.getGuild(), chn, e.getChannel());
            rt.start();
        } else {
            e.reply("現在VCに接続していません").queue();
        }
    }

    private static class ReconnectThread extends Thread {
        private final AudioManager manager;
        private final Guild guild;
        private final AudioChannel channel;
        private final Channel textChannel;

        private ReconnectThread(AudioManager manager, Guild guild, AudioChannel channel, Channel textChannel) {
            this.manager = manager;
            this.guild = guild;
            this.channel = channel;
            this.textChannel = textChannel;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            this.manager.openAudioConnection(this.channel);
            TTSManager.getInstance().connect(BotLocation.of(guild.getJDA(), guild), textChannel.getIdLong(), channel.getIdLong());
        }
    }

    public static void voiceShow(SlashCommandInteractionEvent e) {
        var builder = new MessageCreateBuilder().addContent("読み上げ音声タイプ一覧\n");
        StringBuilder sb = new StringBuilder();
        for (VoiceType voiceType : TTSManager.getInstance().getVoiceTypes(e.getUser().getIdLong(), e.getGuild().getIdLong())) {
            sb.append(voiceType.getId()).append(" ").append(voiceType.getTitle()).append("\n");
        }
//        builder.appendCodeLine(sb.toString());
        builder.addContent("``" + sb + "``");
        e.reply(builder.build()).setEphemeral(true).queue();
    }

    public static void voiceChange(SlashCommandInteractionEvent e) {
        var uop = e.getOption("user");
        if (uop != null && !DiscordUtils.hasPermission(e.getMember())) {
            e.reply("他ユーザーを編集するための権限がありません").queue();
            return;
        }
        User user = uop == null ? e.getUser() : uop.getAsUser();

        var op = e.getOption("voice_type");
        String id = op == null ? null : op.getAsString();
        var type = TTSManager.getInstance().getVoiceTypeById(id, e.getUser().getIdLong(), e.getGuild().getIdLong());
        if (type == null) {
            e.reply("存在しない読み上げタイプです").queue();
            return;
        }
        var pre = TTSManager.getInstance().getUserVoiceType(user.getIdLong(), e.getGuild().getIdLong());
        if (pre == type) {
            e.reply("読み上げ音声タイプは変更されませんでした").queue();
            return;
        }

        TTSManager.getInstance().setUserVoceTypes(user.getIdLong(), type);
        e.reply(DiscordUtils.getName(BotLocation.of(e), user, user.getIdLong()) + "の読み上げ音声タイプを[" + type.getTitle() + "]に変更しました").queue();
    }

    public static void voiceCheck(SlashCommandInteractionEvent e) {
        var uop = e.getOption("user");
        User user = uop == null ? e.getUser() : uop.getAsUser();

        var type = TTSManager.getInstance().getUserVoiceType(user.getIdLong(), e.getGuild().getIdLong());
        e.reply(DiscordUtils.getName(BotLocation.of(e), user, user.getIdLong()) + "の現在の読み上げタイプは[" + type.getTitle() + "]です").setEphemeral(true).queue();
    }

    public static void denyShow(SlashCommandInteractionEvent e) {
        if (!DiscordUtils.hasPermission(e.getMember())) {
            e.reply("読み上げ拒否をされているユーザ一覧を見る権限がありません").setEphemeral(true).queue();
            return;
        }
        var lst = Main.getSaveData().getDenyUsers(e.getGuild().getIdLong());
        if (lst.isEmpty()) {
            e.reply("読み上げ拒否されたユーザは存在しません").setEphemeral(true).queue();
            return;
        }

        var msg = new MessageCreateBuilder().addContent("読み上げ拒否されたユーザ一覧\n");
        StringBuilder sb = new StringBuilder();
        for (Long deny : lst) {
            sb.append(DiscordUtils.getName(BotLocation.of(e), e.getJDA().getUserById(deny), deny)).append("\n");
        }
        msg.addContent("``" + sb + "``");
        //msg.appendCodeLine(sb.toString());
        e.reply(msg.build()).setEphemeral(true).queue();
    }

    public static void denyAdd(SlashCommandInteractionEvent e) {
        if (!checkNeedAdmin(e.getMember(), e)) return;
        if (!DiscordUtils.hasPermission(e.getMember())) {
            e.reply("ユーザを読み上げ拒否する権限がありません").setEphemeral(true).queue();
            return;
        }
        var uop = e.getOption("user");
        if (uop == null) {
            e.reply("ユーザを指定してください").setEphemeral(true).queue();
            return;
        }
        if (uop.getAsUser().isBot()) {
            e.reply(DiscordUtils.getName(BotLocation.of(e), uop.getAsUser(), uop.getAsUser().getIdLong()) + "はBOTです").setEphemeral(true).queue();
            return;
        }

        if (Main.getSaveData().isDenyUser(e.getGuild().getIdLong(), uop.getAsUser().getIdLong())) {
            e.reply("すでに読み上げ拒否をされているユーザです").setEphemeral(true).queue();
            return;
        }
        Main.getSaveData().addDenyUser(e.getGuild().getIdLong(), uop.getAsUser().getIdLong());
        e.reply(DiscordUtils.getName(BotLocation.of(e), uop.getAsUser(), uop.getAsUser().getIdLong()) + "の読み上げ拒否します").setEphemeral(true).queue();
    }

    public static void denyRemove(SlashCommandInteractionEvent e) {
        if (!checkNeedAdmin(e.getMember(), e)) return;
        if (!DiscordUtils.hasPermission(e.getMember())) {
            e.reply("ユーザの読み上げ拒否を解除する権限がありません").setEphemeral(true).queue();
            return;
        }
        var uop = e.getOption("user");
        if (uop == null) {
            e.reply("ユーザを指定してください").setEphemeral(true).queue();
            return;
        }
        if (uop.getAsUser().isBot()) {
            e.reply(DiscordUtils.getName(BotLocation.of(e), uop.getAsUser(), uop.getAsUser().getIdLong()) + "はBOTです").setEphemeral(true).queue();
            return;
        }
        if (!Main.getSaveData().isDenyUser(e.getGuild().getIdLong(), uop.getAsUser().getIdLong())) {
            e.reply("読み上げ拒否をされていないユーザです").setEphemeral(true).queue();
            return;
        }
        Main.getSaveData().removeDenyUser(e.getGuild().getIdLong(), uop.getAsUser().getIdLong());
        e.reply(DiscordUtils.getName(BotLocation.of(e), uop.getAsUser(), uop.getAsUser().getIdLong()) + "の読み上げ拒否を解除します").setEphemeral(true).queue();
    }

    public static void playReinoare(SlashCommandInteractionEvent e, ReinoareManager reinoareManager) {
        var op = e.getOption("search");
        if (op != null && TTSManager.getInstance().getVoiceTypes(e.getUser().getIdLong(), e.getGuild().getIdLong()).contains(reinoareManager.getVoice())) {
            TTSManager.getInstance().sayText(BotLocation.of(e), reinoareManager.getVoice(), op.getAsString());
        }
        e.deferReply().queue();
        e.getHook().deleteOriginal().queue();
    }

    public static void configShow(SlashCommandInteractionEvent e) {
        var sc = Main.getServerSaveData(e.getGuild().getIdLong());
        var msg = new MessageCreateBuilder().addContent("現在のコンフィグ\n");
        StringBuilder sbr = new StringBuilder();

        sbr.append("VCに参加時のみ読み上げ").append(" ").append(sc.isNeedJoin() ? "有効" : "無効").append("\n");
        sbr.append("読み上げの上書き").append(" ").append(sc.isOverwriteAloud() ? "有効" : "無効").append("\n");
        if (!DiscordUtils.isNonAllowInm(e.getGuild().getIdLong()))
            sbr.append("淫夢モード").append(" ").append(sc.isInmMode(e.getGuild().getIdLong()) ? "有効" : "無効").append("\n");
        if (!DiscordUtils.isNonAllowCookie(e.getGuild().getIdLong()))
            sbr.append("クッキー☆モード").append(" ").append(sc.isCookieMode(e.getGuild().getIdLong()) ? "有効" : "無効").append("\n");

        sbr.append("VCに参加時に名前を読み上げ").append(" ").append(sc.isJoinSayName() ? "有効" : "無効").append("\n");
        sbr.append("最大読み上げ文字数").append(" ").append(sc.getMaxReadAroundCharacterLimit()).append("文字").append("\n");
        sbr.append("最大名前読み上げ文字数").append(" ").append(sc.getMaxReadAroundNameLimit()).append("文字").append("\n");
        sbr.append("先頭につけると読み上げなくなる文字").append(" \"").append(sc.getNonReadingPrefix()).append("\"").append("\n");

//        msg.appendCodeLine(sbr.toString());
        msg.addContent("``" + sbr + "``");
        e.reply(msg.build()).setEphemeral(true).queue();
    }

    public static void configSet(SlashCommandInteractionEvent e, String sb) {
        var sc = Main.getServerSaveData(e.getGuild().getIdLong());
        if (!checkNeedAdmin(e.getMember(), e)) return;

        var en = e.getOption("enable");
        if (en == null) en = e.getOption("max-count");
        if (en == null) en = e.getOption("prefix");
        if (en == null) {
            e.reply("コンフィグ設定内容が未指定です").setEphemeral(true).queue();
            return;
        }
        if (en.getType() == OptionType.BOOLEAN) {
            boolean ena = en.getAsBoolean();
            String enStr = ena ? "有効" : "無効";
            switch (sb) {
                case "need-join" -> {
                    if (sc.isNeedJoin() == ena) {
                        e.reply("すでにVCに参加時のみ読み上げは" + enStr + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setNeedJoin(ena);
                    e.reply("VCに参加時のみ読み上げを" + enStr + "にしました").queue();
                }
                case "overwrite-aloud" -> {
                    if (sc.isOverwriteAloud() == ena) {
                        e.reply("すでに読み上げの上書きは" + enStr + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setOverwriteAloud(ena);
                    e.reply("読み上げの上書きを" + enStr + "にしました").queue();
                }
                case "inm-mode" -> {
                    if (ena && DiscordUtils.isNonAllowInm(e.getGuild().getIdLong())) {
                        if (rand.nextInt() == 0) {
                            e.reply("ｲﾔｰキツイッス（素）").queue();
                        } else {
                            e.reply("ダメみたいですね").queue();
                        }
                        return;
                    }
                    if (sc.isInmMode(e.getGuild().getIdLong()) == ena) {
                        e.reply("すでに淫夢モードは" + enStr + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setInmMode(ena);
                    Main.updateGuildCommand(e.getGuild(), true);
                    e.reply("淫夢モードを" + enStr + "にしました").queue();
                }
                case "cookie-mode" -> {
                    if (ena && DiscordUtils.isNonAllowCookie(e.getGuild().getIdLong())) {
                        if (rand.nextInt() == 0) {
                            e.reply("くぉら！").queue();
                        } else {
                            e.reply("お覚悟を。").queue();
                        }
                        return;
                    }
                    if (sc.isCookieMode(e.getGuild().getIdLong()) == ena) {
                        e.reply("すでにクッキー☆モードは" + enStr + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setCookieMode(ena);
                    Main.updateGuildCommand(e.getGuild(), true);
                    e.reply("クッキー☆モードを" + enStr + "にしました").queue();
                }
                case "join-say-name" -> {
                    if (sc.isJoinSayName() == ena) {
                        e.reply("すでにVCに参加時に名前を読み上げは" + enStr + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setJoinSayName(ena);
                    if (ena) {
                        TTSListener.updateAuditLogMap(e.getGuild());
                    }
                    e.reply("VCに参加時に名前を読み上げを" + enStr + "にしました").queue();
                }
            }
        } else if (en.getType() == OptionType.INTEGER) {
            int iv = en.getAsInt();
            switch (sb) {
                case "read-around-limit" -> {
                    if (sc.getMaxReadAroundCharacterLimit() == iv) {
                        e.reply("すでに最大読み上げ文字数は" + iv + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setMaxReadAroundCharacterLimit(iv);
                    e.reply("最大読み上げ文字数を" + iv + "にしました").queue();
                }
                case "read-around-name-limit" -> {
                    if (sc.getMaxReadAroundNameLimit() == iv) {
                        e.reply("すでに最大名前読み上げ文字数は" + iv + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setMaxReadAroundNameLimit(iv);
                    e.reply("最大名前読み上げ文字数を" + iv + "にしました").queue();
                }
            }
        } else if (en.getType() == OptionType.STRING) {
            String pre = en.getAsString();
            switch (sb) {
                case "non-reading-prefix" -> {
                    if (sc.getNonReadingPrefix().equals(pre)) {
                        e.reply("既に先頭につけると読み上げなくなる文字は" + DiscordUtils.mentionEscape(pre) + "です").setEphemeral(true).queue();
                        return;
                    }
                    sc.setNonReadingPrefix(pre);
                    e.reply("先頭につけると読み上げなくなる文字を" + DiscordUtils.mentionEscape(pre) + "に設定しました").queue();
                }
            }
        }
    }

    public static void vnick(SlashCommandInteractionEvent e) {
        var uop = e.getOption("user");
        if (uop != null && !DiscordUtils.hasPermission(e.getMember())) {
            e.reply("他ユーザーの読み上げユーザ名を変更するための権限がありません").queue();
            return;
        }
        User user = uop == null ? e.getUser() : uop.getAsUser();

        var nm = e.getOption("name");
        if (nm != null) {
            var name = nm.getAsString();
            if ("reset".equals(name)) {
                Main.getSaveData().removeUserNickName(user.getIdLong());
                e.reply(DiscordUtils.getName(BotLocation.of(e), user, user.getIdLong()) + "のニックネームをリセットしました").queue();
            } else {
                Main.getSaveData().setUserNickName(user.getIdLong(), name);
                e.reply(DiscordUtils.getName(BotLocation.of(e), user, user.getIdLong()) + "のニックネームを変更しました").queue();
            }
        }
    }

    private static boolean checkNeedAdmin(Member member, IReplyCallback callback) {
        /*if (!DiscordUtils.hasNeedAdminPermission(member)) {
            callback.reply("コマンドを実行する権限がありません").setEphemeral(true).queue();
            return false;
        }*/
        return true;
    }

    public static void dictShow(SlashCommandInteractionEvent e) {
        boolean global = false;
        var typeom = e.getOption("type");
        if (typeom != null && "global".equals(typeom.getAsString())) global = true;

        DictionaryManager dm = DictionaryManager.getInstance();
        EmbedBuilder eb = DiscordUtils.createEmbedBuilder();
        if (global) {
            eb.setTitle("グローバル辞書");
            setDictShowEmbed(eb, dm.getGlobalDictionaries());
        } else {
            var dic = dm.getGuildDictionary(e.getGuild().getIdLong());
            eb.setTitle("サーバー別辞書");
            setDictShowEmbed(eb, ImmutableList.of(dic));
        }
        e.reply(DiscordUtils.createEmbedMessage(eb.build())).queue();
    }

    private static void setDictShowEmbed(EmbedBuilder builder, List<Dictionary> dictionaries) {
        if (dictionaries.isEmpty()) {
            builder.setDescription("登録なし");
            return;
        }

        if (dictionaries.isEmpty() || dictionaries.stream().allMatch(n -> n.getEntryShowTexts().isEmpty())) {
            builder.setDescription("登録なし");
            return;
        }

        boolean single = dictionaries.size() == 1;
        AtomicInteger ct = new AtomicInteger();

        for (Dictionary dictionary : dictionaries) {
            var shows = dictionary.getEntryShowTexts();

            if (!single) {
                String name = dictionary.getName();
                if (dictionary.isBuildIn())
                    name += " [組み込み]";

                builder.addField(name, shows.isEmpty() ? "登録なし" : shows.size() + "種類", false);
                if (shows.isEmpty())
                    continue;
            }

            shows.forEach((word, read) -> {
                ct.getAndIncrement();
                addDictWordAndReadingField(builder, DiscordUtils.mentionEscape(word), DiscordUtils.mentionEscape(read));
            });
        }

        builder.setFooter("計" + ct.get() + "種類");
    }

    private static void addDictWordAndReadingField(EmbedBuilder builder, String word, String reading) {
        var world = "` " + word.replace("\n", "\\n") + " `";
        var reding = "```" + reading.replace("```", "\\```") + "```";
        builder.addField(world, reding, false);
    }

    public static void dictAdd(SlashCommandInteractionEvent e) {
        if (!checkNeedAdmin(e.getMember(), e)) return;
        var word = e.getOption("word");
        var reading = e.getOption("reading");
        if (word == null || reading == null) {
            e.reply("引数不足です").queue();
            return;
        }
        String worldStr = DiscordUtils.mentionEscape(word.getAsString());
        String readingStr = DiscordUtils.mentionEscape(reading.getAsString());

        if (worldStr.length() > 1000 || readingStr.length() > 1000) {
            e.reply("登録可能な最大文字数は1000文字です").queue();
            return;
        }

        DictionaryManager dm = DictionaryManager.getInstance();

        var dict = dm.getGuildDictionary(e.getGuild().getIdLong());
        boolean ex = dict.isExist(worldStr);
        dict.add(worldStr, readingStr);

        EmbedBuilder eb = DiscordUtils.createEmbedBuilder();
        eb.setTitle("登録された単語と読み");
        addDictWordAndReadingField(eb, worldStr, readingStr);

        var msg = new MessageCreateBuilder().addEmbeds(eb.build()).addContent(ex ? "以下の単語の読みを上書き登録しました" : "以下の単語の読みを登録しました").build();
        e.reply(msg).queue();
    }

    public static void dictRemove(SlashCommandInteractionEvent e) {
        if (!checkNeedAdmin(e.getMember(), e)) return;
        var word = e.getOption("word");
        if (word == null) {
            e.reply("引数不足です").queue();
            return;
        }
        String worldStr = DiscordUtils.mentionEscape(word.getAsString());

        DictionaryManager dm = DictionaryManager.getInstance();

        var gd = dm.getGuildDictionary(e.getGuild().getIdLong());

        if (gd.isExist(worldStr)) {
            var preReadingStr = gd.get(worldStr);
            gd.remove(worldStr);
            EmbedBuilder eb = DiscordUtils.createEmbedBuilder();
            eb.setTitle("削除された単語と読み");
            addDictWordAndReadingField(eb, worldStr, preReadingStr);

            var msg = new MessageCreateBuilder().addEmbeds(eb.build()).addContent("以下の単語を辞書から削除しました").build();
            e.reply(msg).queue();
        } else {
            e.reply("未登録の単語です").queue();
        }
    }

    public static void dictDownload(SlashCommandInteractionEvent e) {
        if (!checkNeedAdmin(e.getMember(), e)) return;

        DictionaryManager dm = DictionaryManager.getInstance();
        var gd = dm.getGuildDictionary(e.getGuild().getIdLong());
        var jo = gd.save();

        byte[] jobyte = GSON.toJson(jo).getBytes(StandardCharsets.UTF_8);

        e.replyFiles(FileUpload.fromData(jobyte, e.getGuild().getId() + "_dict.json")).queue();
    }

    public static void dictUpload(SlashCommandInteractionEvent e) {
        if (!checkNeedAdmin(e.getMember(), e)) return;

        var file = e.getOption("file");
        var overwrite = e.getOption("overwrite");
        if (file == null) {
            e.reply("引数不足です").queue();
            return;
        }
        DictionaryManager dm = DictionaryManager.getInstance();
        var gd = dm.getGuildDictionary(e.getGuild().getIdLong());

        boolean over = false;
        if (overwrite != null)
            over = overwrite.getAsBoolean();

        var attachment = file.getAsAttachment();

        JsonObject jo;
        try (InputStream stream = attachment.getProxy().download().get(); InputStream bufStream = new BufferedInputStream(stream); Reader reader = new InputStreamReader(bufStream, StandardCharsets.UTF_8)) {
            jo = GSON.fromJson(reader, JsonObject.class);
        } catch (IOException | ExecutionException | InterruptedException ex) {
            e.reply("アップロードされたファイルの取得に失敗").queue();
            return;
        } catch (JsonSyntaxException ex) {
            e.reply("アップロードされたファイルはJSONではありません").queue();
            return;
        }

        try {
            gd.load(jo, false, over);
        } catch (RuntimeException ex) {
            e.reply("辞書読み込みエラー: " + ex.getMessage()).queue();
            return;
        }

        e.reply("アップロードされたファイルから単語が登録されました").queue();
    }

    public static void infoAbout(SlashCommandInteractionEvent e) {
        var vstr = "v" + Main.VERSION;
        if (Main.isTest())
            vstr = "開発テスト";

        EmbedBuilder about = DiscordUtils.createEmbedBuilder();
        about.setTitle("I Discord TTS Voice BOT");
        about.setDescription(vstr);
        about.addField("License", "GNU LGPLv3", false);
        about.setFooter("Developed by FelNull", "https://avatars.githubusercontent.com/u/59995376?s=200&v=4");

        e.reply(DiscordUtils.createEmbedMessage(about.build())).addActionRow(Button.of(ButtonStyle.LINK, "https://github.com/TeamFelnull/IDiscordTTSVoice", "Source")).queue();
    }

    public static void infoOSS(SlashCommandInteractionEvent e) {
        EmbedBuilder oss = DiscordUtils.createEmbedBuilder();
        oss.setTitle("OSS Credit");

        oss.addField("VOICEVOX", "voicevox.hiroshiba.jp", false);
        oss.addField("COEIROINK", "coeiroink.com", false);
        oss.addField("SHAREVOX", "sharevox.app", false);

        oss.addField("VoiceTextWebAPI", "cloud.voicetext.jp", false);
        oss.addField("GoogleTTS", "cloud.google.com", false);

        var sc = Main.getServerSaveData(e.getGuild().getIdLong());
        if (sc.isInmMode(e.getGuild().getIdLong()) || sc.isCookieMode(e.getGuild().getIdLong()))
            oss.addField("淫ク☆ Sounds", "www.morimori0317.net/inc-sounds-search/ and inc-sounds.net", false);

        e.reply(DiscordUtils.createEmbedMessage(oss.build())).queue();
    }

    public static void infoEnvironment(SlashCommandInteractionEvent e) {
        EmbedBuilder env = DiscordUtils.createEmbedBuilder();
        env.setTitle("環境情報");
        env.addField("Java", System.getProperty("java.runtime.name") + " - " + System.getProperty("java.version"), false);
        env.addField("Java vm", System.getProperty("java.vm.name") + " - " + System.getProperty("java.vm.version"), false);
        env.addField("Server", System.getProperty("os.name") + "(" + System.getProperty("os.arch") + ") - " + Runtime.getRuntime().availableProcessors() + "Processors", false);

        e.reply(DiscordUtils.createEmbedMessage(env.build())).queue();
    }

    public static void infoWork(SlashCommandInteractionEvent e) {
        long tct = TTSManager.getInstance().getTTSCount();
        var file = VoiceLoaderManager.getInstance().getTmpFolder().listFiles();
        int fct = 0;
        if (file != null)
            fct = file.length;

        long kst = System.currentTimeMillis() - Main.START_TIME;

        EmbedBuilder work = DiscordUtils.createEmbedBuilder();
        work.setTitle("稼働情報");
        work.addField("参加サーバー数", e.getJDA().getGuilds().size() + "個", false);
        work.addField("読み上げチャンネル数", tct + "個", false);
        work.addField("TMPファイル数", fct + "個", false);
        work.addField("稼働時間", FNStringUtil.getTimeFormat(kst), false);

        e.reply(DiscordUtils.createEmbedMessage(work.build())).queue();
    }
}
