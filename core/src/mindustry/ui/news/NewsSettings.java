package mindustry.ui.news;

import arc.*;

/** Player preferences for the main-menu news widget. */
public final class NewsSettings{
    public static final String enabled = "news-enabled";

    public static final String[] sourceKeys = {
        "news-source-bbc",
        "news-source-meduza",
        "news-source-habr"
    };

    public static final String[] sourceNames = {"BBC", "Meduza", "Habr"};

    private NewsSettings(){}

    public static boolean enabled(){
        return Core.settings.getBool(enabled, true);
    }

    public static boolean isSourceEnabled(int index){
        if(!enabled() || index < 0 || index >= sourceKeys.length) return false;
        return Core.settings.getBool(sourceKeys[index], true);
    }

    public static boolean isSourceEnabled(String source){
        if(!enabled() || source == null) return false;
        for(int i = 0; i < sourceNames.length; i++){
            if(sourceNames[i].equalsIgnoreCase(source)) return Core.settings.getBool(sourceKeys[i], true);
        }
        return false;
    }

    public static boolean anySourceEnabled(){
        if(!enabled()) return false;
        for(int i = 0; i < sourceKeys.length; i++){
            if(Core.settings.getBool(sourceKeys[i], true)) return true;
        }
        return false;
    }

    public static int enabledFeedCount(){
        if(!enabled()) return 0;
        int n = 0;
        for(int i = 0; i < sourceKeys.length; i++){
            if(Core.settings.getBool(sourceKeys[i], true)) n++;
        }
        return n;
    }

    /** Comma-separated list of enabled sources for the panel subtitle. */
    public static String sourcesHint(){
        if(!enabled()) return "";
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < sourceNames.length; i++){
            if(!Core.settings.getBool(sourceKeys[i], true)) continue;
            if(out.length() > 0) out.append(" · ");
            out.append(sourceNames[i]);
        }
        return out.toString();
    }
}
