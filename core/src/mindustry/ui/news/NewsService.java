package mindustry.ui.news;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.struct.*;
import arc.util.*;
import arc.scene.style.*;
import arc.util.serialization.*;

import java.util.regex.*;

import static mindustry.Vars.*;

/** Fetches news from independent RSS feeds (Russian language). */
public class NewsService{
    private static final Json cacheJson = new Json();
    /** Legacy settings keys; kept only for one-time migration. */
    private static final String legacyCacheKey = "delta-news-cache-v3";
    private static final String legacyCacheTimeKey = "delta-news-cache-time";
    private static final String cacheFileName = "delta_news_cache.json";
    private static final long cacheDuration = 1000L * 60 * 30; //30 min
    private static final int listLimit = 24;
    private static final int previewLimit = 3;

    private static final String[] feedUrls = {
        "https://feeds.bbci.co.uk/russian/world/rss.xml",
        "https://meduza.io/rss/all",
        "https://habr.com/ru/rss/articles/"
    };

    private static final String[] feedSources = {"BBC", "Meduza", "Habr"};

    public final Seq<NewsItem> items = new Seq<>();
    public final ObjectMap<String, TextureRegionDrawable> images = new ObjectMap<>();
    public final ObjectMap<String, String> fullTexts = new ObjectMap<>();
    public final ObjectMap<String, String> fullImages = new ObjectMap<>();
    private final ObjectSet<String> loadingFull = new ObjectSet<>();

    public boolean loading;
    public boolean loaded;
    public @Nullable String lastError;

    private static Fi cacheFile(){
        return dataDirectory.child(cacheFileName);
    }

    public void loadCache(){
        items.clear();
        long cacheTime = 0L;
        boolean migratedLegacy = false;

        if(cacheFile().exists()){
            try{
                NewsCacheData cache = cacheJson.fromJson(NewsCacheData.class, cacheFile().readString());
                if(cache != null && cache.items != null){
                    cacheTime = cache.time;
                    for(StoredNews s : cache.items){
                        items.add(s.toItem());
                    }
                }
            }catch(Exception e){
                Log.err(e);
            }
        }

        String legacy = Core.settings.getString(legacyCacheKey, "");
        if(!legacy.isEmpty()){
            try{
                StoredNews[] stored = cacheJson.fromJson(StoredNews[].class, legacy);
                if(stored != null){
                    items.clear();
                    for(StoredNews s : stored){
                        items.add(s.toItem());
                    }
                    cacheTime = Core.settings.getLong(legacyCacheTimeKey, Time.millis());
                    migratedLegacy = true;
                }
            }catch(Exception e){
                Log.err(e);
            }
        }

        items.sort();
        loaded = !items.isEmpty();

        if(migratedLegacy){
            writeCacheFile(cacheTime);
            clearLegacySettingsCache();
        }
    }

    public void saveCache(){
        if(items.isEmpty()) return;
        writeCacheFile(Time.millis());
    }

    private void writeCacheFile(long time){
        int limit = Math.min(items.size, listLimit);
        NewsCacheData cache = new NewsCacheData();
        cache.time = time;
        cache.items = new StoredNews[limit];
        for(int i = 0; i < limit; i++){
            cache.items[i] = StoredNews.fromCache(items.get(i));
        }
        try{
            cacheFile().writeString(cacheJson.toJson(cache));
        }catch(Exception e){
            Log.err(e);
        }
    }

    private static void clearLegacySettingsCache(){
        if(Core.settings.getString(legacyCacheKey, "").isEmpty()) return;
        Core.settings.remove(legacyCacheKey);
        Core.settings.remove(legacyCacheTimeKey);
        try{
            Core.settings.manualSave();
        }catch(Throwable e){
            Log.err(e);
        }
    }

    public boolean shouldRefresh(){
        if(!loaded || items.isEmpty()) return true;
        long cacheTime = readCacheTime();
        return Time.millis() - cacheTime > cacheDuration;
    }

    private long readCacheTime(){
        if(cacheFile().exists()){
            try{
                NewsCacheData cache = cacheJson.fromJson(NewsCacheData.class, cacheFile().readString());
                if(cache != null) return cache.time;
            }catch(Exception ignored){}
        }
        return Core.settings.getLong(legacyCacheTimeKey, 0L);
    }

