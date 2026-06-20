package mindustry.io;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;

import static mindustry.Vars.*;

/** Handles recovery when settings reads/writes fail. */
public class SettingsRecovery{
    private static final String[] heavySettingsKeys = {
        "delta-news-cache-v3",
        "delta-news-cache-time"
    };

    /** Call before {@link arc.Settings#load()}. */
    public static void prepare(){
        installErrorHandler();
    }

    public static void installErrorHandler(){
        Core.settings.setErrorHandler(SettingsRecovery::handleError);
    }

    static void handleError(Throwable error){
        Log.err(error);
        if(recover(error)){
            Log.info("Recovered settings storage: @", Core.settings.getDataDirectory());
            return;
        }
        Core.app.post(() -> {
            if(ui != null){
                Fi dir = Core.settings.getDataDirectory();
                ui.showErrorMessage("Failed to access local storage.\nSettings will not be saved.\nPath: " + dir.absolutePath());
            }
        });
    }

    /** @return whether recovery succeeded enough to continue. */
    public static boolean recover(Throwable error){
        if(tryStripHeavyKeys()) return true;
        return tryRestoreLatestBackup();
    }

    static boolean tryStripHeavyKeys(){
        if(Core.settings.getString("delta-news-cache-v3", "").isEmpty()) return false;

        for(String key : heavySettingsKeys){
            Core.settings.remove(key);
        }

        try{
            Core.settings.manualSave();
            return true;
        }catch(Throwable e){
            Log.err(e);
            return false;
        }
    }

    static boolean tryRestoreLatestBackup(){
        Fi dataDir = Core.settings.getDataDirectory();
        Seq<Fi> backups = new Seq<>();

        Fi legacy = dataDir.child("settings_backup.bin");
        if(legacy.exists()) backups.add(legacy);

        Fi backupFolder = dataDir.child("settings_backups");
        if(backupFolder.exists()){
            backups.addAll(backupFolder.findAll(f -> f.extension().equals("bin")));
        }

        if(backups.isEmpty()) return false;
        backups.sort(f -> -f.lastModified());

        Fi target = dataDir.child("settings.bin");
        for(Fi source : backups){
            try{
                source.copyTo(target);
                Core.settings.load();
                tryStripHeavyKeys();
                return true;
            }catch(Throwable e){
                Log.err(e);
            }
        }
        return false;
    }
}
