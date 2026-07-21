package mindustry.world.blocks.logic;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.pooling.*;
import mindustry.annotations.Annotations.*;
import mindustry.core.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import java.io.*;
import java.nio.charset.*;
import java.util.Arrays;

import static mindustry.Vars.*;

public class CanvasBlock extends Block{
    public float padding = 0f;
    public int canvasSize = 8;
    public int[] palette = {0x362944_ff, 0xc45d9f_ff, 0xe39aac_ff, 0xf0dab1_ff, 0x6461c2_ff, 0x2ba9b4_ff, 0x93d4b5_ff, 0xf0f6e8_ff};
    public int bitsPerPixel;
    public IntIntMap colorToIndex = new IntIntMap();
    /** If true, pixels are stored as raw RGBA8888 (4 bytes per pixel) instead of palette indices. */
    public boolean trueColor = true;
    /** Maximum character count of the canvas description. */
    public int maxDescriptionLength = 22000;
    /** Hard cap on the UTF-8 byte size of a description, so writeUTF/packet buffers can never overflow. */
    public int maxDescriptionBytes = 60000;
    /** Smallest and largest per-canvas resolution a player may set. */
    public int minCanvasSize = 1, maxCanvasSize = 256;
    /** Resolutions above this are allowed but warned about, as editing them gets very heavy. */
    public int canvasSizeWarnThreshold = 64;
    /** Identifies a config payload carrying pixels + description. */
    protected static final int configMagic = 0xDECA0001;
    /** Identifies a config payload carrying an explicit resolution + pixels + description. */
    protected static final int configMagicSized = 0xDECA0002;

    public @Load("@-side1") TextureRegion side1;
    public @Load("@-side2") TextureRegion side2;

    public @Load("@-corner1") TextureRegion corner1;
    public @Load("@-corner2") TextureRegion corner2;

    /** Scratch pixmaps keyed by resolution; canvases of different sizes coexist, so one shared pixmap is not enough. */
    protected IntMap<Pixmap> previewPixmaps = new IntMap<>(); // please use only for previews
    protected @Nullable Texture previewTexture;
    protected int tempBlend = 0;

    public CanvasBlock(String name){
        super(name);

        configurable = true;
        destructible = true;
        canOverdrive = false;
        solid = true;

        config(byte[].class, (CanvasBuild build, byte[] bytes) -> {
            // extended payload: resolution + pixels + description, used by schematics and config copying
            var extended = readConfig(bytes);
            if(extended != null){
                //adopt the stored resolution before the pixels, so the payload length lines up
                if(extended.size > 0) build.adoptCanvasSize(extended.size);
                applyPixelConfig(build, extended.pixels);
                applyDescription(build, extended.description);
                return;
            }

            applyPixelConfig(build, bytes);
        });

        config(String.class, (CanvasBuild build, String text) -> applyDescription(build, text));
    }

    protected void applyPixelConfig(CanvasBuild build, byte[] bytes){
        // truecolor payload
        if(trueColor && bytes.length == trueColorLength(build.canvasSize)){
            if(build.data.length != bytes.length) build.data = new byte[bytes.length];
            System.arraycopy(bytes, 0, build.data, 0, bytes.length);
            build.invalidateAll();
            return;
        }

        // legacy indexed payload
        if(bytes.length == legacyIndexedLength(build.canvasSize)){
            // On vanilla servers, skip when the echoed legacy matches our local truecolor snapshot
            // so custom colors are not wiped after save.
            if(trueColor && net.client() && !netClient.deltaServer && Arrays.equals(bytes, build.legacyBytes())) return;
            build.loadLegacy(bytes);
        }
    }

    /** @return whether this is a resolution a player is allowed to set. */
    public boolean validCanvasSize(int size){
        return size >= minCanvasSize && size <= maxCanvasSize;
    }

    /** @return a scratch pixmap of the given resolution, allocated on demand and reused afterwards. */
    protected Pixmap previewPixmap(int size){
        Pixmap pixmap = previewPixmaps.get(size);
        if(pixmap == null){
            pixmap = new Pixmap(size, size);
            previewPixmaps.put(size, pixmap);
        }
        return pixmap;
    }