    public Seq<NewsItem> filteredItems(){
        Seq<NewsItem> out = new Seq<>();
        if(!NewsSettings.enabled()) return out;
        for(NewsItem item : items){
            if(NewsSettings.isSourceEnabled(item.source)) out.add(item);
        }
        return out;
    }

    public Seq<NewsItem> preview(){
        Seq<NewsItem> visible = filteredItems();
        Seq<NewsItem> out = new Seq<>();
        int count = Math.min(previewLimit, visible.size);
        for(int i = 0; i < count; i++){
            out.add(visible.get(i));
        }
        return out;
    }

    public void onSettingsChanged(){
        if(!NewsSettings.enabled() || !NewsSettings.anySourceEnabled()){
            loading = false;
            return;
        }
        if(shouldRefresh()){
            fetch(ok -> Core.app.post(() -> {
                if(ui != null && ui.newsfrag != null) ui.newsfrag.rebuildItems();
            }));
        }
    }

    public void fetch(Boolc done){
        if(loading){
            done.get(false);
            return;
        }
        if(!NewsSettings.enabled() || !NewsSettings.anySourceEnabled()){
            done.get(false);
            return;
        }
        loading = true;
        lastError = null;

        Seq<NewsItem> fetched = new Seq<>();
        int feeds = NewsSettings.enabledFeedCount();
        int[] remaining = {feeds};
        if(feeds == 0){
            loading = false;
            done.get(false);
            return;
        }

        for(int i = 0; i < feedUrls.length; i++){
            if(!NewsSettings.isSourceEnabled(i)) continue;
            final int index = i;
            Http.get(feedUrls[index])
            .timeout(20000)
            .error(e -> {
                synchronized(remaining){
                    remaining[0]--;
                    if(remaining[0] <= 0) completeFetch(fetched, done);
                }
            })
            .submit(res -> {
                try{
                    fetched.addAll(RssParser.parse(res.getResultAsString(), feedSources[index]));
                }catch(Exception e){
                    Log.err(e);
                }
                synchronized(remaining){
                    remaining[0]--;
                    if(remaining[0] <= 0) completeFetch(fetched, done);
                }
            });
        }
    }

    private void completeFetch(Seq<NewsItem> fetched, Boolc done){
        Core.app.post(() -> {
            loading = false;
            if(fetched.isEmpty()){
                lastError = "fetch";
                if(items.isEmpty()) loaded = false;
                done.get(false);
                return;
            }

            fetched.sort();
            ObjectSet<String> links = new ObjectSet<>();
            items.clear();
            for(NewsItem item : fetched){
                if(!NewsSettings.isSourceEnabled(item.source)) continue;
                if(item.link.isEmpty() || links.add(item.link)){
                    items.add(item);
                }
                if(items.size >= listLimit) break;
            }
            loaded = true;
            lastError = null;
            saveCache();
            done.get(true);
        });
    }

    public static String normalizeImageUrl(String url){
        if(url == null || url.isEmpty()) return "";
        if(url.startsWith("//")) url = "https:" + url;
        // Meduza /medium/ and /full/ return 403; /small/ works for thumbnails.
        if(url.contains("meduza.io/image/")){
            url = url.replace("/thumb/", "/small/").replace("/medium/", "/small/").replace("/full/", "/small/");
        }
        return url;
    }

    public void requestImage(String url, Runnable onLoaded){
        final String imageUrl = normalizeImageUrl(url);
        if(imageUrl.isEmpty()) return;
        if(images.containsKey(imageUrl)){
            onLoaded.run();
            return;
        }
        images.put(imageUrl, new TextureRegionDrawable(Core.atlas.find("nomap")));

        Http.get(imageUrl)
        .timeout(20000)
        .error(e -> Log.err("News image failed: @", imageUrl, e))
        .submit(res -> {
            try{
                Pixmap pix = new Pixmap(res.getResult());
                Core.app.post(() -> {
                    try{
                        Texture tex = new Texture(pix);
                        tex.setFilter(TextureFilter.linear);
                        images.put(imageUrl, new TextureRegionDrawable(new TextureRegion(tex)));
                        pix.dispose();
                        onLoaded.run();
                    }catch(Exception e){
                        Log.err(e);
                        pix.dispose();
                    }
                });
            }catch(Exception e){
                Log.err(e);
            }
        });
    }

