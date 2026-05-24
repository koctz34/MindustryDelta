package mindustry.ui.fragments;

import arc.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.news.*;

import static mindustry.Vars.*;

public class NewsFragment{
    private final NewsService service;
    private Table panel;
    private float panelWidth;
    private float blockWidth;
    private boolean requested;
    private Group group;

    public NewsFragment(NewsService service){
        this.service = service;
    }

    public void build(Group parent){
        // World news panel is desktop-only; it clutters the main menu on Android.
        if(android) return;

        group = new WidgetGroup();
        group.setFillParent(true);
        group.touchable = Touchable.childrenOnly;
        group.visible(() -> state.isMenu() && !ui.editor.isShown() && NewsSettings.enabled() && NewsSettings.anySourceEnabled());
        parent.addChild(group);

        service.loadCache();
        requestFetch();

        rebuildRoot();
        Events.on(ResizeEvent.class, e -> rebuildRoot());
    }

    void rebuildRoot(){
        group.clearChildren();

        blockWidth = 300f;
        panelWidth = mobile ? Math.min(360f, Core.graphics.getWidth() * 0.55f) : 420f;
        float panelH = mobile ? 400f : 500f;
        float scrollH = panelH - 88f;

        panel = new Table(Styles.black6);
        panel.margin(10f);
        panel.touchable = Touchable.enabled;
        panel.top().left();

        Table header = new Table();
        header.image(Icon.bookOpenSmall).color(Pal.accent).padRight(5f);
        header.add("[accent]" + Core.bundle.get("news.title") + "[]").left().row();
        String hint = NewsSettings.sourcesHint();
        if(!hint.isEmpty()){
            header.add("[darkgray]" + hint + "[]").left().padTop(2f);
        }
        panel.add(header).width(blockWidth).left().padBottom(6f).row();

        Table items = new Table();
        items.top().left();
        Seq<NewsItem> visible = service.filteredItems();
        if(service.loading && visible.isEmpty()){
            items.add("[lightgray]@news.loading[]").left().pad(4f).row();
        }else if(visible.isEmpty()){
            items.add(service.lastError != null ? "[lightgray]@news.error[]" : "[lightgray]@news.empty[]").left().pad(4f).row();
        }else{
            Seq<NewsItem> preview = service.preview();
            for(int i = 0; i < preview.size; i++){
                items.add(buildBlock(preview.get(i), i)).width(blockWidth).center().padBottom(4f).row();
            }
        }

        ScrollPane scroll = new ScrollPane(items, Styles.noBarPane);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);
        scroll.setOverscroll(false, false);
        panel.add(scroll).width(panelWidth - 20f).height(scrollH).row();

        Table actions = new Table();
        actions.touchable = Touchable.enabled;
        actions.defaults().size(34f);

        ImageButton reload = actions.button(Icon.refresh, Styles.clearNonei, () -> {
            service.fetch(ok -> Core.app.post(this::rebuildItems));
            rebuildItems();
        }).tooltip(Core.bundle.get("news.reload")).get();
        reload.update(() -> reload.setColor(service.loading ? Pal.accent : Color.white));

        actions.button(Icon.listSmall, Styles.clearNonei, () -> ui.newsList.show())
        .tooltip(Core.bundle.get("news.list"));

        panel.add(actions).right().padTop(6f);

        Table root = new Table();
        root.setFillParent(true);
        root.touchable = Touchable.childrenOnly;

        float padR = mobile ? 8f : 30f;
        float padT = mobile ? Core.scene.marginTop + 90f : Mathf.clamp((Core.graphics.getHeight() - panelH) * 0.34f, 70f, 180f);

        if(mobile){
            root.top().right();
            root.add(panel).size(panelWidth, panelH).padRight(padR).padTop(padT);
        }else{
            root.top().right();
            root.add(panel).size(panelWidth, panelH).padRight(padR).padTop(padT);
        }

        group.addChild(root);

        if(!NewsSettings.enabled() || !NewsSettings.anySourceEnabled()) return;
        if(requested && !service.shouldRefresh()) return;
        if(service.shouldRefresh()){
            service.fetch(ok -> Core.app.post(this::rebuildItems));
        }
    }

    void requestFetch(){
        requested = true;
    }

    public void rebuildItems(){
        if(panel == null) return;
        rebuildRoot();
    }

    Table buildBlock(NewsItem item, int index){
        Table block = new Table(Styles.black5);
        block.margin(0f);
        block.touchable = Touchable.enabled;
        block.top().left();

        ClickListener listener = new ClickListener(){
            @Override
            public void clicked(InputEvent event, float x, float y){
                ui.newsDetail.show(item);
            }
        };
        block.addListener(listener);
        block.update(() -> block.setBackground(listener.isOver() ? Styles.black3 : Styles.black5));

        Table stripe = new Table(Tex.whiteui);
        stripe.setColor(Pal.accent);
        block.add(stripe).width(3f).growY().padTop(2f).padBottom(2f);

        Table inner = new Table();
        inner.margin(6f);
        inner.top().left();

        float textW = blockWidth - 3f - 12f;

        Table text = new Table();
        text.top().left();
        text.add(item.metaText()).color(Color.gray).left().wrap().width(textW).row();
        text.add(item.title).color(Pal.accent).left().wrap().width(textW).padTop(1f).row();
        text.add(item.previewSummary(mobile ? 70 : 90)).color(Color.lightGray).left().wrap().width(textW).padTop(2f).row();
        inner.add(text).width(textW).top().left();

        block.add(inner).growX().top().left();

        return block;
    }
}