    protected void applyDescription(CanvasBuild build, String text){
        if(text == null) text = "";
        if(text.length() > maxDescriptionLength || text.getBytes(StandardCharsets.UTF_8).length > maxDescriptionBytes){
            return; //no.
        }

        build.description.setLength(0);
        build.description.append(text);
    }

    /** Decoded contents of an extended canvas config payload. */
    public static class CanvasConfig{
        public byte[] pixels;
        public String description = "";
        /** Canvas resolution, or 0 when the payload predates per-canvas resolutions. */
        public int size;
    }

    /**
     * Packs resolution, pixels and description into one config payload, so schematics and config copying carry all
     * three. Only used when a description or a non-default resolution is present; plain canvases keep emitting raw
     * pixel bytes, byte-identical to what they emitted before this format existed.
     */
    public byte[] packConfig(byte[] pixels, int size, String description){
        byte[] desc = description.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream(pixels.length + desc.length + 16);
        var write = new Writes(new DataOutputStream(out));
        write.i(configMagicSized);
        write.i(size);
        write.i(pixels.length);
        write.b(pixels);
        write.i(desc.length);
        write.b(desc);
        return out.toByteArray();
    }

    /** @return the decoded extended payload, or null if these bytes are plain pixel data. */
    public @Nullable CanvasConfig readConfig(byte[] bytes){
        //a payload carrying pixels is always longer than the header, and never collides with a raw pixel length
        if(bytes == null || bytes.length < 12) return null;

        try{
            var read = new Reads(new DataInputStream(new ByteArrayInputStream(bytes)));
            int magic = read.i();
            //configMagic is the older layout without a resolution; schematics saved before resizing existed still use it
            if(magic != configMagic && magic != configMagicSized) return null;

            var config = new CanvasConfig();

            if(magic == configMagicSized){
                int size = read.i();
                if(!validCanvasSize(size)) return null;
                config.size = size;
            }

            int pixelLen = read.i();
            if(pixelLen < 0 || pixelLen > bytes.length) return null;
            config.pixels = read.b(new byte[pixelLen]);

            int descLen = read.i();
            if(descLen < 0 || descLen > bytes.length) return null;
            config.description = descLen == 0 ? "" : new String(read.b(new byte[descLen]), StandardCharsets.UTF_8);

            return config;
        }catch(Exception e){
            return null;
        }
    }

    public void setPaletteFromString(String value){
        String[] split = value.split("\n");
        palette = new int[split.length];
        for(int i = 0; i < split.length; i++){
            palette[i] = (Integer.parseInt(split[i], 16) << 8) | 0xff;
        }
    }

    @Override
    public void init(){
        super.init();

        for(int i = 0; i < palette.length; i++){
            colorToIndex.put(palette[i], i);
        }
        bitsPerPixel = Mathf.log2(Mathf.nextPowerOfTwo(palette.length));

        clipSize = Math.max(clipSize, size * 8 - padding);
    }

    protected int trueColorLength(){
        return trueColorLength(canvasSize);
    }

    protected int legacyIndexedLength(){
        return legacyIndexedLength(canvasSize);
    }

    protected int trueColorLength(int size){
        return size * size * 4;
    }

    protected int legacyIndexedLength(int size){
        return Mathf.ceil(size * size * bitsPerPixel / 8f);
    }