    public String getMainImage(NewsItem item){
        if(item == null) return "";
        String cached = fullImages.get(item.link);
        if(cached != null && !cached.isEmpty()) return normalizeImageUrl(cached);
        return item.imageUrl == null ? "" : normalizeImageUrl(item.imageUrl);
    }

    public void requestFull(NewsItem item, Runnable onLoaded){
        if(item == null || item.link.isEmpty()) return;
        if(fullTexts.containsKey(item.link)){
            Core.app.post(onLoaded);
            return;
        }

        if(item.hasEmbeddedFull()){
            String image = item.imageUrl.isEmpty() ? "" : largerImageUrl(normalizeImageUrl(item.imageUrl));
            fullTexts.put(item.link, item.fullContent);
            if(!image.isEmpty()) fullImages.put(item.link, image);
            Core.app.post(onLoaded);
            return;
        }

        if(loadingFull.contains(item.link)) return;

        loadingFull.add(item.link);

        Http.get(item.link)
        .timeout(25000)
        .error(e -> Core.app.post(() -> {
            loadingFull.remove(item.link);
            storeFull(item, item.summary, item.imageUrl);
            onLoaded.run();
        }))
        .submit(res -> {
            try{
                ParsedArticle article = parseArticle(res.getResultAsString(), item);
                Core.app.post(() -> {
                    loadingFull.remove(item.link);
                    storeFull(item, article.text.isEmpty() ? item.summary : article.text, article.imageUrl);
                    onLoaded.run();
                });
            }catch(Exception e){
                Log.err(e);
                Core.app.post(() -> {
                    loadingFull.remove(item.link);
                    storeFull(item, item.summary, item.imageUrl);
                    onLoaded.run();
                });
            }
        });
    }

    private void storeFull(NewsItem item, String text, String imageUrl){
        fullTexts.put(item.link, text);
        imageUrl = normalizeImageUrl(imageUrl);
        if(!imageUrl.isEmpty()){
            fullImages.put(item.link, largerImageUrl(imageUrl));
        }
    }

    public boolean isFullLoading(NewsItem item){
        return item != null && loadingFull.contains(item.link);
    }

    public static String largerImageUrl(String url){
        if(url == null) return "";
        if(url.contains("meduza.io/image/")){
            return url
            .replace("/thumb/", "/large/")
            .replace("/small/", "/large/")
            .replace("/medium/", "/large/")
            .replace("/full/", "/large/");
        }
        return url
        .replace("/ace/ws/240/", "/ace/ws/800/")
        .replace("/ace/standard/240/", "/ace/standard/800/")
        .replace("width=240", "width=800");
    }

