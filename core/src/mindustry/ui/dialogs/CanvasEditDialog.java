package mindustry.ui.dialogs;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
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
    static final String layoutKey = "canvas-layout";
    static final int maxHistory = 10;
    static Pixmap clipboard;
    static Texture clipboardTexture;

    int curColor;
    boolean modified, grid = false;
    float time;
    CanvasBuild canvas;
    CanvasBlock block;
    Pixmap pix;
    Texture texture;
    Color current = new Color();
    TextField hexField;
    Slider rSlider, gSlider, bSlider, aSlider, brushSlider, spraySlider;
    int brush = 1, sprayFrequency = 10;
    boolean spraying;
    int lastSprayX, lastSprayY;
    float sprayAccumulator;
    /** Prevents RGBA slider {@code moved()} from mixing stale channel values when sliders are set programmatically. */
    boolean syncingColorUi;
    boolean deletePaletteMode;
    Seq<Integer> customPalette;
    Seq<Pixmap> undoStack = new Seq<>(), redoStack = new Seq<>();
    CanvasTool tool = CanvasTool.brush;
    PasteRegion paste;
    boolean actionOpen, actionChanged;
    boolean layoutEditing, toolsVertical;
    float toolsX, toolsY, bodyX, bodyY, controlsX, controlsY, gridX, gridY, paletteX, paletteY;
    float oldToolsX, oldToolsY, oldBodyX, oldBodyY, oldControlsX, oldControlsY, oldGridX, oldGridY, oldPaletteX, oldPaletteY;
    boolean oldToolsVertical;
    Table toolsPanel, bodyPanel, controlsPanel, gridPanel, palettePanel;

    enum CanvasTool{
        brush, fill, colorFill, spray, line, rect, rectFill, circle, circleFill, copy, paste
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
        loadLayout();

        rebuildButtons();

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

            if(spraying && tool == CanvasTool.spray && !layoutEditing){
                sprayAccumulator += Time.delta * sprayFrequency;
                while(sprayAccumulator >= 1f){
                    sprayAt(lastSprayX, lastSprayY);
                    sprayAccumulator -= 1f;
                }
            }
        });

        cont.table(Tex.button, tools -> {
            toolsPanel = tools;
            rebuildTools();
        }).colspan(3).left().row();

        cont.table(Tex.pane, body -> {
            bodyPanel = body;
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
                            if(layoutEditing) return false;
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
                                }else if(tool == CanvasTool.colorFill){
                                    beginAction();
                                    replaceColor(pix.get(cx, cy), curColor);
                                    finishAction();
                                    return false;
                                }else if(tool == CanvasTool.spray){
                                    beginAction();
                                    spraying = true;
                                    lastSprayX = cx;
                                    lastSprayY = cy;
                                    sprayAccumulator = 0f;
                                    sprayAt(cx, cy);
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
                            if(tool == CanvasTool.spray && spraying){
                                lastSprayX = cx;
                                lastSprayY = cy;
                            }else if(tool == CanvasTool.brush){
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

                            if(tool == CanvasTool.spray && spraying){
                                spraying = false;
                                finishAction();
                            }else if(tool == CanvasTool.brush){
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

            body.table(sprayWrap -> {
                sprayWrap.defaults().pad(4f);
                sprayWrap.add("@canvas.sprayfreq").padBottom(6f).row();
                sprayWrap.add(spraySlider = new Slider(1f, 60f, 1f, true)).height(Math.max(120f, canvasPx - 24f)).width(32f);
                spraySlider.setValue(sprayFrequency);
                spraySlider.moved(v -> sprayFrequency = (int)v);
            }).visible(() -> tool == CanvasTool.spray && !layoutEditing).padRight(6f);

            body.add(canvasElement).size(canvasPx);
            body.add().width(8f);

            body.table(Tex.button, right -> {
                controlsPanel = right;
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
                preview.clicked(() -> {
                    if(!layoutEditing) ui.picker.show(Tmp.c1.set(current), true, c -> setColor(c.rgba8888()));
                });

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

                right.button("@pickcolor", Icon.pencil, () -> ui.picker.show(Tmp.c1.set(current), true, c -> setColor(c.rgba8888()))).disabled(b -> layoutEditing).colspan(2).growX();
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
            gridPanel = t;
            t.defaults().size(44f);
            t.button(Icon.grid, Styles.clearNoneTogglei, () -> grid = !grid).checked(grid).disabled(b -> layoutEditing).row();
            t.button(Icon.edit, Styles.clearNoneTogglei, this::beginLayoutEdit).checked(b -> layoutEditing).tooltip("@canvas.layoutedit");
        });

        // Palette with persistent custom colors.
        cont.table(Tex.button, p -> {
            palettePanel = p;
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
                    }).disabled(b -> layoutEditing).tooltip("@add");

                    // toggle delete mode for custom colors
                    bar.button(Icon.trash, Styles.clearNoneTogglei, () -> {
                        deletePaletteMode = !deletePaletteMode;
                    }).checked(b -> deletePaletteMode).disabled(b -> layoutEditing).tooltip("@save.delete");
                }).growX().left().row();

                int cols = 12;
                int idx = 0;

                // base palette
                for(int i = 0; i < block.palette.length; i++){
                    if(idx % cols == 0) p.row();
                    int rgba = block.palette[i];
                    ImageButton button = p.button(Tex.whiteui, Styles.squareTogglei, 30f, () -> {
                        setColor(rgba);
                    }).size(44f).checked(b -> curColor == rgba).disabled(b -> layoutEditing).get();
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
                    }).size(44f).checked(b -> curColor == rgba).disabled(b -> layoutEditing).get();
                    button.getStyle().imageUpColor = new Color(rgba);
                    idx++;
                }
            };

            rebuild[0].run();
        });

        buttons.defaults().size(150f, 64f);
        installLayoutDraggers();
        applyLayout();
    }

    void rebuildTools(){
        if(toolsPanel == null) return;
        toolsPanel.clearChildren();
        toolsPanel.defaults().size(44f).padRight(toolsVertical ? 0f : 4f).padBottom(toolsVertical ? 4f : 0f);
        
        addToolButton(Icon.pencil, () -> selectTool(CanvasTool.brush), b -> tool == CanvasTool.brush, "@canvas.brush");
        addToolButton(Icon.fill, () -> selectTool(CanvasTool.fill), b -> tool == CanvasTool.fill, "@canvas.fill");
        addToolButton(Icon.right, () -> selectTool(CanvasTool.line), b -> tool == CanvasTool.line, "@canvas.line");
        addToolButton(Icon.box, () -> selectTool(CanvasTool.rect), b -> tool == CanvasTool.rect, "@canvas.rect");
        addToolButton(Icon.box, () -> selectTool(CanvasTool.rectFill), b -> tool == CanvasTool.rectFill, "@canvas.rectfill");
        addToolButton(Icon.commandRally, () -> selectTool(CanvasTool.circle), b -> tool == CanvasTool.circle, "@canvas.circle");
        addToolButton(Icon.commandRally, () -> selectTool(CanvasTool.circleFill), b -> tool == CanvasTool.circleFill, "@canvas.circlefill");
        addToolButton(Icon.copy, () -> selectTool(CanvasTool.copy), b -> tool == CanvasTool.copy, "@canvas.copy");
        toolsPanel.button(Icon.paste, Styles.clearNoneTogglei, this::startPaste).checked(b -> tool == CanvasTool.paste).disabled(b -> layoutEditing || clipboard == null).tooltip("@canvas.paste");
        nextToolCell();
        addToolButton(Icon.spray, () -> selectTool(CanvasTool.spray), b -> tool == CanvasTool.spray, "@canvas.spray");
        addToolButton(Icon.refresh, () -> selectTool(CanvasTool.colorFill), b -> tool == CanvasTool.colorFill, "@canvas.colorfill");
        toolsPanel.button(Icon.undo, Styles.clearNonei, this::undo).disabled(b -> layoutEditing || undoStack.isEmpty()).tooltip("@canvas.undo");
        nextToolCell();
        toolsPanel.button(Icon.redo, Styles.clearNonei, this::redo).disabled(b -> layoutEditing || redoStack.isEmpty()).tooltip("@canvas.redo");
        nextToolCell();
        toolsPanel.button(Icon.ok, Styles.clearNonei, this::confirmPaste).visible(() -> paste != null).disabled(b -> layoutEditing).tooltip("@canvas.confirm");
        nextToolCell();
        toolsPanel.button(Icon.cancel, Styles.clearNonei, this::cancelPaste).visible(() -> paste != null).disabled(b -> layoutEditing).tooltip("@canvas.cancel");
        nextToolCell();

        toolsPanel.button(Icon.move, Styles.clearNonei, () -> {
            toolsVertical = !toolsVertical;
            rebuildTools();
        }).visible(() -> layoutEditing).tooltip("@edit");
    }

    void addToolButton(Drawable icon, Runnable clicked, Boolf<ImageButton> checked, String tooltip){
        toolsPanel.button(icon, Styles.clearNoneTogglei, clicked).checked(checked).disabled(b -> layoutEditing).tooltip(tooltip);
        nextToolCell();
    }

    void nextToolCell(){
        if(toolsVertical) toolsPanel.row();
    }

    boolean shouldBlendBrush(){
        return Core.input.shift() && current.a < 0.9999f;
    }

    int resolveBrushColor(int x, int y){
        if(!pix.in(x, y)) return curColor;
        return shouldBlendBrush() ? blendColors(pix.get(x, y), curColor) : curColor;
    }

    static int blendColors(int dst, int src){
        Tmp.c1.set(src);
        float sa = Tmp.c1.a;
        if(sa <= 0f) return dst;
        if(sa >= 1f) return src;

        Tmp.c2.set(dst);
        float da = Tmp.c2.a;
        float outA = sa + da * (1f - sa);
        if(outA <= 0.001f) return 0;

        return Color.rgba8888(
            (Tmp.c1.r * sa + Tmp.c2.r * da * (1f - sa)) / outA,
            (Tmp.c1.g * sa + Tmp.c2.g * da * (1f - sa)) / outA,
            (Tmp.c1.b * sa + Tmp.c2.b * da * (1f - sa)) / outA,
            outA
        );
    }

    boolean drawBrushPixel(int x, int y){
        return drawPixel(x, y, resolveBrushColor(x, y));
    }

    void rebuildButtons(){
        buttons.clearChildren();
        buttons.defaults().size(150f, 64f);
        if(layoutEditing){
            buttons.button("@save", Icon.save, this::saveLayoutEdit);
            buttons.button("@cancel", Icon.cancel, this::cancelLayoutEdit);
            buttons.row();
            buttons.button("@settings.resetKey", Icon.refresh, this::resetLayout).colspan(2).growX();
        }else{
            buttons.button("@back", Icon.left, this::hide);
            buttons.button("@import", Icon.image, () -> FileChooser.open("png").submit(this::importFrom));
            buttons.button("@export", Icon.export, () -> FileChooser.export("canvas", "png", this::exportTo));
        }
    }

    void beginLayoutEdit(){
        if(layoutEditing) return;
        saveOldLayout();
        layoutEditing = true;
        cancelPaste();
        rebuildButtons();
        rebuildTools();
    }

    void saveLayoutEdit(){
        layoutEditing = false;
        saveLayout();
        rebuildButtons();
        rebuildTools();
    }

    void cancelLayoutEdit(){
        toolsX = oldToolsX;
        toolsY = oldToolsY;
        bodyX = oldBodyX;
        bodyY = oldBodyY;
        controlsX = oldControlsX;
        controlsY = oldControlsY;
        gridX = oldGridX;
        gridY = oldGridY;
        paletteX = oldPaletteX;
        paletteY = oldPaletteY;
        toolsVertical = oldToolsVertical;
        layoutEditing = false;
        applyLayout();
        rebuildButtons();
        rebuildTools();
    }

    void resetLayout(){
        toolsX = toolsY = bodyX = bodyY = controlsX = controlsY = gridX = gridY = paletteX = paletteY = 0f;
        toolsVertical = false;
        applyLayout();
        rebuildTools();
    }

    void saveOldLayout(){
        oldToolsX = toolsX;
        oldToolsY = toolsY;
        oldBodyX = bodyX;
        oldBodyY = bodyY;
        oldControlsX = controlsX;
        oldControlsY = controlsY;
        oldGridX = gridX;
        oldGridY = gridY;
        oldPaletteX = paletteX;
        oldPaletteY = paletteY;
        oldToolsVertical = toolsVertical;
    }

    void loadLayout(){
        toolsX = Core.settings.getFloat(layoutKey + "-tools-x", 0f);
        toolsY = Core.settings.getFloat(layoutKey + "-tools-y", 0f);
        bodyX = Core.settings.getFloat(layoutKey + "-body-x", 0f);
        bodyY = Core.settings.getFloat(layoutKey + "-body-y", 0f);
        controlsX = Core.settings.getFloat(layoutKey + "-controls-x", 0f);
        controlsY = Core.settings.getFloat(layoutKey + "-controls-y", 0f);
        gridX = Core.settings.getFloat(layoutKey + "-grid-x", 0f);
        gridY = Core.settings.getFloat(layoutKey + "-grid-y", 0f);
        paletteX = Core.settings.getFloat(layoutKey + "-palette-x", 0f);
        paletteY = Core.settings.getFloat(layoutKey + "-palette-y", 0f);
        toolsVertical = Core.settings.getBool(layoutKey + "-tools-vertical", false);
    }

    void saveLayout(){
        Core.settings.put(layoutKey + "-tools-x", toolsX);
        Core.settings.put(layoutKey + "-tools-y", toolsY);
        Core.settings.put(layoutKey + "-body-x", bodyX);
        Core.settings.put(layoutKey + "-body-y", bodyY);
        Core.settings.put(layoutKey + "-controls-x", controlsX);
        Core.settings.put(layoutKey + "-controls-y", controlsY);
        Core.settings.put(layoutKey + "-grid-x", gridX);
        Core.settings.put(layoutKey + "-grid-y", gridY);
        Core.settings.put(layoutKey + "-palette-x", paletteX);
        Core.settings.put(layoutKey + "-palette-y", paletteY);
        Core.settings.put(layoutKey + "-tools-vertical", toolsVertical);
        Core.settings.manualSave();
    }

    void installLayoutDraggers(){
        installLayoutDragger(toolsPanel, "tools");
        installLayoutDragger(bodyPanel, "body");
        installLayoutDragger(controlsPanel, "controls");
        installLayoutDragger(gridPanel, "grid");
        installLayoutDragger(palettePanel, "palette");
    }

    void installLayoutDragger(Table table, String name){
        if(table == null) return;
        table.addListener(new InputListener(){
            float lastx, lasty;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(!layoutEditing || button != KeyCode.mouseLeft) return false;
                Vec2 v = table.localToParentCoordinates(Tmp.v1.set(x, y));
                lastx = v.x;
                lasty = v.y;
                table.toFront();
                event.stop();
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                Vec2 v = table.localToParentCoordinates(Tmp.v1.set(x, y));
                moveLayoutPanel(name, v.x - lastx, v.y - lasty);
                lastx = v.x;
                lasty = v.y;
                event.stop();
            }
        });
    }

    void moveLayoutPanel(String name, float dx, float dy){
        switch(name){
            case "tools" -> {
                toolsX += dx;
                toolsY += dy;
            }
            case "body" -> {
                bodyX += dx;
                bodyY += dy;
            }
            case "controls" -> {
                controlsX += dx;
                controlsY += dy;
            }
            case "grid" -> {
                gridX += dx;
                gridY += dy;
            }
            case "palette" -> {
                paletteX += dx;
                paletteY += dy;
            }
        }
        applyLayout();
    }

    void applyLayout(){
        setPanelTranslation(toolsPanel, toolsX, toolsY);
        setPanelTranslation(bodyPanel, bodyX, bodyY);
        setPanelTranslation(controlsPanel, controlsX, controlsY);
        setPanelTranslation(gridPanel, gridX, gridY);
        setPanelTranslation(palettePanel, paletteX, paletteY);
    }

    void setPanelTranslation(@Nullable Element element, float x, float y){
        if(element != null) element.setTranslation(x, y);
    }

    @Override
    public void draw(){
        if(layoutEditing){
            Draw.color(1f, 0.85f, 0.15f, 0.22f);
            Lines.stroke(Scl.scl(6f));
            float spacing = Scl.scl(42f), offset = (Time.time * Scl.scl(0.7f)) % spacing;
            float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
            for(float x = -h; x < w + h; x += spacing){
                Lines.line(x + offset, 0f, x + h + offset, h);
            }
            Draw.reset();
        }
        super.draw();
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
        spraying = false;
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
                drawBrushPixel(x + dx, y + dy);
            }
        }
    }

    void sprayAt(int x, int y){
        int radius = Math.max(0, brush - 1);
        if(radius == 0){
            drawBrushPixel(x, y);
            return;
        }

        int rr = radius * radius;
        for(int attempt = 0; attempt < radius * radius * 2; attempt++){
            int dx = (int)(Mathf.random() * (radius * 2 + 1)) - radius;
            int dy = (int)(Mathf.random() * (radius * 2 + 1)) - radius;
            if(dx * dx + dy * dy <= rr){
                drawBrushPixel(x + dx, y + dy);
                return;
            }
        }
    }

    void replaceColor(int from, int to){
        if(from == to && !shouldBlendBrush()) return;
        for(int x = 0; x < pix.width; x++){
            for(int y = 0; y < pix.height; y++){
                if(pix.get(x, y) == from){
                    drawPixel(x, y, shouldBlendBrush() ? blendColors(from, curColor) : to);
                }
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
                if(filled || x == minx || x == maxx || y == miny || y == maxy) drawBrushPixel(x, y);
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
                if(filled ? dst <= 1f : dst <= 1f && dst >= 0.72f) drawBrushPixel(x, y);
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