    /** Quantize an RGBA8888 color to the nearest palette index. */
    protected int quantizeToPaletteIndex(int rgba){
        // 1) Exact ARGB match (same as palette literals / truecolor buffer).
        for(int i = 0; i < palette.length; i++){
            if(palette[i] == rgba) return i;
        }

        // 2) Same RGB as a palette swatch; alpha may differ slightly after Pixmap/GL (vanilla legacy must match palette strokes).
        int r = (rgba >> 16) & 0xff, g = (rgba >> 8) & 0xff, b = rgba & 0xff;
        for(int i = 0; i < palette.length; i++){
            int p = palette[i];
            int pr = (p >> 16) & 0xff, pg = (p >> 8) & 0xff, pb = p & 0xff;
            if(r == pr && g == pg && b == pb) return i;
        }

        // 3) Semi-transparent: blend over background like vanilla indexed canvas, then try exact / RGB again.
        if(Color.ai(rgba) < 255){
            int blended = Pixmap.blend(palette[0], rgba);
            for(int i = 0; i < palette.length; i++){
                if(palette[i] == blended) return i;
            }
            int br = (blended >> 16) & 0xff, bg = (blended >> 8) & 0xff, bb = blended & 0xff;
            for(int i = 0; i < palette.length; i++){
                int p = palette[i];
                int pr = (p >> 16) & 0xff, pg = (p >> 8) & 0xff, pb = p & 0xff;
                if(br == pr && bg == pg && bb == pb) return i;
            }
            rgba = blended;
        }

        Tmp.c1.set(rgba);
        float nearestDst = 1e9f;
        int nearest = 0;

        for(int i = 0; i < palette.length; i++){
            int pc = palette[i];
            Tmp.c2.set(pc);
            float dst = Tmp.c1.dst(Tmp.c2);
            if(dst < nearestDst){
                nearestDst = dst;
                nearest = i;
            }
        }

        return nearest;
    }

    @Override
    public void drawPlanRegion(BuildPlan plan, Eachable<BuildPlan> list){
        //only draw the preview in schematics, as it lags otherwise
        if(!plan.worldContext && plan.config instanceof byte[] configBytes){
            var extended = readConfig(configBytes);
            byte[] pixelBytes = extended != null ? extended.pixels : configBytes;

            //the plan may hold a canvas of any resolution, so derive it rather than assuming the block default
            int planSize = extended != null && extended.size > 0 ? extended.size : sizeOfPayload(pixelBytes.length);
            if(planSize <= 0) planSize = canvasSize;

            Pixmap pix = makePixmap(pixelBytes, previewPixmap(planSize), planSize);

            if(previewTexture == null || previewTexture.width != pix.width || previewTexture.height != pix.height){
                if(previewTexture != null) previewTexture.dispose();
                previewTexture = new Texture(pix);
            }else{
                previewTexture.draw(pix);
            }

            tempBlend = 0;

            findPlan(list, plan.x, plan.y, size + 1, other -> {
                if(other.block == this){
                    for(int i = 0; i < 4; i++){
                        if(other.x == plan.x + Geometry.d4x(i) * size && other.y == plan.y + Geometry.d4y(i) * size){
                            tempBlend |= (1 << i);
                        }
                    }
                }
                return false;
            });

            int blending = tempBlend;

            float x = plan.drawx(), y = plan.drawy();
            Tmp.tr1.set(previewTexture);
            float pad = blending == 0 ? padding : 0f;

            Draw.rect(Tmp.tr1, x, y, size * tilesize - pad, size * tilesize - pad);
            Draw.flush(); //texture is reused, so flush it now

            //code duplication, awful
            for(int i = 0; i < 4; i ++){
                if((blending & (1 << i)) == 0){
                    Draw.rect(i >= 2 ? side2 : side1, x, y, i * 90);

                    if((blending & (1 << ((i + 1) % 4))) != 0){
                        Draw.rect(i >= 2 ? corner2 : corner1, x, y, i * 90);
                    }

                    if((blending & (1 << (Mathf.mod(i - 1, 4)))) != 0){
                        Draw.yscl = -1f;
                        Draw.rect(i >= 2 ? corner2 : corner1, x, y, i * 90);
                        Draw.yscl = 1f;
                    }
                }
            }
        }else{
            super.drawPlanRegion(plan, list);
        }
    }

    public Pixmap makePixmap(byte[] data, Pixmap target){
        return makePixmap(data, target, target.width);
    }

