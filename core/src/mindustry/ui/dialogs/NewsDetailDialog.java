package mindustry.ui.dialogs;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.news.*;

import static mindustry.Vars.*;

public class NewsDetailDialog extends BaseDialog{
    private NewsItem current;
    private Image image;
    private String imageKey = "";

    public NewsDetailDialog(){
        super("@news.title");
        shouldPause = false;
        addCloseButton();
        onResize(this::rebuild);
    }

    public void show(NewsItem item){
        current = item;
        rebuild();
        super.show();
    }

    void rebuild(){
        if(current == null) return;

        title.setText(current.title);
        cont.clear();
        buttons.clear();
        addCloseButton();

        float w = mobile ? Core.graphics.getWidth() - 64f : Math.min(900f, Core.graphics.getWidth() - 180f);
        float h = mobile ? Core.graphics.getHeight() * 0.72f : Core.graphics.getHeight() * 0.72f;
        float textW = w - 48f;

        boolean pendingFull = !ui.newsService.fullTexts.containsKey(current.link) && !ui.newsService.isFullLoading(current);

        Table inner = new Table();
        inner.margin(14f);
        ScrollPane pane = new ScrollPane(inner);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);

        Table header = new Table(Styles.black5);
        header.margin(12f);
        header.add(current.title).color(Pal.accent).width(textW).wrap().left().row();
        header.add(current.metaText()).color(Color.gray).width(textW).wrap().left().padTop(4f);
        inner.add(header).width(w - 28f).padBottom(8f).row();

        imageKey = NewsService.largerImageUrl(ui.newsService.getMainImage(current));
        if(!imageKey.isEmpty()){
            image = new Image(getImageDrawable(imageKey));
            image.setScaling(Scaling.fit);
            inner.add(image).width(w - 28f).maxHeight(280f).padBottom(8f).row();
            ui.newsService.requestImage(imageKey, this::updateImage);
        }else{
            image = null;
        }

        if(ui.newsService.isFullLoading(current) && !ui.newsService.fullTexts.containsKey(current.link)){
            inner.add("[lightgray]@news.loadingfull[]").width(textW).wrap().left().pad(8f).row();
        }

        String text = ui.newsService.fullTexts.get(current.link, current.hasEmbeddedFull() ? current.fullContent : current.summary);
        Table body = new Table(Styles.black5);
        body.margin(14f);
        body.add(text).width(textW).wrap().left().row();
        inner.add(body).width(w - 28f).padTop(4f).row();

        cont.add(pane).grow().width(w).height(h);

        buttons.button("@news.openlink", Icon.link, () -> Menus.openURI(current.link)).size(240f, 64f);

        shown(() -> Time.run(1f, () -> Core.scene.setScrollFocus(pane)));

        if(pendingFull){
            NewsItem shown = current;
            ui.newsService.requestFull(shown, () -> {
                if(current != shown) return;
                rebuild();
            });
        }
    }

    Drawable getImageDrawable(String key){
        String norm = NewsService.normalizeImageUrl(key);
        if(ui.newsService.images.containsKey(norm)){
            return ui.newsService.images.get(norm);
        }
        if(norm != null && !norm.equals(key) && ui.newsService.images.containsKey(key)){
            return ui.newsService.images.get(key);
        }
        if(current != null && current.imageUrl != null){
            String itemUrl = NewsService.normalizeImageUrl(current.imageUrl);
            if(ui.newsService.images.containsKey(itemUrl)) return ui.newsService.images.get(itemUrl);
        }
        return new TextureRegionDrawable(Core.atlas.find("nomap"));
    }

    void updateImage(){
        if(image == null || imageKey.isEmpty()) return;
        image.setDrawable(getImageDrawable(imageKey));
    }
}
