package mindustry.ui.news;

import arc.struct.*;
import arc.util.*;

import java.text.*;
import java.util.regex.*;
import java.util.*;

/** Minimal RSS 2.0 parser for BBC / Meduza feeds. */
public class RssParser{
    private static final DateFormat[] dateFormats = {
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US),
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    };

    static{
        for(DateFormat f : dateFormats){
            f.setLenient(true);
        }
    }

    public static Seq<NewsItem> parse(String xml, String source){
        Seq<NewsItem> items = new Seq<>();
        if(xml == null || xml.isEmpty()) return items;

        int searchFrom = 0;
        while(true){
            int start = indexOfItem(xml, searchFrom);
            if(start < 0) break;
            int end = xml.indexOf("</item>", start);
            if(end < 0) break;
            String block = xml.substring(start, end);
            searchFrom = end + 7;

            String title = clean(extractTag(block, "title"));
            String descriptionRaw = extractTag(block, "description");
            String description = clean(descriptionRaw);
            String encoded = extractTag(block, "content:encoded");
            String fullContent = encoded.isEmpty() ? "" : htmlToArticleText(encoded);
            String link = clean(extractLink(block));
            String image = extractImage(block);
            if(image.isEmpty()){
                String html = encoded.isEmpty() ? descriptionRaw : encoded;
                if(!html.isEmpty()){
                    Matcher img = Pattern.compile("(?is)<img[^>]+src=[\"']([^\"']+)[\"']").matcher(html);
                    if(img.find()) image = NewsService.normalizeImageUrl(img.group(1));
                }
            }
            image = NewsService.normalizeImageUrl(image);
            long date = parseDate(extractTag(block, "pubDate"));

            if(title.isEmpty()) continue;

            if(description.isEmpty()){
                description = fullContent.isEmpty() ? title : previewFromFull(fullContent, 280);
            }

            items.add(new NewsItem(title, description, fullContent, link, image, source, date));
        }
        return items;
    }

    private static String previewFromFull(String full, int max){
        if(full.length() <= max) return full;
        int cut = full.lastIndexOf(' ', max);
        if(cut < max / 2) cut = max;
        return full.substring(0, cut).trim() + "...";
    }

    private static int indexOfItem(String xml, int from){
        int a = xml.indexOf("<item>", from);
        int b = xml.indexOf("<item ", from);
        if(a < 0) return b;
        if(b < 0) return a;
        return Math.min(a, b);
    }

    private static String extractTag(String block, String tag){
        String open = "<" + tag;
        int idx = block.indexOf(open);
        if(idx < 0) return "";
        int contentStart = block.indexOf('>', idx);
        if(contentStart < 0) return "";
        contentStart++;

        if(block.regionMatches(true, contentStart, "<![CDATA[", 0, 9)){
            contentStart += 9;
            int cdataEnd = block.indexOf("]]>", contentStart);
            if(cdataEnd < 0) return "";
            return block.substring(contentStart, cdataEnd);
        }

        int close = block.indexOf("</" + tag + ">", contentStart);
        if(close < 0) return "";
        return block.substring(contentStart, close);
    }

    private static String extractLink(String block){
        String link = extractTag(block, "link");
        if(!link.isEmpty()) return link;

        int atom = block.indexOf("<atom:link");
        if(atom >= 0){
            int href = block.indexOf("href=\"", atom);
            if(href >= 0){
                href += 6;
                int end = block.indexOf('"', href);
                if(end > href) return block.substring(href, end);
            }
        }
        return "";
    }

    private static String extractImage(String block){
        String[] patterns = {"url=\"", "url='"};
        for(String tag : new String[]{"media:thumbnail", "media:content", "enclosure"}){
            int idx = 0;
            while((idx = block.indexOf("<" + tag, idx)) >= 0){
                for(String p : patterns){
                    int urlIdx = block.indexOf(p, idx);
                    if(urlIdx < 0 || urlIdx > idx + tag.length() + 30) continue;
                    urlIdx += p.length();
                    char quote = p.charAt(p.length() - 1);
                    int end = block.indexOf(quote, urlIdx);
                    if(end > urlIdx){
                        String url = block.substring(urlIdx, end);
                        if(url.startsWith("http")) return url;
                    }
                }
                idx++;
            }
        }
        return "";
    }

    private static long parseDate(String raw){
        if(raw == null || raw.isEmpty()) return 0L;
        String s = clean(raw);
        for(DateFormat f : dateFormats){
            try{
                Date d = f.parse(s);
                if(d != null) return d.getTime();
            }catch(ParseException ignored){}
        }
        return 0L;
    }

    private static String htmlToArticleText(String html){
        if(html == null || html.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        Matcher p = Pattern.compile("(?is)<p[^>]*>(.*?)</p>").matcher(html);
        while(p.find()){
            String value = cleanHtml(p.group(1));
            if(value.length() < 15) continue;
            if(out.length() > 0) out.append("\n\n");
            out.append(value);
        }
        if(out.length() > 0) return out.toString();
        return cleanHtml(html);
    }

    private static String cleanHtml(String text){
        if(text == null) return "";
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
        .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
        .replaceAll("(?is)<[^>]+>", " ");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String clean(String text){
        if(text == null) return "";
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        text = text.replaceAll("<[^>]+>", " ");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }
}
