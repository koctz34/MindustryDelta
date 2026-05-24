package mindustry.ui.dialogs;

import arc.*;
import arc.graphics.*;
import arc.struct.Seq;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.news.*;

import static mindustry.Vars.*;

public class NewsListDialog extends BaseDialog{
    public NewsListDialog(){
        super("@news.list");
        shouldPause = false;
        addCloseButton();
        shown(() -> {
            if(NewsSettings.enabled() && NewsSettings.anySourceEnabled() && ui.newsService.shouldRefresh()){
                ui.newsService.fetch(ok -> Core.app.post(this::rebuild));
            }
            rebuild();
        });
        onResize(this::rebuild);
    }

    void rebuild(){
        cont.clear();
        buttons.clear();
        addCloseButton();

        float w = mobile ? Core.graphics.getWidth() - 56f : Math.min(960f, Core.graphics.getWidth() - 140f);
        float paneH = mobile ? Core.graphics.getHeight() - 180f : Core.graphics.getHeight() - 190f;
        float rowW = w - 32f;

        Table list = new Table();
        list.margin(10f);
        ScrollPane pane = new ScrollPane(list);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);

        Seq<NewsItem> visible = ui.newsService.filteredItems();
        if(ui.newsService.loading && visible.isEmpty()){
            list.add("@news.loading").pad(20f).row();
        }else if(visible.isEmpty()){
            list.add(ui.newsService.lastError != null ? "@news.error" : "@news.empty").pad(20f).row();
        }else{
            for(NewsItem item : visible){
                list.add(buildRow(item, rowW)).width(rowW).padBottom(7f).row();
            }
        }

        cont.add(pane).grow().width(w).height(paneH);
        shown(() -> Time.run(1f, () -> Core.scene.setScrollFocus(pane)));
    }

    Table buildRow(NewsItem item, float rowW){
        Table row = new Table(Styles.grayPanel);
        row.margin(0f);
        row.touchable = Touchable.enabled;

        ClickListener listener = new ClickListener(){
            @Override
            public void clicked(InputEvent event, float x, float y){
                ui.newsDetail.show(item);
            }
        };
        row.addListener(listener);
        row.update(() -> row.setBackground(listener.isOver() ? Styles.flatOver : Styles.grayPanel));

        Table stripe = new Table(Tex.whiteui);
        stripe.setColor(Pal.accent);
        row.add(stripe).width(5f).growY();

        float btnW = mobile ? 50f : 58f;
        float textW = rowW - 5f - btnW - 24f - (item.imageUrl.isEmpty() ? 0f : (mobile ? 86f : 106f));

        Table body = new Table();
        body.margin(10f).left().top();

        if(!item.imageUrl.isEmpty()){
            String imgUrl = NewsService.normalizeImageUrl(item.imageUrl);
            Image thumb = new Image(ui.newsService.images.get(imgUrl, () -> Core.atlas.getDrawable("nomap")));
            thumb.setScaling(Scaling.fit);
            ui.newsService.requestImage(imgUrl, () -> thumb.setDrawable(ui.newsService.images.get(imgUrl)));
            body.add(thumb).size(mobile ? 76f : 96f, mobile ? 52f : 60f).padRight(10f).top();
        }

        Table text = new Table();
        text.top().left();
        text.add(item.metaText()).color(Color.gray).left().wrap().width(textW).row();
        text.add(item.title).color(Pal.accent).left().wrap().width(textW).padTop(3f).row();
        text.add(item.previewSummary(mobile ? 120 : 200)).color(Color.lightGray).left().wrap().width(textW).padTop(5f).row();
        body.add(text).width(textW).top().left();

        row.add(body).growX().left().top();
        row.button(Icon.rightOpen, Styles.clearNonei, () -> ui.newsDetail.show(item)).size(btnW).padRight(6f).top();

        return row;
    }
}