    public Pixmap makePixmap(byte[] data, Pixmap target, int size){
        if(data.length == trueColorLength(size)){
            int pixels = size * size;
            int o = 0;
            for(int i = 0; i < pixels; i++, o += 4){
                int c = ((data[o] & 0xff) << 24) | ((data[o + 1] & 0xff) << 16) | ((data[o + 2] & 0xff) << 8) | (data[o + 3] & 0xff);
                target.setRaw(i % size, i / size, c);
            }
            return target;
        }

        int bpp = bitsPerPixel;
        int pixels = size * size;
        for(int i = 0; i < pixels; i++){
            int bitOffset = i * bpp;
            int pal = getByte(data, bitOffset);
            target.set(i % size, i / size, palette[Math.min(pal, palette.length)]);
        }
        return target;
    }

    /** @return the resolution a raw pixel payload of this length represents, or 0 if it matches no valid resolution. */
    public int sizeOfPayload(int length){
        if(trueColor && length % 4 == 0){
            int pixels = length / 4;
            int size = (int)Math.round(Math.sqrt(pixels));
            if(size * size == pixels && validCanvasSize(size)) return size;
        }
        return 0;
    }

    protected int getByte(byte[] data, int bitOffset){
        int result = 0, bpp = bitsPerPixel;
        for(int i = 0; i < bpp; i++){
            int word = i + bitOffset >>> 3;
            result |= (((data[word] & (1 << (i + bitOffset & 7))) == 0 ? 0 : 1) << i);
        }
        return result;
    }

    public class CanvasBuild extends Building implements LReadable, LWritable{
        /**
         * Per-canvas resolution. Deliberately shadows {@link CanvasBlock#canvasSize}, which is only the default for
         * newly placed canvases — every canvas can be resized independently, so code inside this class must always
         * mean this field. Use the sized {@code trueColorLength(int)} / {@code legacyIndexedLength(int)} overloads.
         */
        public int canvasSize = CanvasBlock.this.canvasSize;
        public @Nullable Texture texture;
        /** Truecolor RGBA8888 pixels (always present when {@link CanvasBlock#trueColor} is enabled). */
        public byte[] data = new byte[trueColor ? trueColorLength(canvasSize) : legacyIndexedLength(canvasSize)];
        /** Free-form text shown under the canvas when hovered, like a message block. */
        public StringBuilder description = new StringBuilder();
        /** Cached legacy indexed bytes for network/schematic compatibility. */
        protected transient byte[] legacyCache = new byte[legacyIndexedLength(canvasSize)];
        protected transient boolean legacyInvalidated = true;
        public int blending;
        protected boolean invalidated = false;

        protected void invalidateAll(){
            invalidated = true;
            legacyInvalidated = true;
        }

        /**
         * Switches to a new resolution, discarding the current pixels. Callers are expected to supply replacement
         * pixel data immediately afterwards; use {@link #resizeCanvas(int)} to keep the existing image instead.
         */
        protected void adoptCanvasSize(int newSize){
            if(!validCanvasSize(newSize) || newSize == canvasSize) return;

            canvasSize = newSize;
            data = new byte[trueColor ? trueColorLength(newSize) : legacyIndexedLength(newSize)];
            legacyCache = new byte[legacyIndexedLength(newSize)];
            invalidateAll();

            //the texture dimensions changed, so it cannot simply be redrawn
            disposeTexture();
        }

        /** Changes the resolution, keeping the existing image centered at its original scale (cropping if shrinking). */
        public void resizeCanvas(int newSize){
            if(!validCanvasSize(newSize) || newSize == canvasSize) return;

            int oldSize = canvasSize;
            byte[] old = data;
            byte[] next = new byte[trueColor ? trueColorLength(newSize) : legacyIndexedLength(newSize)];

            //a positive offset pads the image, a negative one crops it — both keep the old content centered
            int offset = (newSize - oldSize) / 2;
            for(int y = 0; y < oldSize; y++){
                int ny = y + offset;
                if(ny < 0 || ny >= newSize) continue;

                for(int x = 0; x < oldSize; x++){
                    int nx = x + offset;
                    if(nx < 0 || nx >= newSize) continue;

                    int from = y * oldSize + x, to = ny * newSize + nx;
                    if(trueColor){
                        setColor(next, to, getColor(old, from));
                    }else{
                        setByte(next, to * bitsPerPixel, getByte(old, from * bitsPerPixel));
                    }
                }
            }

            canvasSize = newSize;
            data = next;
            legacyCache = new byte[legacyIndexedLength(newSize)];
            invalidateAll();

            disposeTexture();
        }

