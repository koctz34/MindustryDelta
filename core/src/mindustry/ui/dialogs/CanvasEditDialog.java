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

    int curColor;
    boolean fill, modified, grid = true;
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

        cont.table(Tex.pane, body -> {
            body.center();

            //canvas element centered; tools panel is separate
            var canvasElement = new Element(){
                int lastX, lastY;
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
                                if(fill){
                                    if(!pix.in(cx, cy)) return false;
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

                                    return false;
                                }else{
                                    drawBrush(cx, cy);
                                    lastX = cx;
                                    lastY = cy;
                                }
                            }else if(button == KeyCode.mouseMiddle){
                                CanvasEditDialog.this.setColor(pix.get(cx, cy));
                                return false;
                            }
                            return true;
                        }

                        @Override
                        public void touchDragged(InputEvent event, float ex, float ey, int pointer){
                            if(fill) return;
                            int cx = convertX(ex), cy = convertY(ey);
                            Bresenham2.line(lastX, lastY, cx, cy, (x, y) -> drawBrush(x, y));
                            lastX = cx;
                            lastY = cy;
                        }
                    });
                }

                void drawBrush(int x, int y){
                    int radius = Math.max(0, brush - 1);
                    int rr = radius * radius;
                    for(int dx = -radius; dx <= radius; dx++){
                        for(int dy = -radius; dy <= radius; dy++){
                            if(dx*dx + dy*dy > rr) continue;
                            int px = x + dx, py = y + dy;
                            if(pix.in(px, py) && pix.get(px, py) != curColor){
                                pix.set(px, py, curColor);
                                Pixmaps.drawPixel(texture, px, py, curColor);
                                modified = true;
                            }
                        }
                    }
                }

                @Override
                public void draw(){
                    Tmp.tr1.set(texture);
                    Draw.alpha(parentAlpha);
                    Draw.rect(Tmp.tr1, x + width/2f, y + height/2f, width, height);

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

        cont.table(Tex.button, t -> {
            t.button(Icon.fill, Styles.clearNoneTogglei, () -> fill = !fill).size(44f);
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
                pix.fill(block.palette[0]);
                Pixmap dest = new Pixmap(size, size);
                dest.draw(source, (size - source.width)/2, (size - source.height)/2);
                source.dispose();
                source = dest;
            }
            int sizeX = Math.min(source.width, pix.width), sizeY = Math.min(source.height, pix.height);
            for(int x = 0; x < sizeX; x++){
                for(int y = 0; y < sizeY; y++){
                    pix.setRaw(x, y, source.getRaw(x, y));
                }
            }

            texture.draw(pix);
            modified = true;
        }catch(Exception e){
            ui.showException("@editor.errorload", e);
        }
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

                var stream = new arc.util.io.ReusableByteOutStream();
                var writes = new arc.util.io.Writes(new java.io.DataOutputStream(stream));
                writes.i(canvas.pos());
                writes.i(rgba.length);
                writes.b(rgba);
                Call.serverBinaryPacketReliable("delta-canvas", stream.toByteArray());
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
