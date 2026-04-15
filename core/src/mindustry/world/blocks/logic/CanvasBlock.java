package mindustry.world.blocks.logic;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.annotations.Annotations.*;
import mindustry.entities.units.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import static mindustry.Vars.*;

public class CanvasBlock extends Block{
    public float padding = 0f;
    public int canvasSize = 8;
    public int[] palette = {0x362944_ff, 0xc45d9f_ff, 0xe39aac_ff, 0xf0dab1_ff, 0x6461c2_ff, 0x2ba9b4_ff, 0x93d4b5_ff, 0xf0f6e8_ff};
    public int bitsPerPixel;
    public IntIntMap colorToIndex = new IntIntMap();
    /** If true, pixels are stored as raw RGBA8888 (4 bytes per pixel) instead of palette indices. */
    public boolean trueColor = true;

    public @Load("@-side1") TextureRegion side1;
    public @Load("@-side2") TextureRegion side2;

    public @Load("@-corner1") TextureRegion corner1;
    public @Load("@-corner2") TextureRegion corner2;

    protected @Nullable Pixmap previewPixmap; // please use only for previews
    protected @Nullable Texture previewTexture;
    protected int tempBlend = 0;

    public CanvasBlock(String name){
        super(name);

        configurable = true;
        destructible = true;
        canOverdrive = false;
        solid = true;

        config(byte[].class, (CanvasBuild build, byte[] bytes) -> {
            // truecolor payload
            if(trueColor && bytes.length == trueColorLength()){
                System.arraycopy(bytes, 0, build.data, 0, bytes.length);
                build.invalidateAll();
                return;
            }

            // legacy indexed payload
            if(bytes.length == legacyIndexedLength()){
                build.loadLegacy(bytes);
            }
        });
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

        previewPixmap = new Pixmap(canvasSize, canvasSize);
    }

    protected int trueColorLength(){
        return canvasSize * canvasSize * 4;
    }

    protected int legacyIndexedLength(){
        return Mathf.ceil(canvasSize * canvasSize * bitsPerPixel / 8f);
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
        if(!plan.worldContext && plan.config instanceof byte[] data){
            Pixmap pix = makePixmap(data, previewPixmap);

            if(previewTexture == null){
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
        if(data.length == trueColorLength()){
            int pixels = canvasSize * canvasSize;
            int o = 0;
            for(int i = 0; i < pixels; i++, o += 4){
                int c = ((data[o] & 0xff) << 24) | ((data[o + 1] & 0xff) << 16) | ((data[o + 2] & 0xff) << 8) | (data[o + 3] & 0xff);
                target.setRaw(i % canvasSize, i / canvasSize, c);
            }
            return target;
        }

        int bpp = bitsPerPixel;
        int pixels = canvasSize * canvasSize;
        for(int i = 0; i < pixels; i++){
            int bitOffset = i * bpp;
            int pal = getByte(data, bitOffset);
            target.set(i % canvasSize, i / canvasSize, palette[Math.min(pal, palette.length)]);
        }
        return target;
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
        public @Nullable Texture texture;
        /** Truecolor RGBA8888 pixels (always present when {@link CanvasBlock#trueColor} is enabled). */
        public byte[] data = new byte[trueColor ? trueColorLength() : legacyIndexedLength()];
        /** Cached legacy indexed bytes for network/schematic compatibility. */
        protected transient byte[] legacyCache = new byte[legacyIndexedLength()];
        protected transient boolean legacyInvalidated = true;
        public int blending;
        protected boolean invalidated = false;

        protected void invalidateAll(){
            invalidated = true;
            legacyInvalidated = true;
        }

        /** Applies a truecolor RGBA8888 payload to this canvas. */
        public void applyTrueColor(byte[] rgba){
            if(!trueColor || rgba == null || rgba.length != trueColorLength()) return;
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

            Pixmap pix = makePixmap(data, previewPixmap);
            if(texture != null){
                texture.draw(pix);
            }else{
                texture = new Texture(pix);
            }

            invalidated = false;
        }

        /** Fills truecolor data from a legacy indexed payload. */
        protected void loadLegacy(byte[] legacy){
            if(!trueColor || legacy.length != legacyIndexedLength()) return;

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
            byte[] bytes = new byte[data.length];
            int pixels = canvasSize * canvasSize;
            for(int i = 0; i < pixels; i++){
                int x = i % canvasSize, y = i / canvasSize;
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
            if(texture != null){
                texture.dispose();
                texture = null;
            }
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
            return data;
        }

        @Override
        public void write(Writes write){
            super.write(write);

            //for future canvas resizing events
            write.i(data.length);
            write.b(data);
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);

            int len = read.i();
            if(data.length == len){
                read.b(data);
                invalidateAll();
                return;
            }

            //legacy indexed format -> convert to truecolor if enabled
            if(trueColor && len == legacyIndexedLength()){
                byte[] legacy = new byte[len];
                read.b(legacy);
                loadLegacy(legacy);
            }else{
                read.skip(len);
            }
        }
    }
}
