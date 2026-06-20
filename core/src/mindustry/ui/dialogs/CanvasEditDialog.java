package mindustry.ui.dialogs;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.logic.CanvasBlock.*;

import static mindustry.Vars.*;

public class CanvasEditDialog extends BaseDialog{
    static final float refreshTime = 60f * 2f;
    static final String customPaletteKey = "canvas-custom-palette";
    static final int maxHistory = 10;
    static Pixmap clipboard;
    static Texture clipboardTexture;

    int curColor;
    boolean modified, grid = true;
    float time;
    CanvasBuild canvas;
    CanvasBlock block;
    Pixmap pix;
    Texture texture;
    Color current = new Color();
    TextField hexField;
    Slider rSlider, gSlider, bSlider, aSlider, brushSlider;
    int brush = 1;
    /** Prevents RGBA slider {@code moved()} from mixing stale channel values when sliders are set programmatically. */
    boolean syncingColorUi;
    boolean deletePaletteMode;
    Seq<Integer> customPalette;
    Seq<Pixmap> undoStack = new Seq<>(), redoStack = new Seq<>();
    CanvasTool tool = CanvasTool.brush;
    PasteRegion paste;
    boolean actionOpen, actionChanged;

    enum CanvasTool{
        brush, fill, line, rect, rectFill, circle, circleFill, copy, paste
    }

    static class PasteRegion{
        int x, y;
    }