        protected void disposeTexture(){
            if(texture != null){
                texture.dispose();
                texture = null;
            }
        }

        /** Applies a truecolor RGBA8888 payload to this canvas. */
        public void applyTrueColor(byte[] rgba){
            if(!trueColor || rgba == null) return;

            if(rgba.length != trueColorLength(canvasSize)){
                //a resize may not have been applied yet; adopt whatever resolution this payload implies
                int implied = sizeOfPayload(rgba.length);
                if(implied <= 0) return;
                adoptCanvasSize(implied);
            }

            if(data.length != rgba.length) data = new byte[rgba.length];
            System.arraycopy(rgba, 0, data, 0, rgba.length);
            invalidateAll();
        }

        /** Returns legacy indexed bytes for vanilla/network compatibility. */
        public byte[] legacyBytesPublic(){
            return legacyBytes();
        }

        public void setPixel(int pos, int index){
            if(pos < canvasSize * canvasSize && pos >= 0){
                if(trueColor){
                    setColor(data, pos, index);
                }else if(index >= 0 && index < palette.length){
                    setByte(data, pos * bitsPerPixel, index);
                }else{
                    return;
                }
                invalidateAll();
            }
        }

        public double getPixel(int pos){
            if(pos >= 0 && pos < canvasSize * canvasSize){
                if(trueColor){
                    return (double)(getColor(data, pos) & 0xffffffffL);
                }else{
                    return getByte(data, pos * bitsPerPixel);
                }
            }
            return Double.NaN;
        }

        public void updateTexture(){
            if(headless || (texture != null && !invalidated)) return;

            Pixmap pix = makePixmap(data, previewPixmap(canvasSize), canvasSize);
            if(texture != null){
                texture.draw(pix);
            }else{
                texture = new Texture(pix);
            }

            invalidated = false;
        }

        /** Fills truecolor data from a legacy indexed payload. */
        protected void loadLegacy(byte[] legacy){
            if(!trueColor || legacy.length != legacyIndexedLength(canvasSize)) return;

            int pixels = canvasSize * canvasSize;
            int bpp = bitsPerPixel;
            for(int i = 0; i < pixels; i++){
                int pal = getByte(legacy, i * bpp);
                int col = palette[Math.min(pal, palette.length)];
                setColor(data, i, col);
            }

            // keep cache in sync so config() can return without re-quantizing immediately
            System.arraycopy(legacy, 0, legacyCache, 0, legacy.length);
            invalidated = true;
            legacyInvalidated = false;
        }

        protected byte[] legacyBytes(){
            if(!trueColor) return data;

            if(legacyInvalidated){
                int pixels = canvasSize * canvasSize;
                for(int i = 0; i < pixels; i++){
                    int rgba = getColor(data, i);
                    int palIndex = quantizeToPaletteIndex(rgba);
                    setByte(legacyCache, i * bitsPerPixel, palIndex);
                }
                legacyInvalidated = false;
            }

            return legacyCache;
        }

        public byte[] packPixmap(Pixmap pixmap){
            //pack at the pixmap's own resolution, so a freshly resized pixmap can be packed before the build adopts it
            return packPixmap(pixmap, pixmap.width);
        }

        public byte[] packPixmap(Pixmap pixmap, int size){
            byte[] bytes = new byte[trueColor ? trueColorLength(size) : legacyIndexedLength(size)];
            int pixels = size * size;
            for(int i = 0; i < pixels; i++){
                int x = i % size, y = i / size;
                // Must use get(), not getRaw(): the editor draws with set(..., rgba8888) and compares get();
                // getRaw() uses native pixmap channel order and breaks pure channel colors (e.g. full red → black).
                int color = pixmap.get(x, y);
                if(trueColor){
                    setColor(bytes, i, color);
                }else{
                    int palIndex = colorToIndex.get(color);
                    setByte(bytes, i * bitsPerPixel, palIndex);
                }
            }
            return bytes;
        }

