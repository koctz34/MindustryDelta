package mindustry.ui.news;

import java.text.*;
import java.util.*;

/** Single news entry from an RSS feed. */
public class NewsItem implements Comparable<NewsItem>{
    private static final DateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    public final String title;
    public final String summary;
    public final String fullContent;
    public final String link;
    public final String imageUrl;
    public final String source;
    public final long pubDate;

    public NewsItem(String title, String summary, String fullContent, String link, String imageUrl, String source, long pubDate){
        this.title = title;
        this.summary = summary;
        this.fullContent = fullContent == null ? "" : fullContent;
        this.link = link;
        this.imageUrl = imageUrl;
        this.source = source;
        this.pubDate = pubDate;
    }

    public boolean hasEmbeddedFull(){
        return !fullContent.isEmpty();
    }

    public String previewSummary(int maxLen){
        String text = summary.isEmpty() ? fullContent : summary;
        if(text.length() <= maxLen) return text;
        int cut = text.lastIndexOf(' ', maxLen);
        if(cut < maxLen / 2) cut = maxLen;
        return text.substring(0, cut).trim() + "...";
    }

    public String dateText(){
        return pubDate <= 0L ? "" : dateFormat.format(new Date(pubDate));
    }

    public String metaText(){
        String date = dateText();
        return date.isEmpty() ? source : source + "  |  " + date;
    }

    @Override
    public int compareTo(NewsItem other){
        return Long.compare(other.pubDate, pubDate);
    }
}