    private static ParsedArticle parseArticle(String html, NewsItem item){
        ParsedArticle out = new ParsedArticle();
        if(html == null || html.isEmpty()) return out;

        String cleanSource = stripNoise(html);
        String article = firstMatch(cleanSource, "(?is)<article[^>]*>(.*?)</article>");
        if(article.isEmpty()){
            article = firstMatch(cleanSource, "(?is)<main[^>]*>(.*?)</main>");
        }
        if(article.isEmpty()) article = cleanSource;

        out.imageUrl = pickMainImage(item.imageUrl,
        firstMatch(cleanSource, "(?is)<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        firstImageInHtml(article));

        String text = item.source.equals("BBC") ? parseBbcText(article) :
            item.source.equals("Habr") ? parseHabrText(article) : parseGenericText(article);
        if(text.isEmpty() || text.length() < item.summary.length()){
            text = parseGenericText(article);
        }

        if(text.isEmpty() || text.length() < item.summary.length()){
            String meta = cleanHtml(firstMatch(cleanSource, "(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']"));
            text = meta.isEmpty() ? item.summary : meta;
        }

        out.text = text;
        return out;
    }

    private static String parseBbcText(String article){
        StringBuilder out = new StringBuilder();
        Matcher p = Pattern.compile("(?is)<p([^>]*)>(.*?)</p>").matcher(article);
        while(p.find()){
            String attrs = p.group(1);
            String value = cleanHtml(p.group(2));
            if(value.startsWith("End of") || value.contains("Рекомендуем")) break;
            if(!(attrs.contains("dir=\"ltr\"") || attrs.contains("css-s4cjt0"))) continue;
            if(value.length() < 20 || shouldSkip(value)) continue;
            appendParagraph(out, value);
        }
        return out.toString();
    }

    private static String parseHabrText(String article){
        String block = firstMatch(article, "(?is)<motion\\.div[^>]*class=[\"'][^\"']*article-formatted-body[^\"']*[\"'][^>]*>(.*?)</motion\\.motion-div>");
        if(block.isEmpty()){
            block = firstMatch(article, "(?is)<div[^>]*article-formatted-body[^>]*>(.*?)</div>");
        }
        if(!block.isEmpty()){
            String text = parseGenericText(block);
            if(!text.isEmpty()) return text;
        }
        return parseGenericText(article);
    }

    private static String parseGenericText(String article){
        StringBuilder out = new StringBuilder();
        Matcher p = Pattern.compile("(?is)<(h2|p)[^>]*>(.*?)</\\1>").matcher(article);
        while(p.find()){
            String value = cleanHtml(p.group(2));
            if(shouldStop(value)) break;
            if(value.length() < 20 || shouldSkip(value)) continue;
            appendParagraph(out, value);
        }
        return out.toString();
    }

    private static String pickMainImage(String... urls){
        for(String url : urls){
            if(url != null && url.startsWith("http")) return largerImageUrl(url);
        }
        return "";
    }

    private static String firstImageInHtml(String html){
        if(html == null || html.isEmpty()) return "";
        Matcher matcher = Pattern.compile("(?is)(https?://[^\"'<> ]+\\.(?:jpg|jpeg|png|webp)(?:\\?[^\"'<> ]*)?)").matcher(html);
        if(matcher.find()) return largerImageUrl(cleanHtml(matcher.group(1)));
        return "";
    }

    private static String firstMatch(String text, String regex){
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String stripNoise(String text){
        return text.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
        .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
        .replaceAll("(?is)<svg[^>]*>.*?</svg>", " ")
        .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
    }

    private static String cleanHtml(String text){
        if(text == null) return "";
        text = stripNoise(text).replaceAll("(?is)<[^>]+>", " ");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static boolean shouldSkip(String text){
        return text.startsWith("http") || text.contains("Подписывайтесь") || text.contains("Подписаться") ||
        text.contains("JavaScript") || text.contains("cookie") || text.contains("Cookie") ||
        text.contains("Продолжительность") || text.startsWith("Видео") || text.contains("Смотреть") ||
        text.contains("Слушать") || text.contains("Перейти к содержанию") || text.contains("Главная") ||
        text.contains("Читайте также") || text.contains("LIVE Live") || text.matches(".*\\d+:\\d{2}.*Видео.*");
    }

    private static boolean shouldStop(String text){
        return text.startsWith("End of") || text.contains("Рекомендуем") || text.contains("Еще по теме") ||
        text.contains("Читайте также") || text.contains("Смотрите также") || text.contains("Похожие темы") ||
        text.contains("Похожие публикации") || text.contains("Читать комментарии") || text.startsWith("Хабр");
    }

    private static void appendParagraph(StringBuilder out, String value){
        if(out.indexOf(value) >= 0) return;
        if(out.length() > 0) out.append("\n\n");
        out.append(value);
    }

    public static class NewsCacheData{
        public long time;
        public StoredNews[] items;
    }

    public static class StoredNews{
        public String title, summary, fullContent, link, imageUrl, source;
        public long pubDate;

        static StoredNews fromCache(NewsItem item){
            StoredNews s = new StoredNews();
            s.title = item.title;
            s.summary = item.summary;
            s.link = item.link;
            s.imageUrl = item.imageUrl;
            s.source = item.source;
            s.pubDate = item.pubDate;
            return s;
        }

        NewsItem toItem(){
            return new NewsItem(title, summary, fullContent, link, imageUrl, source, pubDate);
        }
    }

    private static class ParsedArticle{
        String text = "";
        String imageUrl = "";
    }
}