        protected int getColor(byte[] bytes, int pos){
            int o = pos * 4;
            return ((bytes[o] & 0xff) << 24) | ((bytes[o + 1] & 0xff) << 16) | ((bytes[o + 2] & 0xff) << 8) | (bytes[o + 3] & 0xff);
        }

        protected void setColor(byte[] bytes, int pos, int color){
            int o = pos * 4;
            bytes[o] = (byte)(color >>> 24);
            bytes[o + 1] = (byte)(color >>> 16);
            bytes[o + 2] = (byte)(color >>> 8);
            bytes[o + 3] = (byte)(color);
        }

        protected void setByte(byte[] bytes, int bitOffset, int value){
            int bpp = bitsPerPixel;
            for(int i = 0; i < bpp; i++){
                int word = i + bitOffset >>> 3;

                if(((value >>> i) & 1) == 0){
                    bytes[word] &= ~(1 << (i + bitOffset & 7));
                }else{
                    bytes[word] |= (1 << (i + bitOffset & 7));
                }
            }
        }

        @Override
        public void onProximityUpdate(){
            super.onProximityUpdate();

            blending = 0;
            for(int i = 0; i < 4; i++){
                if(blends(world.tile(tile.x + Geometry.d4[i].x * size, tile.y + Geometry.d4[i].y * size))) blending |= (1 << i);
            }
        }

        @Override
        public void afterPickedUp(){
            super.afterPickedUp();
            blending = 0;
        }

        @Override
        public void dropped(){
            super.dropped();

            onProximityUpdate();
        }

        @Override
        public boolean readable(LExecutor exec){
            return isValid() && (exec.privileged || this.team == exec.team);
        }

        @Override
        public void read(LVar position, LVar output){
            output.setnum(getPixel(position.numi()));
        }

        @Override
        public boolean writable(LExecutor exec){
            return readable(exec);
        }

        @Override
        public void write(LVar position, LVar value){
            int pos = position.numi();

            if(trueColor){
                // Logic can provide colors in two forms:
                // 1) As a normal integer (e.g. 0xff0000ff) -> numeric value fits in unsigned 32-bit range.
                // 2) As a packed-color double (e.g. %ffffffff or via colorpack) -> RGBA bytes are stored in the raw bits.
                double d = value.num();
                long asLong = (long)d;

                int rgba;
                if(d == (double)asLong && asLong >= 0L && asLong <= 0xFFFFFFFFL){
                    rgba = (int)asLong;
                }else{
                    rgba = (int)Double.doubleToRawLongBits(d);
                }

                setPixel(pos, rgba);
            }else{
                setPixel(pos, value.numi());
            }
        }

        boolean blends(Tile other){
            return other != null && other.build != null && other.build.block == block && other.build.tileX() == other.x && other.build.tileY() == other.y;
        }

        @Override
        public void draw(){
            if(!renderer.drawDisplays){
                super.draw();

                return;
            }

            if(blending == 0){
                super.draw();
            }

            if(texture == null || invalidated){
                updateTexture();
            }

            Tmp.tr1.set(texture);
            float pad = blending == 0 ? padding : 0f;

            Draw.rect(Tmp.tr1, x, y, size * tilesize - pad, size * tilesize - pad);
            for(int i = 0; i < 4; i ++){
                if((blending & (1 << i)) == 0){
                    Draw.rect(i >= 2 ? side2 : side1, x, y, i * 90);

                    if((blending & (1 << ((i + 1) % 4))) != 0){
                        Draw.rect(i >= 2 ? corner2 : corner1, x, y, i * 90);
                    }

                    if((blending & (1 << (Mathf.mod(i - 1, 4)))) != 0){
                        Draw.yscl = -1f;
                        Draw.rect(i >= 2 ? corner2 : corner1, x, y, i * 90);
                        Draw.yscl = 1f;
                    }
                }
            }
        }