    public CanvasEditDialog(CanvasBuild canvas){
        super("");
        titleTable.remove();
        this.canvas = canvas;
        block = (CanvasBlock)canvas.block;
        int size = block.canvasSize;
        pix = block.makePixmap(canvas.data, new Pixmap(size, size));
        texture = new Texture(pix);
        curColor = block.palette[0];
        current.set(curColor);
        customPalette = Core.settings.getJson(customPaletteKey, Seq.class, Integer.class, Seq::new);

        addCloseButton(160f);

        buttons.button("@import", Icon.image, () -> platform.showFileChooser(true, "png", this::importFrom));

        buttons.button("@export", Icon.export, () -> platform.showFileChooser(false, "png", this::exportTo));

        hidden(() -> {
            save();

            clearHistory(undoStack);
            clearHistory(redoStack);
            texture.dispose();
            pix.dispose();
        });

        resized(this::hide);

        //update at an interval so that people can see what is being drawn
        update(() -> {
            if(!canvas.isValid()){
                hide();
            }

            time += Time.delta;

            if(time >= refreshTime){
                save();
                time = 0f;
            }
        });

        cont.table(Tex.button, tools -> {
            tools.defaults().height(44f).padRight(4f);
            tools.button(Icon.pencil, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.brush)).size(44f).checked(b -> tool == CanvasTool.brush).tooltip("@canvas.brush");
            tools.button(Icon.fill, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.fill)).size(44f).checked(b -> tool == CanvasTool.fill).tooltip("@canvas.fill");
            tools.button(Icon.right, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.line)).size(44f).checked(b -> tool == CanvasTool.line).tooltip("@canvas.line");
            tools.button(Icon.box, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.rect)).size(44f).checked(b -> tool == CanvasTool.rect).tooltip("@canvas.rect");
            tools.button(Icon.box, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.rectFill)).size(44f).checked(b -> tool == CanvasTool.rectFill).tooltip("@canvas.rectfill");
            tools.button(Icon.commandRally, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.circle)).size(44f).checked(b -> tool == CanvasTool.circle).tooltip("@canvas.circle");
            tools.button(Icon.commandRally, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.circleFill)).size(44f).checked(b -> tool == CanvasTool.circleFill).tooltip("@canvas.circlefill");
            tools.button(Icon.copy, Styles.clearNoneTogglei, () -> selectTool(CanvasTool.copy)).size(44f).checked(b -> tool == CanvasTool.copy).tooltip("@canvas.copy");
            tools.button(Icon.paste, Styles.clearNoneTogglei, this::startPaste).size(44f).checked(b -> tool == CanvasTool.paste).disabled(b -> clipboard == null).tooltip("@canvas.paste");
            tools.button(Icon.undo, Styles.clearNonei, this::undo).size(44f).disabled(b -> undoStack.isEmpty()).tooltip("@canvas.undo");
            tools.button(Icon.redo, Styles.clearNonei, this::redo).size(44f).disabled(b -> redoStack.isEmpty()).tooltip("@canvas.redo");
            tools.button(Icon.ok, Styles.clearNonei, this::confirmPaste).size(44f).visible(() -> paste != null).tooltip("@canvas.confirm");
            tools.button(Icon.cancel, Styles.clearNonei, this::cancelPaste).size(44f).visible(() -> paste != null).tooltip("@canvas.cancel");
        }).colspan(3).left().row();

        cont.table(Tex.pane, body -> {
            body.center();

            //canvas element centered; tools panel is separate
            var canvasElement = new Element(){
                int lastX, lastY, startX, startY, endX, endY;
                boolean dragging, movingPaste;
                IntSeq stack = new IntSeq();

                int convertX(float ex){
                    return (int)((ex) / (width / size));
                }

                int convertY(float ey){
                    return pix.height - 1 - (int)((ey) / (height / size));
                }

                {
                    addListener(new InputListener(){

                        @Override
                        public boolean touchDown(InputEvent event, float ex, float ey, int pointer, KeyCode button){
                            int cx = convertX(ex), cy = convertY(ey);

                            if(button == KeyCode.mouseLeft){
                                if(tool == CanvasTool.paste && paste != null){
                                    movingPaste = true;
                                    movePaste(cx, cy);
                                    return true;
                                }

                                if(!pix.in(cx, cy)) return false;

                                if(tool == CanvasTool.fill){
                                    beginAction();
                                    stack.clear();
                                    int src = curColor;
                                    int dst = pix.get(cx, cy);
                                    if(src != dst){
                                        stack.add(Point2.pack(cx, cy));
                                        while(!stack.isEmpty()){
                                            int current = stack.pop();
                                            int x = Point2.x(current), y = Point2.y(current);
                                            drawBrush(x, y);
                                            for(int i = 0; i < 4; i++){
                                                int nx = x + Geometry.d4x(i), ny = y + Geometry.d4y(i);
                                                if(nx >= 0 && ny >= 0 && nx < pix.width && ny < pix.height && pix.get(nx, ny) == dst){
                                                    stack.add(Point2.pack(nx, ny));
                                                }
                                            }
                                        }
                                    }
                                    finishAction();

                                    return false;
                                }else if(tool == CanvasTool.brush){
                                    beginAction();
                                    drawBrush(cx, cy);
                                    lastX = cx;
                                    lastY = cy;
                                }else{
                                    dragging = true;
                                    startX = endX = cx;
                                    startY = endY = cy;
                                }
                            }else if(button == KeyCode.mouseMiddle){
                                if(pix.in(cx, cy)) CanvasEditDialog.this.setColor(pix.get(cx, cy));
                                return false;
                            }
                            return true;
                        }

                        @Override
                        public void touchDragged(InputEvent event, float ex, float ey, int pointer){
                            int cx = convertX(ex), cy = convertY(ey);
                            if(movingPaste){
                                movePaste(cx, cy);
                                return;
                            }
                            if(tool == CanvasTool.brush){
                                Bresenham2.line(lastX, lastY, cx, cy, (x, y) -> drawBrush(x, y));
                                lastX = cx;
                                lastY = cy;
                            }else if(dragging){
                                endX = Mathf.clamp(cx, 0, pix.width - 1);
                                endY = Mathf.clamp(cy, 0, pix.height - 1);
                            }
                        }

                        @Override
                        public void touchUp(InputEvent event, float ex, float ey, int pointer, KeyCode button){
                            if(button != KeyCode.mouseLeft) return;
                            int cx = Mathf.clamp(convertX(ex), 0, pix.width - 1), cy = Mathf.clamp(convertY(ey), 0, pix.height - 1);

                            if(movingPaste){
                                movingPaste = false;
                                movePaste(cx, cy);
                                return;
                            }

                            if(tool == CanvasTool.brush){
                                finishAction();
                            }else if(dragging){
                                endX = cx;
                                endY = cy;
                                if(tool == CanvasTool.copy){
                                    copySelection(startX, startY, endX, endY);
                                }else{
                                    beginAction();
                                    drawShape(tool, startX, startY, endX, endY);
                                    finishAction();
                                }
                                dragging = false;
                            }
                        }
                    });
                }

                @Override
                public void draw(){
                    Tmp.tr1.set(texture);
                    Draw.alpha(parentAlpha);
                    Draw.rect(Tmp.tr1, x + width/2f, y + height/2f, width, height);

                    drawPastePreview(x, y, width / size, height / size, parentAlpha);

                    //draw grid
                    if(grid){
                        float xspace = (getWidth() / size);
                        float yspace = (getHeight() / size);
                        float s = 1f;

                        int minspace = 10;

                        int jumpx = (int)(Math.max(minspace, xspace) / xspace);
                        int jumpy = (int)(Math.max(minspace, yspace) / yspace);

                        for(int x = 0; x <= size; x += jumpx){
                            Fill.crect((int)(this.x + xspace * x - s), y - s, 2, getHeight() + (x == size ? 1 : 0));
                        }

                        for(int y = 0; y <= size; y += jumpy){
                            Fill.crect(x - s, (int)(this.y + y * yspace - s), getWidth(), 2);
                        }
                    }

                    if(!mobile){
                        Vec2 s = screenToLocalCoordinates(Core.input.mouse());
                        if(s.x >= 0 && s.y >= 0 && s.x < width && s.y < height){
                            float sx = Mathf.round(s.x, width / size), sy = Mathf.round(s.y, height / size);

                            Lines.stroke(Scl.scl(6f));
                            Draw.color(Pal.accent);
                            Lines.rect(sx + x, sy + y, width / size, height / size, Lines.getStroke() - 1f);

                            Draw.reset();
                        }
                    }

                    if(dragging){
                        drawToolPreview(tool, startX, startY, endX, endY, x, y, width / size, height / size, parentAlpha);
                    }
                }
            };

            float canvasPx = mobile && !Core.graphics.isPortrait() ? Math.min(290f, Core.graphics.getHeight() / Scl.scl(1f) - 75f / Scl.scl(1f)) : 480f;
            body.add(canvasElement).size(canvasPx);
            body.add().width(8f);

            body.table(Tex.button, right -> {
                right.defaults().left().pad(6f);

                //color preview circle (clickable)
                var preview = new Element(){
                    @Override
                    public void draw(){
                        Draw.alpha(parentAlpha);
                        //alpha background
                        Tex.alphaBg.draw(x, y, width, height);
                        //circle fill
                        Draw.color(current);
                        Fill.circle(x + width/2f, y + height/2f, Math.min(width, height)/2f - 2f);
                        Draw.reset();
                    }
                };

                preview.touchable = Touchable.enabled;
                preview.clicked(() -> ui.picker.show(Tmp.c1.set(current), true, c -> setColor(c.rgba8888())));

                var pick = new Table();
                pick.add(preview).size(44f);
                right.add(pick).row();

                //hex
                right.add("@color").padRight(6f);
                hexField = right.field(current.toString(), value -> {
                    if(syncingColorUi) return;
                    try{
                        Color.valueOf(current, value);
                        setColor(current.rgba8888(), false);
                    }catch(Exception ignored){
                    }
                }).width(180f).valid(text -> {
                    try{
                        Color.valueOf(text);
                        return true;
                    }catch(Exception e){
                        return false;
                    }
                }).get();
                right.row();

                right.button("@pickcolor", Icon.pencil, () -> ui.picker.show(Tmp.c1.set(current), true, c -> setColor(c.rgba8888()))).colspan(2).growX();
                right.row();

                //RGBA sliders
                right.add("R").padRight(6f);
                right.add(rSlider = new Slider(0f, 1f, 1f/255f, false)).width(220f).row();
                right.add("G").padRight(6f);
                right.add(gSlider = new Slider(0f, 1f, 1f/255f, false)).width(220f).row();
                right.add("B").padRight(6f);
                right.add(bSlider = new Slider(0f, 1f, 1f/255f, false)).width(220f).row();
                right.add("A").padRight(6f);
                right.add(aSlider = new Slider(0f, 1f, 1f/255f, false)).width(220f).row();

                //brush
                right.add("@canvas.brushsize").padRight(6f);
                right.add(brushSlider = new Slider(1f, 8f, 1f, false)).width(220f).row();

                //init slider values + listeners
                rSlider.setValue(current.r);
                gSlider.setValue(current.g);
                bSlider.setValue(current.b);
                aSlider.setValue(current.a);
                brushSlider.setValue(brush);

                Runnable updateFromSliders = () -> {
                    if(syncingColorUi) return;
                    current.set(rSlider.getValue(), gSlider.getValue(), bSlider.getValue(), aSlider.getValue());
                    setColor(current.rgba8888());
                };

                rSlider.moved(v -> updateFromSliders.run());
                gSlider.moved(v -> updateFromSliders.run());
                bSlider.moved(v -> updateFromSliders.run());
                aSlider.moved(v -> updateFromSliders.run());
                brushSlider.moved(v -> brush = (int)v);
            }).growY().width(320f);
        }).colspan(3);

        cont.row();

        cont.table(Tex.button, t -> {
            t.button(Icon.grid, Styles.clearNoneTogglei, () -> grid = !grid).checked(grid).size(44f);
        });

        // Palette with persistent custom colors.
        cont.table(Tex.button, p -> {
            Runnable[] rebuild = {null};
            rebuild[0] = () -> {
                p.clearChildren();

                // toolbar row
                p.table(bar -> {
                    bar.left();
                    bar.defaults().size(44f).padRight(4f);

                    // add current color to custom palette
                    bar.button(Icon.add, Styles.clearNoneTogglei, () -> {
                        int rgba = curColor;
                        // keep alpha consistent
                        if(Color.ai(rgba) == 0) rgba |= 0xff;
                        if(!customPalette.contains(rgba, false)){
                            customPalette.add(rgba);
                            Core.settings.putJson(customPaletteKey, Integer.class, customPalette);
                        }
                        rebuild[0].run();
                    }).tooltip("@add");

                    // toggle delete mode for custom colors
                    bar.button(Icon.trash, Styles.clearNoneTogglei, () -> {
                        deletePaletteMode = !deletePaletteMode;
                    }).checked(b -> deletePaletteMode).tooltip("@save.delete");
                }).growX().left().row();

                int cols = 12;
                int idx = 0;

                // base palette
                for(int i = 0; i < block.palette.length; i++){
                    if(idx % cols == 0) p.row();
                    int rgba = block.palette[i];
                    ImageButton button = p.button(Tex.whiteui, Styles.squareTogglei, 30f, () -> {
                        setColor(rgba);
                    }).size(44f).checked(b -> curColor == rgba).get();
                    button.getStyle().imageUpColor = new Color(rgba);
                    idx++;
                }

                // custom palette (persisted)
                for(int i = 0; i < customPalette.size; i++){
                    if(idx % cols == 0) p.row();
                    int rgba = customPalette.get(i);
                    ImageButton button = p.button(Tex.whiteui, Styles.squareTogglei, 30f, () -> {
                        if(deletePaletteMode){
                            customPalette.remove(Integer.valueOf(rgba), false);
                            Core.settings.putJson(customPaletteKey, Integer.class, customPalette);
                            rebuild[0].run();
                        }else{
                            setColor(rgba);
                        }
                    }).size(44f).checked(b -> curColor == rgba).get();
                    button.getStyle().imageUpColor = new Color(rgba);
                    idx++;
                }
            };

            rebuild[0].run();
        });

        buttons.defaults().size(150f, 64f);
    }

    void exportTo(Fi file){
        try{
            file.writePng(pix);
        }catch(Exception e){
            ui.showException(e);
        }
    }

    void importFrom(Fi file){
        try{
            Pixmap source = new Pixmap(file);
            int size = pix.width;
            if(source.width > size || source.height > size){
                float ratio = (float)Math.max(source.width, source.height) / size;
                Pixmap dest = new Pixmap(size, size);
                dest.draw(source, 0, 0, source.width, source.height, (size - (int)(source.width / ratio))/2, (size - (int)(source.height / ratio))/2, (int)(source.width / ratio), (int)(source.height / ratio));
                source.dispose();
                source = dest;
            }else if(source.width < size || source.height < size){
                Pixmap dest = new Pixmap(size, size);
                dest.draw(source, (size - source.width)/2, (size - source.height)/2);
                source.dispose();
                source = dest;
            }
            beginAction();
            int sizeX = Math.min(source.width, pix.width), sizeY = Math.min(source.height, pix.height);
            for(int x = 0; x < sizeX; x++){
                for(int y = 0; y < sizeY; y++){
                    pix.setRaw(x, y, source.getRaw(x, y));
                }
            }
            source.dispose();

            texture.draw(pix);
            actionChanged = true;
            finishAction();
        }catch(Exception e){
            ui.showException("@editor.errorload", e);
        }
    }

    void selectTool(CanvasTool next){
        if(next != CanvasTool.paste) paste = null;
        tool = next;
    }

    void beginAction(){
        if(actionOpen) return;
        pushHistory(undoStack, copyPixmap(pix));
        clearHistory(redoStack);
        actionOpen = true;
        actionChanged = false;
    }

    void finishAction(){
        if(!actionOpen) return;
        actionOpen = false;

        if(actionChanged){
            modified = true;
        }else if(!undoStack.isEmpty()){
            undoStack.pop().dispose();
        }
    }

    void pushHistory(Seq<Pixmap> stack, Pixmap snapshot){
        stack.add(snapshot);
        while(stack.size > maxHistory){
            stack.remove(0).dispose();
        }
    }

    Pixmap copyPixmap(Pixmap source){
        Pixmap copy = new Pixmap(source.width, source.height);
        for(int x = 0; x < source.width; x++){
            for(int y = 0; y < source.height; y++){
                copy.setRaw(x, y, source.getRaw(x, y));
            }
        }
        return copy;
    }

    void applySnapshot(Pixmap snapshot){
        for(int x = 0; x < pix.width; x++){
            for(int y = 0; y < pix.height; y++){
                pix.setRaw(x, y, snapshot.getRaw(x, y));
            }
        }
        texture.draw(pix);
        modified = true;
    }

    void undo(){
        if(undoStack.isEmpty()) return;
        pushHistory(redoStack, copyPixmap(pix));
        Pixmap snapshot = undoStack.pop();
        applySnapshot(snapshot);
        snapshot.dispose();
    }

    void redo(){
        if(redoStack.isEmpty()) return;
        pushHistory(undoStack, copyPixmap(pix));
        Pixmap snapshot = redoStack.pop();
        applySnapshot(snapshot);
        snapshot.dispose();
    }

    void clearHistory(Seq<Pixmap> stack){
        for(Pixmap p : stack){
            p.dispose();
        }
        stack.clear();
    }

    boolean drawPixel(int x, int y, int color){
        if(!pix.in(x, y) || pix.get(x, y) == color) return false;
        pix.set(x, y, color);
        Pixmaps.drawPixel(texture, x, y, color);
        actionChanged = true;
        return true;
    }

    void drawBrush(int x, int y){
        int radius = Math.max(0, brush - 1);
        int rr = radius * radius;
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                if(dx*dx + dy*dy > rr) continue;
                drawPixel(x + dx, y + dy, curColor);
            }
        }
    }

    void drawShape(CanvasTool shape, int x1, int y1, int x2, int y2){
        switch(shape){
            case line -> Bresenham2.line(x1, y1, x2, y2, this::drawBrush);
            case rect -> drawRect(x1, y1, x2, y2, false);
            case rectFill -> drawRect(x1, y1, x2, y2, true);
            case circle -> drawEllipse(x1, y1, x2, y2, false);
            case circleFill -> drawEllipse(x1, y1, x2, y2, true);
            default -> {
            }
        }
    }

    void drawRect(int x1, int y1, int x2, int y2, boolean filled){
        int minx = Math.min(x1, x2), maxx = Math.max(x1, x2);
        int miny = Math.min(y1, y2), maxy = Math.max(y1, y2);

        for(int x = minx; x <= maxx; x++){
            for(int y = miny; y <= maxy; y++){
                if(filled || x == minx || x == maxx || y == miny || y == maxy) drawPixel(x, y, curColor);
            }
        }
    }

    void drawEllipse(int x1, int y1, int x2, int y2, boolean filled){
        int minx = Math.min(x1, x2), maxx = Math.max(x1, x2);
        int miny = Math.min(y1, y2), maxy = Math.max(y1, y2);
        float cx = (minx + maxx) / 2f, cy = (miny + maxy) / 2f;
        float rx = Math.max(0.5f, (maxx - minx + 1f) / 2f), ry = Math.max(0.5f, (maxy - miny + 1f) / 2f);

        for(int x = minx; x <= maxx; x++){
            for(int y = miny; y <= maxy; y++){
                float nx = (x + 0.5f - cx) / rx, ny = (y + 0.5f - cy) / ry;
                float dst = nx * nx + ny * ny;
                if(filled ? dst <= 1f : dst <= 1f && dst >= 0.72f) drawPixel(x, y, curColor);
            }
        }
    }

    void copySelection(int x1, int y1, int x2, int y2){
        int minx = Math.min(x1, x2), maxx = Math.max(x1, x2);
        int miny = Math.min(y1, y2), maxy = Math.max(y1, y2);
        clearClipboard();

        clipboard = new Pixmap(maxx - minx + 1, maxy - miny + 1);
        for(int x = 0; x < clipboard.width; x++){
            for(int y = 0; y < clipboard.height; y++){
                clipboard.setRaw(x, y, pix.getRaw(minx + x, miny + y));
            }
        }
        clipboardTexture = new Texture(clipboard);
    }

    void startPaste(){
        if(clipboard == null) return;
        tool = CanvasTool.paste;
        paste = new PasteRegion();
        paste.x = Math.max(0, (pix.width - clipboard.width) / 2);
        paste.y = Math.max(0, (pix.height - clipboard.height) / 2);
    }

    void movePaste(int cx, int cy){
        if(paste == null || clipboard == null) return;
        paste.x = Mathf.clamp(cx - clipboard.width / 2, 0, Math.max(0, pix.width - clipboard.width));
        paste.y = Mathf.clamp(cy - clipboard.height / 2, 0, Math.max(0, pix.height - clipboard.height));
    }

    void confirmPaste(){
        if(paste == null || clipboard == null) return;
        beginAction();
        for(int x = 0; x < clipboard.width; x++){
            for(int y = 0; y < clipboard.height; y++){
                drawPixel(paste.x + x, paste.y + y, clipboard.get(x, y));
            }
        }
        finishAction();
        paste = null;
        tool = CanvasTool.brush;
    }

    void cancelPaste(){
        paste = null;
        if(tool == CanvasTool.paste) tool = CanvasTool.brush;
    }

    void clearClipboard(){
        if(clipboardTexture != null){
            clipboardTexture.dispose();
            clipboardTexture = null;
        }
        if(clipboard != null){
            clipboard.dispose();
            clipboard = null;
        }
        paste = null;
    }

    float screenY(float oy, float yspace, int pixY){
        return oy + (pix.height - 1 - pixY) * yspace;
    }

    float screenCenterY(float oy, float yspace, int pixY){
        return screenY(oy, yspace, pixY) + yspace / 2f;
    }

    void drawPastePreview(float ox, float oy, float xspace, float yspace, float alpha){
        if(paste == null || clipboardTexture == null) return;
        float px = ox + paste.x * xspace;
        float py = oy + (pix.height - paste.y - clipboard.height) * yspace;
        Tmp.tr1.set(clipboardTexture);
        Draw.color(1f, 1f, 1f, 0.55f * alpha);
        Draw.rect(Tmp.tr1, px + clipboard.width * xspace / 2f, py + clipboard.height * yspace / 2f, clipboard.width * xspace, clipboard.height * yspace);
        Draw.color(Pal.accent, alpha);
        Lines.stroke(Scl.scl(2f));
        Lines.rect(px, py, clipboard.width * xspace, clipboard.height * yspace);
        Draw.reset();
    }

    void drawToolPreview(CanvasTool preview, int x1, int y1, int x2, int y2, float ox, float oy, float xspace, float yspace, float alpha){
        int minPixX = Math.min(x1, x2), maxPixX = Math.max(x1, x2);
        int minPixY = Math.min(y1, y2), maxPixY = Math.max(y1, y2);
        float minx = ox + minPixX * xspace, maxx = ox + (maxPixX + 1) * xspace;
        float miny = screenY(oy, yspace, maxPixY), maxy = screenY(oy, yspace, minPixY) + yspace;

        Draw.color(preview == CanvasTool.copy ? Pal.accent : current, alpha);
        Lines.stroke(Scl.scl(2f));
        switch(preview){
            case line -> Lines.line(ox + (x1 + 0.5f) * xspace, screenCenterY(oy, yspace, y1), ox + (x2 + 0.5f) * xspace, screenCenterY(oy, yspace, y2));
            case rect, copy -> Lines.rect(minx, miny, maxx - minx, maxy - miny);
            case rectFill -> {
                Draw.alpha(0.25f * alpha);
                Fill.crect(minx, miny, maxx - minx, maxy - miny);
                Draw.color(current, alpha);
                Lines.rect(minx, miny, maxx - minx, maxy - miny);
            }
            case circle -> Lines.ellipse(40, (minx + maxx) / 2f, (miny + maxy) / 2f, (maxx - minx) / 2f, (maxy - miny) / 2f, 0f);
            case circleFill -> {
                Draw.color(current, alpha);
                Lines.ellipse(40, (minx + maxx) / 2f, (miny + maxy) / 2f, (maxx - minx) / 2f, (maxy - miny) / 2f, 0f);
            }
            default -> {
            }
        }
        Draw.reset();
    }

    void setColor(int rgba){
        setColor(rgba, true);
    }

    void setColor(int rgba, boolean updateSliders){
        curColor = rgba;
        current.set(rgba);

        if(updateSliders && rSlider != null){
            syncingColorUi = true;
            try{
                rSlider.setValue(current.r);
                gSlider.setValue(current.g);
                bSlider.setValue(current.b);
                aSlider.setValue(current.a);
            }finally{
                syncingColorUi = false;
            }
        }

        if(hexField != null){
            String val = current.toString();
            if(current.a >= 0.9999f){
                val = val.substring(0, 6);
            }
            if(!hexField.hasKeyboard()){
                syncingColorUi = true;
                try{
                    hexField.setText(val);
                }finally{
                    syncingColorUi = false;
                }
            }
        }
    }

    void save(){
        if(modified && canvas.isValid()){
            byte[] rgba = canvas.packPixmap(pix);

            // Multiplayer-safe path:
            // - send full truecolor to server via existing vanilla binary packet channel
            // - server re-broadcasts legacy to vanilla clients + truecolor to modded clients (also via binary channel)
            if(net.client()){
                // Apply locally right away so the user never sees a palette-quantized flash
                // while waiting for the server echo ("delta-canvas-true").
                canvas.applyTrueColor(rgba);

                // Always send truecolor — Delta servers handle this; vanilla servers ignore it.
                var stream = new arc.util.io.ReusableByteOutStream();
                var writes = new arc.util.io.Writes(new java.io.DataOutputStream(stream));
                writes.i(canvas.pos());
                writes.i(rgba.length);
                writes.b(rgba);
                Call.serverBinaryPacketReliable("delta-canvas", stream.toByteArray());

                if(!netClient.deltaServer){
                    // Vanilla servers only understand legacy indexed canvas config.
                    Call.tileConfig(player, canvas, canvas.legacyBytesPublic());
                }
            }else if(net.server()){
                // Host is editing – apply locally and split broadcast: legacy for vanilla, truecolor for delta.
                canvas.applyTrueColor(rgba);

                byte[] legacy = canvas.legacyBytesPublic();
                var legacyPacket = new mindustry.gen.TileConfigCallPacket();
                legacyPacket.player = null;
                legacyPacket.build = canvas;
                legacyPacket.value = legacy;

                var stream2 = new arc.util.io.ReusableByteOutStream();
                var writes2 = new arc.util.io.Writes(new java.io.DataOutputStream(stream2));
                writes2.i(canvas.pos());
                writes2.i(rgba.length);
                writes2.b(rgba);
                byte[] payload = stream2.toByteArray();

                for(mindustry.gen.Player other : mindustry.gen.Groups.player){
                    if(other.con == null) continue;
                    if(other.con.deltaClient){
                        Call.clientBinaryPacketReliable(other.con, "delta-canvas-true", payload);
                    }else{
                        other.con.send(legacyPacket, true);
                    }
                }
            }else{
                // singleplayer/offline – apply directly
                canvas.applyTrueColor(rgba);
            }
            modified = false;
        }
    }
}