        @Override
        public double sense(LAccess sensor){
            return switch(sensor){
                case displayWidth, displayHeight -> canvasSize;
                default -> super.sense(sensor);
            };
        }

        @Override
        public void remove(){
            super.remove();
            disposeTexture();
        }

        @Override
        public void drawSelect(){
            if(renderer.pixelate || description.length() == 0) return;

            Font font = Fonts.outline;
            GlyphLayout l = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
            boolean ints = font.usesIntegerPositions();
            font.getData().setScale(1 / 4f / Scl.scl(1f));
            font.setUseIntegerPositions(false);

            String text = UI.formatIcons(description.toString());

            l.setText(font, text, Color.white, 90f, Align.left, true);
            float offset = 1f;
            float top = y - size * tilesize/2f;

            Draw.color(0f, 0f, 0f, 0.2f);
            Fill.rect(x, top - l.height/2f - offset, l.width + offset*2f, l.height + offset*2f);
            Draw.color();
            font.setColor(Color.white);
            font.draw(text, x - l.width/2f, top - offset, 90f, Align.left, true);
            font.setUseIntegerPositions(ints);

            font.getData().setScale(1f);

            Pools.free(l);
        }

        @Override
        public void updateTableAlign(Table table){
            //keep the config table above the block so it never covers the description text
            Vec2 pos = Core.input.mouseScreen(x, y + size * tilesize / 2f + 1);
            table.setPosition(pos.x, pos.y, Align.bottom);
        }

        @Override
        public void buildConfiguration(Table table){
            table.button(Icon.pencil, Styles.cleari, () -> new CanvasEditDialog(this).show()).size(40f);
        }

        @Override
        public boolean onConfigureBuildTapped(Building other){
            if(this == other){
                deselect();
                return false;
            }

            return true;
        }

        @Override
        public byte[] config(){
            // Return raw data (truecolor RGBA8888 or legacy indexed depending on mode)
            // so schematics preserve full color information for delta clients.
            // Vanilla clients receiving unknown-length bytes via TileConfig will ignore them;
            // live network broadcast to vanilla clients is handled via legacyBytesPublic() separately.
            // With a description or a non-default resolution present, everything is packed together so schematics
            // and config copying (F) carry it all; otherwise the payload stays byte-identical to before.
            return description.length() == 0 && canvasSize == CanvasBlock.this.canvasSize
                ? data
                : packConfig(data, canvasSize, description.toString());
        }

        @Override
        public byte version(){
            return 2;
        }

        @Override
        public void write(Writes write){
            super.write(write);

            //revision 2+: resolution is per-canvas, so it has to be stored rather than taken from the block
            write.i(canvasSize);

            write.i(data.length);
            write.b(data);

            //revision 1+: description as length-prefixed UTF-8, since writeUTF caps out at 64kb
            byte[] desc = description.toString().getBytes(StandardCharsets.UTF_8);
            write.i(desc.length);
            write.b(desc);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);

            //revision 0 and 1 predate resizing; those canvases are at whatever the block default was when drawn
            if(revision >= 2){
                int storedSize = read.i();
                if(validCanvasSize(storedSize)) adoptCanvasSize(storedSize);
            }

            int len = read.i();
            if(trueColor && len == trueColorLength(canvasSize)){
                if(data.length != len) data = new byte[len];
                read.b(data);
                invalidateAll();
            }else if(data.length == len){
                read.b(data);
                invalidateAll();
            }else if(trueColor && len == legacyIndexedLength(canvasSize)){
                //legacy indexed format -> convert to truecolor if enabled
                byte[] legacy = new byte[len];
                read.b(legacy);
                loadLegacy(legacy);
            }else{
                read.skip(len);
            }

            description.setLength(0);
            //revision 0 predates descriptions; those canvases simply have no text
            if(revision >= 1){
                int descLen = read.i();
                if(descLen > 0){
                    description.append(new String(read.b(new byte[descLen]), StandardCharsets.UTF_8));
                }
            }
        }
    }
}
